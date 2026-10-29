package dev.omatheusmesmo.vintelli.lsp;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generic client drives a real language server process (the fake server of
 * this module) through the regular profile launch: session lifecycle, document
 * sync, the pushed diagnostics, the feature requests, the configuration only
 * selection of a second profile and the failure of a missing binary.
 */
class LspClientTest {

    private static final String PROFILE_MARKER = "unit-marker";
    private static final String SECOND_MARKER = "second-marker";

    @TempDir
    Path workspace;

    private Path trace(String name) throws IOException {
        Path trace = workspace.resolve(name + ".trace");
        Files.writeString(trace, "");
        return trace;
    }

    @Test
    void sessionServesHoverDefinitionReferencesAndCompletion() throws Exception {
        Path traceFile = trace("features");
        try (LspClient client = new LspClient(config("unit", fakeCommand(traceFile, PROFILE_MARKER),
                Optional.empty()))) {

            assertEquals(ProviderHealth.available(), client.health(), client.health().reason());
            assertEquals("unit", client.activeProfileName().orElseThrow());

            CapabilityResult<String> hover = client.hover(document(), 0, 0);
            assertFalse(hover.isFailure(), hover.toString());
            assertEquals("# fake hover " + PROFILE_MARKER, ok(hover));

            CapabilityResult<List<CodeReference>> definitions = client.definition(document(), 0, 0);
            assertFalse(definitions.isFailure(), definitions.toString());
            assertEquals(1, ok(definitions).size());
            assertEquals(document().toAbsolutePath().toString(), ok(definitions).get(0).targetFile());
            assertEquals(0, ok(definitions).get(0).startLine());

            CapabilityResult<List<CodeReference>> references = client.references(document(), 0, 0, false);
            assertFalse(references.isFailure(), references.toString());
            assertEquals(2, ok(references).size());
            assertEquals(Path.of("/fake/other.java").toString(), ok(references).get(1).targetFile());

            CapabilityResult<List<CompletionEntry>> completions = client.completion(document(), 0, 0);
            assertFalse(completions.isFailure(), completions.toString());
            assertEquals(List.of("fake_completion_" + PROFILE_MARKER, "fake_second"), ok(completions).stream()
                    .map(CompletionEntry::label)
                    .toList());

            assertTraceContains(traceFile, "initialize", "initialized", "hover", "definition", "references",
                    "completion");
        }
        assertTraceContains(traceFile, "shutdown", "exit");
    }

    @Test
    void diagnosticsRoundTripReceivesThePushedProblemsAndUpdatesOnAChange() throws Exception {
        Path traceFile = trace("diagnostics");
        Files.writeString(document(), "class Fake {}");
        try (LspClient client = new LspClient(config("unit", fakeCommand(traceFile, PROFILE_MARKER),
                Optional.empty()))) {

            CapabilityResult<List<DiagnosticsCapability.Diagnostic>> opened = client.diagnostics(document());
            assertFalse(opened.isFailure(), opened.toString());
            DiagnosticsCapability.Diagnostic first = ok(opened).get(0);
            assertEquals(0, first.startLine());
            assertEquals(0, first.endLine());
            assertEquals(5, first.endColumn());
            assertEquals(DiagnosticsCapability.Severity.ERROR, first.severity());
            assertEquals("fake", first.source());
            assertTrue(first.message().contains("Fake.java"), first.message());

            CapabilityResult<List<DiagnosticsCapability.Diagnostic>> changed =
                    client.modifyDocument(document(), "class Fake2 {}");
            assertFalse(changed.isFailure(), changed.toString());
            assertEquals(DiagnosticsCapability.Severity.WARNING, ok(changed).get(0).severity());
            assertEquals("fake problem changed", ok(changed).get(0).message());

            assertTraceContains(traceFile, "initialize", "didOpen", "didChange");
        }
        assertTraceContains(traceFile, "shutdown", "exit");
    }

