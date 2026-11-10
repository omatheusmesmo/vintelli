package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorControllerTest {

    @TempDir
    Path tempDir;

    private Path project;
    private BuiltinEditor editor;
    private EditorController controller;
    private final List<String> statuses = new ArrayList<>();
    private final List<String> focusTargets = new ArrayList<>();
    private int vcsRefreshes;

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
        controller = new EditorController(project, editor, projects, host());
    }

    @Test
    void breadcrumbSegmentsRunFromTheProjectRootDownToTheOpenFile() {
        controller.openFile(project.resolve("src/Main.java"), 0);
        controller.refreshNavigation();

        List<StatusBar.Breadcrumb> segments = controller.navigationSegments();
        assertEquals(List.of(project.getFileName().toString(), "src", "Main.java"),
                segments.stream().map(StatusBar.Breadcrumb::label).toList());
        assertEquals(project.resolve("src/Main.java"), segments.getLast().path());
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

    private EditorController.Host host() {
        return new EditorController.Host() {
            @Override
            public void status(String message) {
                statuses.add(message);
            }

            @Override
            public NavigationCapability navigationProvider() {
                return null;
            }

            @Override
            public void refreshVcs() {
                vcsRefreshes++;
            }

            @Override
            public void refreshProjectTree() {
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
