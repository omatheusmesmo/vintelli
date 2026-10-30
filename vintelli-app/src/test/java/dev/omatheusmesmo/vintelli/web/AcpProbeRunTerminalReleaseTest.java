package dev.omatheusmesmo.vintelli.web;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcpProbeRunTerminalReleaseTest {

    @Test
    void closeReleasesEveryTerminalOpenedDuringTheRun() throws Exception {
        AcpProbeRun run = AcpProbeRun.create();
        String java = ProcessHandle.current().info().command().orElseThrow();
        String terminalId = run.terminals().create(new AcpSchema.CreateTerminalRequest(
                "session-1", java, List.of("-version"), null, List.of(), null)).terminalId();
        assertTrue(run.terminals().size() > 0);

        run.close();

        assertEquals(0, run.terminals().size());
        assertEquals(1, run.releasedTerminalCount());
    }

    @Test
    void closeKillsTheProcessTreeOfATerminalStillRunningAtTheEndOfTheRun() throws Exception {
        AcpProbeRun run = AcpProbeRun.create();
        Set<ProcessHandle> childrenBefore = ProcessHandle.current().children().collect(Collectors.toSet());
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        AcpSchema.CreateTerminalRequest request = windows
                ? new AcpSchema.CreateTerminalRequest("session-1", "cmd", List.of("/c", "ping -n 61 127.0.0.1 > nul"), null, List.of(), null)
                : new AcpSchema.CreateTerminalRequest("session-1", "sh", List.of("-c", "sleep 60"), null, List.of(), null);
        run.terminals().create(request);

        List<ProcessHandle> started = ProcessHandle.current().children()
                .filter(child -> !childrenBefore.contains(child))
                .flatMap(child -> Stream.concat(Stream.of(child), child.descendants()))
                .toList();
        assertFalse(started.isEmpty(), "the long-running terminal should be a child of this JVM");
        assertTrue(started.stream().allMatch(ProcessHandle::isAlive), "the terminal should be running before close");

        run.close();

        for (ProcessHandle handle : started) {
            handle.onExit().get(5, TimeUnit.SECONDS);
            assertFalse(handle.isAlive(), "process " + handle.pid() + " should be dead after close");
        }
        assertEquals(0, run.terminals().size());
    }
}
