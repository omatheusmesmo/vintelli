package dev.omatheusmesmo.vintelli.core.acp;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    @Test
    void clampsTheAgentSuppliedOutputLimitToTheServerMaximum() {
        assertEquals(AcpTerminalRegistry.MAX_OUTPUT_LIMIT, AcpTerminalRegistry.clampOutputLimit(Long.MAX_VALUE));
        assertEquals(AcpTerminalRegistry.DEFAULT_OUTPUT_LIMIT, AcpTerminalRegistry.clampOutputLimit(null));
        assertEquals(0L, AcpTerminalRegistry.clampOutputLimit(-5L));
        assertEquals(4L, AcpTerminalRegistry.clampOutputLimit(4L));
    }

    @Test
    void countsOutputInBytesAndTruncates() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        AcpTerminalRegistry registry = new AcpTerminalRegistry(root);

        String java = ProcessHandle.current().info().command().orElseThrow();
        AcpSchema.CreateTerminalRequest request = new AcpSchema.CreateTerminalRequest(
                "session-1", java, List.of("-version"), null, List.of(), 4L);
        String terminalId = registry.create(request).terminalId();

        registry.waitForExit(terminalId);
        AcpSchema.TerminalOutputResponse output = registry.output(terminalId);
        assertTrue(output.truncated());
        assertTrue(output.output().getBytes(StandardCharsets.UTF_8).length <= 4);
        registry.release(terminalId);
    }

    @Test
    void doesNotInheritSentinelEnvironmentVariables() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Map<String, String> base = new HashMap<>(System.getenv());
        base.put("VINTelli_SENTINEL", "secret-value");
        AcpTerminalRegistry registry = new AcpTerminalRegistry(root, base);

        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String command = windows ? "cmd" : "sh";
        List<String> args = windows
                ? List.of("/c", "echo ran & echo %VINTelli_SENTINEL%")
                : List.of("-c", "echo ran; printenv VINTelli_SENTINEL || true");
        AcpSchema.CreateTerminalRequest request = new AcpSchema.CreateTerminalRequest(
                "session-1", command, args, null, List.of(), null);
        String terminalId = registry.create(request).terminalId();

        registry.waitForExit(terminalId);
        AcpSchema.TerminalOutputResponse output = registry.output(terminalId);
        assertTrue(output.output().contains("ran"), output.output());
        assertFalse(output.output().contains("secret-value"), output.output());
        registry.release(terminalId);
    }

    @Test
    void releasesAndRemovesTheTerminalOnRelease() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        AcpTerminalRegistry registry = new AcpTerminalRegistry(root);

        String java = ProcessHandle.current().info().command().orElseThrow();
        String terminalId = registry.create(new AcpSchema.CreateTerminalRequest(
                "session-1", java, List.of("-version"), null, List.of(), null)).terminalId();
        registry.release(terminalId);

        assertThrows(IllegalArgumentException.class, () -> registry.output(terminalId));
    }

    @Test
    void killTerminatesTheProcessTreeIncludingGrandchildren() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        AcpTerminalRegistry registry = new AcpTerminalRegistry(root);

        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String command;
        List<String> args;
        if (windows) {
            command = "cmd";
            args = List.of("/c", "start /b /wait cmd /c \"ping -n 61 127.0.0.1 > nul\"");
        } else {
            command = "sh";
            args = List.of("-c", "sh -c 'sleep 60' & wait");
        }
        AcpSchema.CreateTerminalRequest request = new AcpSchema.CreateTerminalRequest(
                "session-1", command, args, null, List.of(), null);
        String terminalId = registry.create(request).terminalId();
        Process process = registry.process(terminalId);

        List<ProcessHandle> captured = List.of();
        long discoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (captured.isEmpty() && System.nanoTime() < discoveryDeadline) {
            captured = process.descendants().toList();
            if (captured.isEmpty()) {
                process.waitFor(10, TimeUnit.MILLISECONDS);
            }
        }
        assertFalse(captured.isEmpty(), "expected a spawned grandchild");

        registry.kill(terminalId);

        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "terminal process should exit");
        for (ProcessHandle descendant : captured) {
            try {
                descendant.onExit().get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            assertFalse(descendant.isAlive(), "descendant should be dead");
        }
        assertFalse(process.isAlive(), "terminal process should be dead");
        registry.release(terminalId);
    }

    @Test
    void releaseAllKillsEveryTerminalAndRemovesThem() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        AcpTerminalRegistry registry = new AcpTerminalRegistry(root);

        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String command;
        List<String> args;
        if (windows) {
            command = "cmd";
            args = List.of("/c", "ping -n 61 127.0.0.1 > nul");
        } else {
            command = "sh";
            args = List.of("-c", "sleep 60");
        }
        String first = registry.create(new AcpSchema.CreateTerminalRequest(
                "session-1", command, args, null, List.of(), null)).terminalId();
        String second = registry.create(new AcpSchema.CreateTerminalRequest(
                "session-1", command, args, null, List.of(), null)).terminalId();

        Process firstProcess = registry.process(first);
        Process secondProcess = registry.process(second);

        assertTrue(firstProcess.isAlive(), "first process should be alive before releaseAll");
        assertTrue(secondProcess.isAlive(), "second process should be alive before releaseAll");

        registry.releaseAll();

        assertTrue(firstProcess.waitFor(5, TimeUnit.SECONDS), "first process should exit");
        assertTrue(secondProcess.waitFor(5, TimeUnit.SECONDS), "second process should exit");
        assertFalse(firstProcess.isAlive());
        assertFalse(secondProcess.isAlive());
        assertThrows(IllegalArgumentException.class, () -> registry.output(first));
        assertThrows(IllegalArgumentException.class, () -> registry.output(second));
    }

    @Test
    void preservesAMultiByteCharacterSplitAcrossReads() {
        String text = "ab\u20ac\uD83D\uDE00cd";
        AcpTerminalRegistry.Terminal terminal = new AcpTerminalRegistry.Terminal(null, 1024);

        for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
            terminal.appendBytes(new byte[] {value}, 0, 1);
        }

        assertEquals(text, terminal.output());
        assertFalse(terminal.truncated());
    }

    @Test
    void cutInsideAThreeByteCharacterKeepsOnlyTheCompleteCharactersBeforeTheLimit() {
        byte[] data = "ab\u20ac".getBytes(StandardCharsets.UTF_8);

        assertCutOutput("ab", 3, data);
        assertCutOutput("ab", 4, data);
        assertCutOutput("ab\u20ac", 5, data);
    }

    @Test
    void cutInsideAFourByteCharacterKeepsOnlyTheCompleteCharactersBeforeTheLimit() {
        byte[] data = "ab\uD83D\uDE00".getBytes(StandardCharsets.UTF_8);

        assertCutOutput("ab", 3, data);
        assertCutOutput("ab", 4, data);
        assertCutOutput("ab", 5, data);
        assertCutOutput("ab\uD83D\uDE00", 6, data);
    }

    @Test
    void cutInsideACharacterSplitAcrossReadsDropsThePartialCharacter() {
        byte[] data = "ab\u20ac".getBytes(StandardCharsets.UTF_8);
        AcpTerminalRegistry.Terminal terminal = new AcpTerminalRegistry.Terminal(null, 4);

        for (byte value : data) {
            terminal.appendBytes(new byte[] {value}, 0, 1);
        }

        assertEquals("ab", terminal.output());
        assertTrue(terminal.truncated());
    }

    private static void assertCutOutput(String expected, long limit, byte[] data) {
        AcpTerminalRegistry.Terminal terminal = new AcpTerminalRegistry.Terminal(null, limit);
        terminal.appendBytes(data, 0, data.length);

        String output = terminal.output();
        assertEquals(expected, output, "limit " + limit);
        assertFalse(output.contains("\uFFFD"), "limit " + limit);
        assertTrue(output.getBytes(StandardCharsets.UTF_8).length <= limit, "limit " + limit);
    }
}
