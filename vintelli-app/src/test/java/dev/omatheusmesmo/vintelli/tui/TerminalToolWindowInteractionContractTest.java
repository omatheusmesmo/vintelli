package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.TerminalSession;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.api.statusbar.CapabilityStatusWidgets;
import dev.omatheusmesmo.vintelli.api.terminal.DangerousCommands;
import dev.tamboui.layout.Size;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;

class TerminalToolWindowInteractionContractTest {

    private static final Size VIEWPORT = new Size(90, 24);
    private static final Duration AWAIT = Duration.ofSeconds(3);
    private static final String PROMPT = "vintelli> ";
    private static final String CURSOR = "▏";

    @Test
    void ctrlLClearsEarlierOutputAndKeepsThePromptAndTypedInput() throws Exception {
        FakeSession session = new FakeSession();
        try (ToolWindowPilotFixture fixture = openStartedWindow(session)) {
            session.emit("earlier output");
            awaitShows(fixture, "earlier output");
            typeText(fixture, "draft");
            awaitShows(fixture, PROMPT + "draft" + CURSOR);

            fixture.press('l', KeyModifiers.CTRL);

            assertTrue(fixture.await(() -> !fixture.renderedText().contains("earlier output")
                    && fixture.renderedText().contains(PROMPT + "draft" + CURSOR), AWAIT),
                    () -> fixture.renderedText());
            assertEquals(1, session.clears());
            assertTrue(session.written().isEmpty(), () -> "Ctrl+L must not reach the shell: " + session.written());

            session.emit("fresh output");
            awaitShows(fixture, "fresh output");
            String screen = fixture.renderedText();
            assertTrue(screen.indexOf("fresh output") < screen.indexOf(PROMPT), () -> screen);
        }
    }

    @Test
    void typingClearOnItsOwnClearsTheOutputWithoutSendingItToTheShell() throws Exception {
        FakeSession session = new FakeSession();
        try (ToolWindowPilotFixture fixture = openStartedWindow(session)) {
            session.emit("earlier output");
            awaitShows(fixture, "earlier output");
            typeText(fixture, "clear");
            fixture.press(KeyCode.ENTER);

            assertTrue(fixture.await(() -> !fixture.renderedText().contains("earlier output")
                    && fixture.renderedText().contains(PROMPT + CURSOR), AWAIT), () -> fixture.renderedText());
            assertEquals(1, session.clears());
            assertTrue(session.written().isEmpty(), () -> "clear must not reach the shell: " + session.written());
        }
    }

    @Test
    void clearWithArgumentsIsNotTreatedAsClearAndReachesTheShell() throws Exception {
        FakeSession session = new FakeSession();
        try (ToolWindowPilotFixture fixture = openStartedWindow(session)) {
            session.emit("earlier output");
            awaitShows(fixture, "earlier output");
            typeText(fixture, "clear something");
            fixture.press(KeyCode.ENTER);

            assertTrue(fixture.await(() -> session.written().contains("clear something\r"), AWAIT),
                    () -> "written: " + session.written());
            assertEquals(0, session.clears());
            assertTrue(fixture.renderedText().contains("earlier output"), () -> fixture.renderedText());
        }
    }

    @Test
    void dangerousCommandStillAsksForConfirmationBeforeItReachesTheShell() throws Exception {
        FakeSession session = new FakeSession();
        try (ToolWindowPilotFixture fixture = openStartedWindow(session)) {
            typeText(fixture, "rm -rf build");
            fixture.press(KeyCode.ENTER);

            assertTrue(fixture.await(() -> fixture.renderedText().contains("needs confirmation"), AWAIT),
                    () -> fixture.renderedText());
            assertTrue(session.written().isEmpty(), () -> "nothing may be sent before confirmation: " + session.written());

            typeText(fixture, "y");
            fixture.press(KeyCode.ENTER);

            assertTrue(fixture.await(() -> session.written().contains("rm -rf build\r"), AWAIT),
                    () -> "written: " + session.written());
        }
    }