    @Test
    void launcherThreadsAreDaemonAndTheSessionEndsWithoutLingering() throws Exception {
        Path traceFile = trace("linger");
        try (LspClient client = new LspClient(config("unit", fakeCommand(traceFile, PROFILE_MARKER),
                Optional.empty()))) {

            assertFalse(client.hover(document(), 0, 0).isFailure(), "the session must answer");
            List<Thread> launcherThreads = threadsNamedPrefix("vintelli-lsp-");
            assertFalse(launcherThreads.isEmpty(), "the launcher must use its named executor threads");
            assertTrue(launcherThreads.stream().allMatch(Thread::isDaemon));
        }

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !threadsNamedPrefix("vintelli-lsp-").isEmpty()) {
            Thread.sleep(50);
        }
        assertTrue(threadsNamedPrefix("vintelli-lsp-").isEmpty(), "the launcher executor threads must die at close");
        assertTraceContains(traceFile, "exit");
    }

    @Test
    void closeKillsAServerThatIgnoresExitAndItsDescendants() throws Exception {
        Path traceFile = trace("ignore-exit");
        LspClient client = new LspClient(serverOutsideWorkspace("unit",
                fakeCommand(traceFile, PROFILE_MARKER, "--ignore-exit", "--spawn-child")));
        assertFalse(client.hover(document(), 0, 0).isFailure(), "the session must answer");
        List<Long> processes = recordedProcesses(traceFile);
        assertEquals(2, processes.size(), Files.readString(traceFile));

        client.close();

        assertTraceContains(traceFile, "exit");
        for (long pid : processes) {
            assertTrue(awaitGone(pid), "the process " + pid + " survived close");
        }
    }

    @Test
    void aFailedStartKillsTheHalfStartedServerAndItsDescendants() throws Exception {
        Path traceFile = trace("failed-start");
        try (LspClient client = new LspClient(serverOutsideWorkspace("unit",
                fakeCommand(traceFile, PROFILE_MARKER, "--spawn-child", "--fail-initialize")))) {

            CapabilityResult<String> hover = client.hover(document(), 0, 0);
            assertTrue(hover.isFailure(), hover.toString());
            List<Long> processes = recordedProcesses(traceFile);
            assertEquals(2, processes.size(), Files.readString(traceFile));

            for (long pid : processes) {
                assertTrue(awaitGone(pid), "the process " + pid + " survived the failed start");
            }
        }
    }

    @Test
    void closeDocumentWithoutALiveSessionDoesNotStartTheServer() throws Exception {
        Path traceFile = trace("close-idle");
        try (LspClient client = new LspClient(config("unit", fakeCommand(traceFile, PROFILE_MARKER),
                Optional.empty()))) {

            client.closeDocument(document());

            assertEquals("", Files.readString(traceFile));
        }
    }

    @Test
    void missingBinaryIsUnavailableWithAClearMessage() throws Exception {
        trace("missing");
        LspProfileConfig.Profile profile = new StaticProfileConfig.StaticProfile(
                List.of("vintelli-missing-language-server"),
                Optional.of(workspace.toString()), Optional.empty());
        LspProfileConfig config = new StaticProfileConfig.StaticConfig(Optional.empty(), Map.of("unit", profile));
        try (LspClient client = new LspClient(config)) {

            ProviderHealth health = client.health();
            assertFalse(health.isAvailable());
            assertTrue(health.reason().contains("vintelli-missing-language-server"), health.reason());

            CapabilityResult<List<DiagnosticsCapability.Diagnostic>> problems = client.diagnostics(document());
            assertTrue(problems.isFailure(), problems.toString());
            assertTrue(problems.failureOf().orElseThrow().detail().contains("vintelli-missing-language-server"),
                    problems.toString());
        }
    }

    @Test
    void severalProfilesNeedAnExplicitSelection() throws Exception {
        Path ignoredTrace = trace("ambiguous");
        List<String> command = fakeCommand(ignoredTrace, PROFILE_MARKER);
        LspProfileConfig config = new StaticProfileConfig.StaticConfig(Optional.empty(), Map.of(
                "first", new StaticProfileConfig.StaticProfile(command, Optional.of(workspace.toString()),
                        Optional.empty()),
                "second", new StaticProfileConfig.StaticProfile(command, Optional.of(workspace.toString()),
                        Optional.empty())));
        try (LspClient client = new LspClient(config)) {

            ProviderHealth health = client.health();
            assertFalse(health.isAvailable());
            assertTrue(health.reason().contains("vintelli.lsp.profile"), health.reason());
            assertTrue(client.activeProfileName().isEmpty());
        }
    }

    @Test
    void secondProfileIsAddedAndSelectedByConfigurationOnly() throws Exception {
        Path firstTrace = trace("profile-first");
        Path secondTrace = trace("profile-second");
        LspProfileConfig config = new StaticProfileConfig.StaticConfig(Optional.of("unit-second"), Map.of(
                "unit-first", new StaticProfileConfig.StaticProfile(fakeCommand(firstTrace, PROFILE_MARKER),
                        Optional.of(workspace.toString()), Optional.empty()),
                "unit-second", new StaticProfileConfig.StaticProfile(fakeCommand(secondTrace, SECOND_MARKER),
                        Optional.of(workspace.toString()), Optional.empty())));
        try (LspClient client = new LspClient(config)) {

            assertEquals("unit-second", client.activeProfileName().orElseThrow());
            assertEquals(ProviderHealth.available(), client.health(), client.health().reason());

            CapabilityResult<String> hover = client.hover(document(), 0, 0);
            assertFalse(hover.isFailure(), hover.toString());
            assertEquals("# fake hover " + SECOND_MARKER, ok(hover));
        }
    }

    @Test
    void initializationOptionsAndCallHierarchyAnswerOnTheWire() throws Exception {
        Path traceFile = trace("options");
        LspProfileConfig config = new StaticProfileConfig.StaticConfig(Optional.empty(),
                Map.of("unit", new StaticProfileConfig.StaticProfile(fakeCommand(traceFile, PROFILE_MARKER),
                        Optional.of(workspace.toString()), Optional.of("{\"note\": \"unit-options\"}"))));
        try (LspClient client = new LspClient(config)) {

            CapabilityResult<List<CallHierarchyCapability.CallSite>> calls =
                    client.callers(new CallHierarchyCapability.SourcePosition(document(), 0, 0));
            assertFalse(calls.isFailure(), calls.toString());
            assertEquals(1, ok(calls).size());
            assertEquals("fakeCaller", ok(calls).get(0).symbol());
            assertEquals("caller.java", ok(calls).get(0).position().filePath().getFileName().toString());

            CapabilityResult<List<CallHierarchyCapability.CallSite>> callees =
                    client.callees(new CallHierarchyCapability.SourcePosition(document(), 0, 0));
            assertFalse(callees.isFailure(), callees.toString());
            assertEquals("fakeCallee", ok(callees).get(0).symbol());

            String traceText = Files.readString(traceFile);
            assertTrue(traceText.contains("initializationOptions"), traceText);
            assertTrue(traceText.contains("unit-options"), traceText);
            assertTraceContains(traceFile, "callHierarchy/prepare", "callHierarchy/incomingCalls",
                    "callHierarchy/outgoingCalls");
        }
        assertTraceContains(traceFile, "shutdown", "exit");
    }

    private Path document() {
        return workspace.resolve("Fake.java");
    }

    private static <V> V ok(CapabilityResult<V> result) {
        return ((CapabilityResult.Ok<V>) result).value();
    }

    /**
     * A command that launches the fake server of this module as a plain java
     * main, through the classpath entries the fixture classes actually sit in:
     * the same path a real profile would resolve.
     */
    private List<String> fakeCommand(Path traceFile, String marker, String... flags) throws IOException {
        try {
            List<String> command = new ArrayList<>();
            command.add(javaExecutable());
            command.add("-cp");
            command.add(FakeLspServer.classpath());
            command.add(FakeLspServer.class.getName());
            command.add("--trace");
            command.add(traceFile.toAbsolutePath().toString());
            command.add("--marker");
            command.add(marker);
            command.addAll(List.of(flags));
            return command;
        } catch (URISyntaxException failure) {
            throw new IOException("cannot locate the fake server classpath", failure);
        }
    }

    private static String javaExecutable() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    /**
     * A profile without a workspace root starts the server in the working
     * directory of the test JVM: a server that is killed while it still has the
     * temporary workspace as its working directory keeps that directory locked
     * on Windows, which makes the temporary directory impossible to delete.
     */
    private static LspProfileConfig serverOutsideWorkspace(String name, List<String> command) {
        LspProfileConfig.Profile profile = new StaticProfileConfig.StaticProfile(command, Optional.empty(),
                Optional.empty());
        return new StaticProfileConfig.StaticConfig(Optional.empty(), Map.of(name, profile));
    }

    private LspProfileConfig config(String name, List<String> command, Optional<String> initializationOptions)
            throws IOException {
        LspProfileConfig.Profile profile = new StaticProfileConfig.StaticProfile(command,
                Optional.of(workspace.toString()), initializationOptions);
        return new StaticProfileConfig.StaticConfig(Optional.empty(), Map.of(name, profile));
    }

    private static List<Thread> threadsNamedPrefix(String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith(prefix))
                .toList();
    }

    private static void assertTraceContains(Path traceFile, String... methods) throws IOException {
        String text = Files.readString(traceFile);
        for (String method : methods) {
            assertTrue(text.contains(method + "\n"), "the trace misses " + method + ": " + text);
        }
    }

    /**
     * @return the pids the fake server recorded in its trace: the server itself
     *         and the holding child it spawned
     */
    private static List<Long> recordedProcesses(Path traceFile) throws IOException {
        return Files.readAllLines(traceFile).stream()
                .filter(line -> line.startsWith("pid ") || line.startsWith("child "))
                .map(line -> Long.parseLong(line.substring(line.indexOf(' ') + 1)))
                .toList();
    }

    private static boolean awaitGone(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(50);
        }
        return true;
    }
}
