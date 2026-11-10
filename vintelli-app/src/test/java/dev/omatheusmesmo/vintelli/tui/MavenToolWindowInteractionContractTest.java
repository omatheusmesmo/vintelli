package dev.omatheusmesmo.vintelli.tui;

import static dev.omatheusmesmo.vintelli.tui.ToolWindowInteractionAssertions.assertActivationParity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
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
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.maven.MavenToolWindow;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenToolWindowInteractionContractTest {

    private static final Size VIEWPORT = new Size(100, 20);
    private static final String PHASE_ROW = "validate";

    @TempDir
    Path project;

    @Test
    void keyboardAndPointerRunTheSameMavenPhase() throws Exception {
        ToolWindowInteractionAssertions.Observation expected = new ToolWindowInteractionAssertions.Observation(
                "maven:validate", KeyActions.MAVEN_ACTIVATE, "tool-run", 0, "validate selected");

        assertActivationParity("Maven phase activation", expected,
                this::newSession,
                MavenToolWindowInteractionContractTest::runValidateWithKeyboard,
                this::newSession,
                MavenToolWindowInteractionContractTest::runValidateWithPointer);
    }

    @Test
    void keyboardAndPointerRunTheSameScrolledPhaseOfTheRoot() throws Exception {
        ToolWindowInteractionAssertions.Observation expected = new ToolWindowInteractionAssertions.Observation(
                "maven:test", KeyActions.MAVEN_ACTIVATE, "tool-run", 0, "test selected");

        assertActivationParity("Maven scrolled phase activation", expected,
                this::newSession,
                session -> runPhaseWithKeyboard(session, "test"),
                this::newSession,
                session -> runScrolledPhaseWithPointer(session, "test"));
    }

    @Test
    void aSingleClickSelectsAndOnlyTheSecondClickRuns() throws Exception {
        try (ToolWindowInteractionAssertions.Session session = newSession()) {
            ToolWindowPilotFixture fixture = session.fixture();
            MavenContext context = session.context(MavenContext.class);
            openMaven(fixture);
            Rect area = fixture.pilot().findElement("tool-maven");
            int row = rowContaining(fixture.renderedText(), PHASE_ROW);

            fixture.pilot().click(area.x() + 2, row);
            assertEquals(List.of(), context.provider().requests(), "a single click only selects the row");
            assertEquals("validate selected", renderedSelection(fixture.renderedText()));

            fixture.pilot().click(area.x() + 2, row);
            awaitUi(context.uiWork(), () -> !context.provider().requests().isEmpty());
            assertEquals("maven:validate", describe(context.provider().requests().getFirst()));
        }
    }

    @Test
    void wheelScrollsTheTreeAndAClickOutsideTheWindowRunsNothing() throws Exception {
        try (ToolWindowInteractionAssertions.Session session = newSession()) {
            ToolWindowPilotFixture fixture = session.fixture();
            MavenContext context = session.context(MavenContext.class);
            openMaven(fixture);
            Rect area = fixture.pilot().findElement("tool-maven");
            String before = fixture.renderedText();

            fixture.scrollDown(area.x() + 2, area.y());
            fixture.scrollDown(area.x() + 2, area.y());
            String scrolled = fixture.renderedText();
            assertNotEquals(before, scrolled, "the wheel must move the visible rows");
            assertFalse(scrolled.lines().anyMatch(line -> line.contains("demo")), scrolled);

            String selectedBefore = renderedSelection(fixture.renderedText());
            fixture.pilot().click(area.x() + 2, VIEWPORT.height() + 40);
            fixture.pilot().pause(Duration.ofMillis(100));
            assertEquals(List.of(), context.provider().requests(), "a click outside the rows runs nothing");
            assertEquals(selectedBefore, renderedSelection(fixture.renderedText()),
                    "a click outside the rows keeps the selection");
        }
    }

    @Test
    void aProjectWithoutAPomShowsItsReasonAndNoRows() throws Exception {
        Path empty = project.resolve("empty");
        Files.createDirectories(empty);
        try (ToolWindowInteractionAssertions.Session session = newSession(empty)) {
            ToolWindowPilotFixture fixture = session.fixture();
            MavenContext context = session.context(MavenContext.class);
            openMaven(fixture, "No Maven project");

            fixture.press(KeyCode.DOWN);
            fixture.press(KeyCode.ENTER);
            assertEquals(List.of(), context.provider().requests());
        }
    }

    @Test
    void theWindowStaysHiddenInAFreshLayout() throws Exception {
        try (ToolWindowInteractionAssertions.Session session = newSession()) {
            ToolWindowPilotFixture fixture = session.fixture();
            VintelliShell shell = session.context(MavenContext.class).shell();

            assertFalse(shell.isToolWindowVisible("maven"), "a fresh layout starts with the right windows hidden");
            assertFalse(fixture.renderedText().contains("Loading Maven project"), fixture.renderedText());
        }
    }

    private ToolWindowInteractionAssertions.Session newSession() throws Exception {
        writeProject(project);
        return newSession(project);
    }

    private ToolWindowInteractionAssertions.Session newSession(Path projectRoot) throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        UiWorkQueue uiWork = new UiWorkQueue();
        RecordingRunProvider provider = new RecordingRunProvider();
        FakeRegistry registry = new FakeRegistry(provider);
        RunToolWindow run = new RunToolWindow(() -> themes, registry, uiWork, projectRoot.toString());
        run.refreshConfigurations();
        awaitUi(uiWork, () -> provider.listings.get() > 0 && !run.statusMessage().startsWith("Loading"));
        MavenToolWindow maven = new MavenToolWindow(() -> themes, projectRoot, steppingClock());
        maven.load();
        VintelliShell shell = new VintelliShell(ShellConfig.of(List.of(maven, run), new ShellState(), themes)
                .withCapabilityRegistry(registry));
        ToolWindowPilotFixture fixture = ToolWindowPilotFixture.open(shell, VIEWPORT);
        return new ToolWindowInteractionAssertions.Session(fixture,
                () -> observe(fixture, provider, uiWork),
                new MavenContext(shell, provider, uiWork));
    }

    private static void runValidateWithKeyboard(ToolWindowInteractionAssertions.Session session) {
        runPhaseWithKeyboard(session, "validate");
    }

    private static void runPhaseWithKeyboard(ToolWindowInteractionAssertions.Session session, String phase) {
        ToolWindowPilotFixture fixture = session.fixture();
        MavenContext context = session.context(MavenContext.class);
        openMaven(fixture);
        int steps = PHASE_ORDER.indexOf(phase) + 1;
        for (int step = 0; step < steps; step++) {
            fixture.press(KeyCode.DOWN);
        }
        fixture.press(KeyCode.ENTER);
        awaitUi(context.uiWork(), () -> !context.provider().requests().isEmpty());
    }

    private static void runValidateWithPointer(ToolWindowInteractionAssertions.Session session) {
        runScrolledPhaseWithPointer(session, "validate");
    }

    private static void runScrolledPhaseWithPointer(ToolWindowInteractionAssertions.Session session, String phase) {
        ToolWindowPilotFixture fixture = session.fixture();
        MavenContext context = session.context(MavenContext.class);
        openMaven(fixture);
        Rect area = fixture.pilot().findElement("tool-maven");
        if (!"validate".equals(phase)) {
            for (int wheel = 0; wheel < 4; wheel++) {
                fixture.scrollDown(area.x() + 2, area.y());
            }
        }
        int row = rowContaining(fixture.renderedText(), phase);
        assertTrue(row >= area.y() && row < area.bottom(), fixture.renderedText());
        fixture.pilot().click(area.x() + 2, row);
        fixture.pilot().click(area.x() + 2, row);
        awaitUi(context.uiWork(), () -> !context.provider().requests().isEmpty());
    }

    private static void openMaven(ToolWindowPilotFixture fixture) {
        openMaven(fixture, "demo");
    }

    private static void openMaven(ToolWindowPilotFixture fixture, String expectedText) {
        fixture.press('9', KeyModifiers.ALT);
        assertEquals("tool-maven", fixture.testRunner().runner().focusManager().focusedId(),
                "activating Maven transfers TamboUI focus to its tool window");
        assertTrue(awaitRendered(fixture, expectedText), fixture.renderedText());
    }

    private static boolean awaitRendered(ToolWindowPilotFixture fixture, String text) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!fixture.renderedText().contains(text) && System.nanoTime() < deadline) {
            fixture.pilot().pause(Duration.ofMillis(10));
        }
        return fixture.renderedText().contains(text);
    }

    private static ToolWindowInteractionAssertions.Observation observe(ToolWindowPilotFixture fixture,
            RecordingRunProvider provider, UiWorkQueue uiWork) {
        uiWork.drain();
        List<RunCapability.RunRequest> requests = provider.requests();
        String selected = requests.isEmpty() ? "" : describe(requests.getLast());
        String action = requests.isEmpty() ? "" : KeyActions.MAVEN_ACTIVATE;
        String rendered = fixture.renderedText();
        return new ToolWindowInteractionAssertions.Observation(selected, action,
                fixture.testRunner().runner().focusManager().focusedId(), 0, renderedSelection(rendered),
                "Pilot area=" + fixture.pilot().findElement("tool-maven") + "\nRendered buffer:\n" + rendered);
    }

    private static String describe(RunCapability.RunRequest request) {
        return String.join(" ", Stream.concat(Stream.of(request.configurationName()), request.arguments().stream())
                .toList());
    }

    private static String renderedSelection(String screen) {
        return screen.lines()
                .filter(line -> line.contains("›"))
                .map(line -> line.substring(line.indexOf('›') + 1).strip().split("\\s{2,}|[│┃|]")[0].strip())
                .filter(selected -> !selected.isEmpty())
                .findFirst()
                .map(selected -> selected + " selected")
                .orElse("");
    }

    private static int rowContaining(String screen, String token) {
        List<String> rows = screen.lines().toList();
        for (int row = 0; row < rows.size(); row++) {
            if (Arrays.asList(rows.get(row).split("[\\s│┃|›▸▾]+")).contains(token)) {
                return row;
            }
        }
        throw new AssertionError("no rendered row shows " + token + " in:\n" + screen);
    }

    /**
     * A clock that moves one millisecond per reading, so two clicks in a row are always inside the double
     * click window however slowly a runner renders the frames between them.
     */
    private static LongSupplier steppingClock() {
        AtomicLong nanos = new AtomicLong();
        return () -> nanos.addAndGet(1_000_000L);
    }

    private static void awaitUi(UiWorkQueue queue, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            queue.drain();
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the Maven interaction", interrupted);
            }
        }
        queue.drain();
        assertTrue(condition.getAsBoolean(), "the Maven interaction did not reach its expected state");
    }

    private static void writeProject(Path root) throws IOException {
        Files.createDirectories(root.resolve("alpha"));
        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>demo</artifactId>"
                + "<modules><module>alpha</module></modules></project>", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("alpha/pom.xml"), "<project><artifactId>alpha</artifactId></project>",
                StandardCharsets.UTF_8);
    }

    private static final List<String> PHASE_ORDER = List.of("clean", "validate", "initialize",
            "generate-sources", "process-sources", "generate-resources", "process-resources", "compile",
            "process-classes", "generate-test-sources", "process-test-sources", "generate-test-resources",
            "process-test-resources", "test-compile", "process-test-classes", "test");

    private record MavenContext(VintelliShell shell, RecordingRunProvider provider, UiWorkQueue uiWork) {
    }

    private static final class RecordingRunProvider implements RunCapability {
        private final List<RunRequest> requests = new CopyOnWriteArrayList<>();
        private final AtomicInteger listings = new AtomicInteger();

        List<RunRequest> requests() {
            return List.copyOf(requests);
        }

        @Override public String identifier() { return "local"; }
        @Override public String provenance() { return "test runner"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }

        @Override
        public CapabilityResult<List<RunConfiguration>> listConfigurations() {
            listings.incrementAndGet();
            return CapabilityResult.ok(List.of());
        }

        @Override
        public CapabilityResult<RunHandle> start(RunRequest request, Consumer<String> output) {
            requests.add(request);
            output.accept("Contract run output");
            return CapabilityResult.ok(() -> { });
        }
    }

    private static final class FakeRegistry implements CapabilityRegistry {
        private final RunCapability provider;

        private FakeRegistry(RunCapability provider) {
            this.provider = provider;
        }

        @Override public CapabilitySelection<RunCapability> run() {
            return CapabilitySelection.selected(CapabilityType.RUN, provider);
        }
        @Override public CapabilitySelection<TerminalCapability> terminal() { return unavailable(CapabilityType.TERMINAL); }
        @Override public CapabilitySelection<ReadFileCapability> readFile() { return unavailable(CapabilityType.READ_FILE); }
        @Override public CapabilitySelection<DiagnosticsCapability> diagnostics() { return unavailable(CapabilityType.DIAGNOSTICS); }
        @Override public CapabilitySelection<CallHierarchyCapability> callHierarchy() { return unavailable(CapabilityType.CALL_HIERARCHY); }
        @Override public CapabilitySelection<CodeActionCapability> codeActions() { return unavailable(CapabilityType.CODE_ACTION); }
        @Override public CapabilitySelection<EditorBridge> editorBridge() { return unavailable(CapabilityType.EDITOR_BRIDGE); }
        @Override public CapabilitySelection<VersionControlCapability> versionControl() { return unavailable(CapabilityType.VERSION_CONTROL); }
        @Override public List<CapabilityStatus> snapshot() {
            return List.of(CapabilityStatus.available(CapabilityType.RUN, provider.identifier(),
                    provider.provenance(), List.of()));
        }

        private static <T> CapabilitySelection<T> unavailable(CapabilityType type) {
            return CapabilitySelection.unavailable(type, "not configured");
        }
    }
}