    private static ToolWindowPilotFixture openStartedWindow(FakeSession session) throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeRegistry registry = new FakeRegistry(new FakeTerminal(session));
        TerminalToolWindow window = new TerminalToolWindow(() -> themes, registry);
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState(), themes, registry);
        shell.useUiWorkQueue(uiWork);
        ToolWindowPilotFixture fixture = ToolWindowPilotFixture.open(shell, VIEWPORT);
        try {
            fixture.press('2', KeyModifiers.ALT);
            fixture.press(KeyCode.ENTER);
            assertTrue(fixture.await(session::started, AWAIT), () -> fixture.renderedText());
            return fixture;
        } catch (AssertionError | RuntimeException failure) {
            fixture.close();
            throw failure;
        }
    }

    private static void typeText(ToolWindowPilotFixture fixture, String text) {
        fixture.pilot().press(text.split(""));
        fixture.pilot().pause(Duration.ofMillis(200));
    }

    private static void awaitShows(ToolWindowPilotFixture fixture, String text) {
        assertTrue(fixture.await(() -> fixture.renderedText().contains(text), AWAIT),
                () -> "Expected the terminal to show " + text + " but it showed:\n" + fixture.renderedText());
    }

    private static final class FakeSession implements TerminalSession {
        private final List<String> lines = new CopyOnWriteArrayList<>();
        private final List<String> written = new CopyOnWriteArrayList<>();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicInteger clears = new AtomicInteger();

        void emit(String line) {
            lines.add(line);
        }

        void markStarted() {
            started.set(true);
        }

        boolean started() {
            return started.get();
        }

        int clears() {
            return clears.get();
        }

        List<String> written() {
            return List.copyOf(written);
        }

        @Override
        public void write(byte[] input) {
            written.add(new String(input, StandardCharsets.UTF_8));
        }

        @Override
        public List<String> output() {
            return List.copyOf(lines);
        }

        @Override
        public void clearOutput() {
            lines.clear();
            clears.incrementAndGet();
        }

        @Override
        public void resize(int columns, int rows) {
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public void close() {
        }
    }

    private record FakeTerminal(FakeSession session) implements TerminalCapability {

        @Override public String identifier() { return "pty"; }
        @Override public String provenance() { return "local shell"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }

        @Override
        public CapabilityResult<TerminalSession> startSession(TerminalSessionRequest request) {
            session.markStarted();
            return CapabilityResult.ok(session);
        }

        @Override
        public boolean requiresSessionCommandConfirmation(String commandLine) {
            List<String> tokens = List.of(commandLine.strip().split("\\s+"));
            return DangerousCommands.requiresConfirmation(tokens.getFirst(), tokens.subList(1, tokens.size()));
        }

        @Override
        public CapabilityResult<TerminalResult> execute(TerminalRequest request) {
            return CapabilityResult.failed(new CapabilityFailure("not-used", "not used in this test"));
        }
    }

    private static final class FakeRegistry implements CapabilityRegistry {
        private final TerminalCapability terminal;

        private FakeRegistry(TerminalCapability terminal) {
            this.terminal = terminal;
        }

        @Override public CapabilitySelection<TerminalCapability> terminal() {
            return CapabilitySelection.selected(CapabilityType.TERMINAL, terminal);
        }
        @Override public CapabilitySelection<RunCapability> run() { return unavailable(CapabilityType.RUN); }
        @Override public CapabilitySelection<ReadFileCapability> readFile() { return unavailable(CapabilityType.READ_FILE); }
        @Override public CapabilitySelection<DiagnosticsCapability> diagnostics() { return unavailable(CapabilityType.DIAGNOSTICS); }
        @Override public CapabilitySelection<CallHierarchyCapability> callHierarchy() { return unavailable(CapabilityType.CALL_HIERARCHY); }
        @Override public CapabilitySelection<CodeActionCapability> codeActions() { return unavailable(CapabilityType.CODE_ACTION); }
        @Override public CapabilitySelection<EditorBridge> editorBridge() { return unavailable(CapabilityType.EDITOR_BRIDGE); }
        @Override public CapabilitySelection<VersionControlCapability> versionControl() { return unavailable(CapabilityType.VERSION_CONTROL); }

        @Override
        public List<CapabilityStatus> snapshot() {
            return List.of(CapabilityStatus.available(CapabilityType.TERMINAL, terminal.identifier(),
                    terminal.provenance(), List.of()));
        }

        @Override
        public List<CapabilityStatusWidgets> statusBarSnapshot() {
            return List.of();
        }

        private static <T> CapabilitySelection<T> unavailable(CapabilityType type) {
            return CapabilitySelection.unavailable(type, "not configured");
        }
    }
}
