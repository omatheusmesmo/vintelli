package dev.omatheusmesmo.vintelli.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@WithTestResource(AcpChatFileSystemAgentResource.class)
class AcpChatFileSystemTest {

    private static final AcpChatPort.Message READ_RESULT =
            new AcpChatPort.Message("result:fs-read=" + AcpChatFileSystemAgentResource.NOTES);

    @Inject
    AcpChatService chat;

    @Test
    void agentReadsWritesAndRunsATerminalCommandThroughAChatSession() throws Exception {
        List<AcpChatPort.Update> updates = new CopyOnWriteArrayList<>();
        String session = openSession(updates::add);
        try {
            chat.prompt(session, "fs:" + AcpChatFileSystemAgentResource.projectRoot());
            chat.prompt(session, "terminal");

            assertTrue(updates.contains(READ_RESULT), updates.toString());
            assertTrue(updates.contains(new AcpChatPort.Message("result:fs-write=OK")), updates.toString());
            assertEquals("written-by-agent",
                    Files.readString(AcpChatFileSystemAgentResource.projectRoot().resolve("out.txt")));
            assertTrue(updates.contains(new AcpChatPort.Message("result:terminal=exit:0,version:true")), updates.toString());
        } finally {
            chat.close(session);
        }
    }

    @Test
    void readOutsideTheProjectRootIsRefusedEvenWhenTheFileExists() {
        List<AcpChatPort.Update> updates = new CopyOnWriteArrayList<>();
        String session = openSession(updates::add);
        try {
            chat.prompt(session, "fs:" + AcpChatFileSystemAgentResource.projectRoot());

            assertTrue(updates.contains(new AcpChatPort.Message("result:fs-outside=DENIED")), updates.toString());
            assertFalse(updates.contains(new AcpChatPort.Message("result:fs-outside=ALLOWED")), updates.toString());
        } finally {
            chat.close(session);
        }
    }

    @Test
    void closingTheSessionKillsAndReleasesItsRunningTerminals() throws Exception {
        String session = openSession(update -> { });
        Set<ProcessHandle> childrenBefore = childProcesses();
        try {
            chat.prompt(session, "hold-terminal");
            List<ProcessHandle> terminal = newProcessTree(childrenBefore);
            assertTrue(terminal.stream().allMatch(ProcessHandle::isAlive), "the terminal should run before close");

            chat.close(session);

            assertDead(terminal);
        } finally {
            chat.close(session);
        }
    }

    @Test
    void closingOneSessionLeavesTheTerminalsOfAnotherSessionRunning() throws Exception {
        String first = openSession(update -> { });
        String second = openSession(update -> { });
        try {
            Set<ProcessHandle> childrenBeforeFirst = childProcesses();
            chat.prompt(first, "hold-terminal");
            List<ProcessHandle> firstTerminal = newProcessTree(childrenBeforeFirst);
            Set<ProcessHandle> childrenBeforeSecond = childProcesses();
            chat.prompt(second, "hold-terminal");
            List<ProcessHandle> secondTerminal = newProcessTree(childrenBeforeSecond);

            chat.close(first);

            assertDead(firstTerminal);
            assertTrue(secondTerminal.stream().allMatch(ProcessHandle::isAlive),
                    "closing one session must not release the terminals of another");
        } finally {
            chat.close(first);
            chat.close(second);
        }
    }

    private String openSession(Consumer<AcpChatPort.Update> updates) {
        return chat.openSession(AcpChatFileSystemAgentResource.AGENT_ID, updates,
                request -> AcpChatPort.PermissionChoice.cancel());
    }

    private static Set<ProcessHandle> childProcesses() {
        return ProcessHandle.current().children().collect(Collectors.toSet());
    }

    private static List<ProcessHandle> newProcessTree(Set<ProcessHandle> childrenBefore) {
        List<ProcessHandle> started = ProcessHandle.current().children()
                .filter(child -> !childrenBefore.contains(child))
                .flatMap(child -> Stream.concat(Stream.of(child), child.descendants()))
                .toList();
        assertFalse(started.isEmpty(), "the held terminal should be a child of this JVM");
        return started;
    }

    private static void assertDead(List<ProcessHandle> processes) throws Exception {
        for (ProcessHandle handle : processes) {
            handle.onExit().get(5, TimeUnit.SECONDS);
            assertFalse(handle.isAlive(), "process " + handle.pid() + " should be dead after close");
        }
    }
}
