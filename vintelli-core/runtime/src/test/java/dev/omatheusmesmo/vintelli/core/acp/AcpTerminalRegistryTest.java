package dev.omatheusmesmo.vintelli.core.acp;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcpTerminalRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void runsTheCommandInTheRootAndKeepsItsExitStatusAndOutput() throws Exception {
        AcpTerminalRegistry registry = new AcpTerminalRegistry(Files.createDirectories(tempDir.resolve("root")));

        String terminalId = registry.create(javaVersionRequest(null)).terminalId();

        AcpSchema.WaitForTerminalExitResponse exit = registry.waitForExit(terminalId);
        AcpSchema.TerminalOutputResponse output = registry.output(terminalId);
        assertEquals(0, exit.exitCode());
        assertTrue(output.output().contains("version"), output.output());
        assertFalse(output.truncated());
        assertEquals(0, output.exitStatus().exitCode());
        registry.release(terminalId);
    }

    @Test
    void refusesAWorkingDirectoryOutsideTheRoot() throws Exception {
        AcpTerminalRegistry registry = new AcpTerminalRegistry(Files.createDirectories(tempDir.resolve("root")));

        assertThrows(AcpAccessDeniedException.class, () -> registry.create(javaVersionRequest("..")));
    }

    @Test
    void rejectsAnUnknownTerminalId() {
        AcpTerminalRegistry registry = new AcpTerminalRegistry(tempDir);

        assertThrows(IllegalArgumentException.class, () -> registry.output("missing"));
    }

    private static AcpSchema.CreateTerminalRequest javaVersionRequest(String cwd) {
        String java = ProcessHandle.current().info().command().orElseThrow();
        return new AcpSchema.CreateTerminalRequest("session-1", java, List.of("-version"), cwd, List.of(), null);
    }
}
