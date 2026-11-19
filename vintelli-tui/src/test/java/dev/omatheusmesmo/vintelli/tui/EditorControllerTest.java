package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Severity;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.Location;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.icons.FileIcons;
import dev.omatheusmesmo.vintelli.tui.icons.FileIconsFixture;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.navigation.NavigationTarget;
import dev.omatheusmesmo.vintelli.tui.navigation.UsageSearch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorControllerTest {

    @TempDir
    Path tempDir;

    private Path project;
    private BuiltinEditor editor;
    private FileIcons icons;
    private EditorController controller;
    private final List<String> statuses = new ArrayList<>();
    private final List<String> focusTargets = new ArrayList<>();
    private int vcsRefreshes;
    private int diagnosticsRefreshes;
    private final Map<Path, String> readable = new HashMap<>();
    private FakeNavigation navigation;
    private boolean findWindowShown;
    private final List<UsageSearch> usageSearches = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        project = Files.createDirectories(tempDir.resolve("project")).toRealPath();
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/Main.java"), "class Main {}\n");
        Files.writeString(project.resolve("src/Util.java"), "class Util {}\n");
        Files.writeString(tempDir.resolve("outside.txt"), "outside\n");
        editor = new BuiltinEditor(project, null, () -> null);
        ProjectTreeController projects = new ProjectTreeController(project, new ShellState(), null, null,
                statuses::add, () -> { }, () -> { });
        icons = FileIconsFixture.resolved(IconMode.ASCII, tempDir);
        controller = new EditorController(project, editor, projects, host(), icons);
    }

    @Test
    void breadcrumbSegmentsRunFromTheProjectRootDownToTheOpenFile() {
        controller.openFile(project.resolve("src/Main.java"), 0);
        controller.refreshNavigation();

        List<StatusBar.Breadcrumb> segments = controller.navigationSegments();
        assertEquals(List.of(project.getFileName().toString(), "src", "Main.java"),
                segments.stream().map(StatusBar.Breadcrumb::label).toList());
        assertEquals(project.resolve("src/Main.java"), segments.getLast().path());
        assertEquals(icons.iconFor("Main.java"), segments.getLast().icon());
        assertNull(segments.getFirst().icon());
        assertNull(segments.get(1).icon());
    }

    @Test
    void tabSelectionWrapsAroundTheOpenDocumentsAndReportsTheActiveOne() {
        controller.openFile(project.resolve("src/Main.java"), 0);
        controller.openFile(project.resolve("src/Util.java"), 0);

        controller.selectEditorTab(true);
        assertEquals(normalized(project.resolve("src/Main.java")), activeFile());
        assertEquals("Editor tab: " + normalized(project.resolve("src/Main.java")), lastStatus().replace('\\', '/'));

        controller.selectEditorTab(false);
        assertEquals(normalized(project.resolve("src/Util.java")), activeFile());
    }

    @Test
    void closingTheLastTabReportsTheEmptyEditor() {
        controller.openFile(project.resolve("src/Main.java"), 0);

        controller.closeActiveTab();
        assertTrue(editor.tabs().isEmpty());
        assertEquals("Tab closed", lastStatus());

        controller.closeActiveTab();
        assertEquals("No editor tab is open", lastStatus());
    }

    @Test
    void openingAProjectFileReportsItAndRefreshesVersionControl() {
        controller.openFile(project.resolve("src/Util.java"), 0);

        assertEquals("Opened src/Util.java", lastStatus().replace('\\', '/'));
        assertEquals(1, vcsRefreshes);
    }

    @Test
    void openingAFileOutsideTheProjectFailsWithoutTouchingVersionControl() {
        controller.openFile(tempDir.resolve("outside.txt"), 0);

        assertTrue(lastStatus().startsWith("The file is outside the project"), lastStatus());
        assertEquals(0, vcsRefreshes);
        assertTrue(editor.tabs().isEmpty());
    }

    @Test
    void theNavigationBarNeedsAnOpenFileAndTakesFocusWhenItHasOne() {
        controller.focusNavigationBar();
        assertFalse(controller.navigationBarFocused());
        assertEquals("Open a project file to navigate its path", lastStatus());

        controller.openFile(project.resolve("src/Main.java"), 0);
        controller.focusNavigationBar();
        assertTrue(controller.navigationBarFocused());
        assertEquals(List.of(StatusBarElement.ID), focusTargets);
    }

    @Test
    void anEditorActionClosesTheOverlaysBeforeItRuns() {
        controller.openFile(project.resolve("src/Main.java"), 0);
        List<Boolean> focusedWhenClosed = new ArrayList<>();

        assertTrue(controller.handle(KeyActions.FOCUS_NAVIGATION_BAR,
                () -> focusedWhenClosed.add(controller.navigationBarFocused())));

        assertEquals(List.of(false), focusedWhenClosed);
        assertTrue(controller.navigationBarFocused());
    }

    @Test
    void anActionOutsideTheEditorIsNotHandledAndClosesNothing() {
        AtomicInteger closes = new AtomicInteger();

        assertFalse(controller.handle(KeyActions.VCS_COMMIT, closes::incrementAndGet));
        assertEquals(0, closes.get());
    }

    @Test
    void nextProblemReportsAnActiveFileWithoutProblems() {
        controller.openFile(project.resolve("src/Main.java"), 0);

        assertTrue(controller.handle(KeyActions.NEXT_PROBLEM, () -> { }));
        assertEquals("No problems in the active file", lastStatus());
    }

    @Test
    void previousProblemReportsAnActiveFileWithoutProblems() {
        controller.openFile(project.resolve("src/Main.java"), 0);

        assertTrue(controller.handle(KeyActions.PREVIOUS_PROBLEM, () -> { }));
        assertEquals("No problems in the active file", lastStatus());
    }

    @Test
    void problemActionsCycleInBothDirectionsAndWrapAtTheEnds() throws IOException {
        Path file = project.resolve("src/Lines.java");
        Files.writeString(file, "one\ntwo\nthree\nfour\n");
        Path realFile = file.toRealPath();
        controller.openFile(realFile, 0);
        editor.setDiagnostics(List.of(problemAt(realFile, 0), problemAt(realFile, 1), problemAt(realFile, 3)));

        assertTrue(controller.handle(KeyActions.PREVIOUS_PROBLEM, () -> { }));
        assertEquals(3, caretLine(), "previous from the start wraps to the last problem");
        assertTrue(lastStatus().contains("problem at 3"));
        assertTrue(controller.handle(KeyActions.PREVIOUS_PROBLEM, () -> { }));
        assertEquals(1, caretLine());
        assertTrue(controller.handle(KeyActions.NEXT_PROBLEM, () -> { }));
        assertEquals(3, caretLine(), "next after previous returns to the problem previous left");
        assertTrue(controller.handle(KeyActions.NEXT_PROBLEM, () -> { }));
        assertEquals(0, caretLine(), "next from the last problem wraps to the first");
        assertTrue(lastStatus().contains("problem at 0"));
    }

    private int caretLine() {
        return editor.activeDocument().orElseThrow().line();
    }

    private static Diagnostic problemAt(Path file, int line) {
        return new Diagnostic(file, line, 0, line, 1, Severity.ERROR, "test", "problem at " + line);
    }

    @Test
    void openingSavingAndClosingAFileAskForAFreshDiagnosticsRead() {
        controller.openFile(project.resolve("src/Main.java"), 0);
        assertEquals(1, diagnosticsRefreshes);

        controller.saveActiveFile();
        assertEquals(2, diagnosticsRefreshes);

        controller.closeActiveTab();
        assertEquals(3, diagnosticsRefreshes);
    }

    @Test
    void namedBreakpointActionClosesOverlaysAndTargetsTheCaretLine() {
        controller.openFile(project.resolve("src/Main.java"), 0);
        List<Boolean> focusedWhenClosed = new ArrayList<>();

        assertTrue(controller.handle(KeyActions.TOGGLE_BREAKPOINT,
                () -> focusedWhenClosed.add(editor.activeDocument().isPresent())));

        assertEquals(List.of(true), focusedWhenClosed);
        assertEquals("Breakpoint toggled at line 1", lastStatus());
    }

    @Test
    void severalDefinitionsOpenThePopupInsteadOfTheFirstAndEnterOpensTheChosenOne() throws IOException {
        Path caller = caretOnHelperCall();
        Path first = project.resolve("src/Util.java").toRealPath();
        Path second = Files.writeString(project.resolve("src/Other.java"), "one\ntwo\nvoid helper() {}\n").toRealPath();
        navigation = new FakeNavigation(
                List.of(new Location(first, 0, 6), new Location(second, 2, 5), new Location(caller, 4, 0)), List.of());

        controller.navigateToSymbol(false);

        assertTrue(controller.navigationTargets().isOpen());
        assertEquals(3, controller.navigationTargets().view().rows().size());
        assertEquals("Choose Declaration", controller.navigationTargets().view().title());
        assertEquals(caller.toString(), editor.activeDocument().orElseThrow().file(),
                "the first location is not opened while the popup lists the choices");

        controller.pressNavigationTargets(OverlayKey.DOWN, 0);
        controller.pressNavigationTargets(OverlayKey.ENTER, 0);

        assertFalse(controller.navigationTargets().isOpen());
        assertEquals(second.toString(), editor.activeDocument().orElseThrow().file());
        assertEquals(2, caretLine());
    }

    @Test
    void aSingleDefinitionStillJumpsWithoutThePopup() throws IOException {
        caretOnHelperCall();
        Path util = project.resolve("src/Util.java").toRealPath();
        navigation = new FakeNavigation(List.of(new Location(util, 0, 6)), List.of());

        controller.navigateToSymbol(false);

        assertFalse(controller.navigationTargets().isOpen());
        assertEquals(util.toString(), editor.activeDocument().orElseThrow().file());
    }

    @Test
    void aDefinitionOnTheCaretListsTheUsagesOfTheDeclaration() throws IOException {
        Path caller = caretOnHelperCall();
        Path util = project.resolve("src/Util.java").toRealPath();
        navigation = new FakeNavigation(List.of(new Location(caller, 1, 0)),
                List.of(new Location(util, 0, 0), new Location(caller, 3, 0)));

        controller.navigateToSymbol(false);

        assertEquals(List.of(false), navigation.includeDeclaration, "the usages are asked without the declaration");
        assertTrue(controller.navigationTargets().isOpen());
        assertEquals("Usages of helper", controller.navigationTargets().view().title());
        assertEquals(2, controller.navigationTargets().view().rows().size());
        assertEquals(caller.toString(), editor.activeDocument().orElseThrow().file());
    }

    @Test
    void aDefinitionLaterOnTheSameLineIsNotTheDeclarationItself() throws IOException {
        Path caller = caretOnHelperCall();
        Path util = project.resolve("src/Util.java").toRealPath();
        navigation = new FakeNavigation(List.of(new Location(caller, 1, 20), new Location(util, 0, 6)), List.of());

        controller.navigateToSymbol(false);

        assertTrue(navigation.includeDeclaration.isEmpty(), "no usages are asked for a definition elsewhere");
        assertEquals("Choose Declaration", controller.navigationTargets().view().title());
    }

    @Test
    void aDeclarationWithoutUsagesSaysSo() throws IOException {
        Path caller = caretOnHelperCall();
        navigation = new FakeNavigation(List.of(new Location(caller, 1, 0)), List.of());

        controller.navigateToSymbol(false);

        assertFalse(controller.navigationTargets().isOpen());
        assertEquals("No usages found", lastStatus());
    }

    @Test
    void findUsagesHandsEveryUsageToTheFindWindowAndLeavesThePopupClosed() throws IOException {
        Path caller = caretOnHelperCall();
        Path util = project.resolve("src/Util.java").toRealPath();
        findWindowShown = true;
        navigation = new FakeNavigation(List.of(),
                List.of(new Location(caller, 1, 0), new Location(caller, 3, 0), new Location(util, 0, 0)));

        controller.navigateToSymbol(true);

        assertFalse(controller.navigationTargets().isOpen());
        assertEquals(1, usageSearches.size());
        UsageSearch search = usageSearches.getFirst();
        assertEquals("helper", search.symbol());
        assertEquals(3, search.usages().size());
        assertEquals("Usages of helper in 2 files (3)", search.header());
        assertEquals("Usages of helper in 2 files (3)", lastStatus());
    }

    @Test
    void findUsagesFallsBackToThePopupWhenThereIsNoFindWindow() throws IOException {
        Path caller = caretOnHelperCall();
        Path util = project.resolve("src/Util.java").toRealPath();
        navigation = new FakeNavigation(List.of(), List.of(new Location(caller, 3, 0), new Location(util, 0, 0)));

        controller.navigateToSymbol(true);

        assertTrue(usageSearches.isEmpty());
        assertEquals("Usages of helper", controller.navigationTargets().view().title());
        assertEquals(2, controller.navigationTargets().view().rows().size());
    }

    @Test
    void findUsagesWithoutResultsKeepsTheNoUsagesFoundStatus() throws IOException {
        caretOnHelperCall();
        findWindowShown = true;
        navigation = new FakeNavigation(List.of(), List.of());

        controller.navigateToSymbol(true);

        assertTrue(usageSearches.isEmpty());
        assertEquals("No usages found", lastStatus());
    }

    @Test
    void theRowsPreviewTheSourceLineFromTheOpenDocumentOrFromTheHostAndMarkTheSymbol() throws IOException {
        Path caller = caretOnHelperCall();
        Path util = project.resolve("src/Util.java").toRealPath();
        readable.put(util, "class Util {\n    int x = helper();\n}\n");
        findWindowShown = true;
        navigation = new FakeNavigation(List.of(), List.of(new Location(caller, 3, 0), new Location(util, 1, 12)));

        controller.navigateToSymbol(true);

        List<NavigationTarget> usages = usageSearches.getFirst().usages();
        assertEquals("helper();", usages.get(0).preview());
        assertEquals(0, usages.get(0).matchStart());
        assertEquals("int x = helper();", usages.get(1).preview());
        assertEquals(8, usages.get(1).matchStart(), "the range is moved by the trimmed indentation");
        assertEquals(6, usages.get(1).matchLength());
        assertEquals("src", usages.get(1).directory());
    }

    private Path caretOnHelperCall() throws IOException {
        Path caller = Files.writeString(project.resolve("src/Caller.java"),
                "class Caller {\nhelper();\n}\nhelper();\nhelper();\n").toRealPath();
        controller.openFile(caller, 1);
        return caller;
    }

    private static final class FakeNavigation implements NavigationCapability {

        private final List<Location> definitions;
        private final List<Location> usages;
        private final List<Boolean> includeDeclaration = new ArrayList<>();

        FakeNavigation(List<Location> definitions, List<Location> usages) {
            this.definitions = definitions;
            this.usages = usages;
        }

        @Override
        public CapabilityResult<List<Location>> definition(SourcePosition position) {
            return CapabilityResult.ok(definitions);
        }

        @Override
        public CapabilityResult<List<Location>> references(SourcePosition position, boolean includeDeclaration) {
            this.includeDeclaration.add(includeDeclaration);
            return CapabilityResult.ok(usages);
        }

        @Override
        public CapabilityResult<List<DocumentSymbol>> documentSymbols(Path file) {
            return CapabilityResult.ok(List.of());
        }

        @Override
        public CapabilityResult<List<WorkspaceSymbol>> workspaceSymbols(String query) {
            return CapabilityResult.ok(List.of());
        }

        @Override
        public String identifier() {
            return "fake";
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.available();
        }

        @Override
        public String provenance() {
            return "test";
        }
    }

    private EditorController.Host host() {
        return new EditorController.Host() {
            @Override
            public void status(String message) {
                statuses.add(message);
            }

            @Override
            public NavigationCapability navigationProvider() {
                return navigation;
            }

            @Override
            public void refreshVcs() {
                vcsRefreshes++;
            }

            @Override
            public void refreshProjectTree() {
            }

            @Override
            public void refreshDiagnostics() {
                diagnosticsRefreshes++;
            }

            @Override
            public void requestProjectFocus() {
            }

            @Override
            public void releaseCapture() {
            }

            @Override
            public void takeFocus(String elementId) {
                focusTargets.add(elementId);
            }

            @Override
            public Optional<String> readText(Path file) {
                return Optional.ofNullable(readable.get(file));
            }

            @Override
            public void openNavigationTargets(Runnable opener) {
                opener.run();
            }

            @Override
            public boolean showUsages(UsageSearch search) {
                if (findWindowShown) {
                    usageSearches.add(search);
                }
                return findWindowShown;
            }
        };
    }

    private String activeFile() {
        return editor.activeDocument().orElseThrow().file().replace('\\', '/');
    }

    private String lastStatus() {
        return statuses.getLast();
    }

    private static String normalized(Path path) {
        return path.toString().replace('\\', '/');
    }
}
