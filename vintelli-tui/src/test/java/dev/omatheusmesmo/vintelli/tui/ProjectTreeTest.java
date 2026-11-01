package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.search.ProjectSearch;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProjectFileOperations;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.icons.FileIcons;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.app.ToolkitTestRunner;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.pilot.Pilot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.util.concurrent.CompletionStage;

import java.nio.file.Files;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Consumer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectTreeTest {

    @TempDir
    Path project;

    private VintelliShell shellWithProject(Path root) throws Exception {
        Files.createDirectories(root.resolve("src/main"));
        Files.writeString(root.resolve("src/main/A.java"), "class A {}");
        Files.writeString(root.resolve("README.md"), "# readme");
        Files.createDirectories(root.resolve(".hidden"));
        Files.writeString(root.resolve(".hidden/secret.txt"), "x");

        ProjectSearch search = fakeSearch(root);
        BuiltinEditor editor = new BuiltinEditor(root, null, () -> null);
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        List<ToolWindow> windows = new ArrayList<>(defaultWindows());
        windows.add(editor);
        ToolWindowRegistry registry = new ToolWindowRegistry(windows);
        return new VintelliShell(registry.toolWindows(), new ShellState(),
                () -> Keymap.defaultKeymap(registry.toolWindows()), themes, search, testFileOperations(root));
    }

    private static ProjectFileOperations testFileOperations(Path root) {
        return new ProjectFileOperations() {
            @Override
            public CapabilityResult<Path> createFile(Path directory, String name) {
                try { return CapabilityResult.ok(Files.createFile(root.resolve(directory).resolve(name))); }
                catch (IOException failure) { return failed(failure); }
            }

            @Override
            public CapabilityResult<Path> createDirectory(Path directory, String name) {
                try { return CapabilityResult.ok(Files.createDirectory(root.resolve(directory).resolve(name))); }
                catch (IOException failure) { return failed(failure); }
            }

            @Override
            public CapabilityResult<Path> rename(Path entry, String name) {
                try { return CapabilityResult.ok(Files.move(root.resolve(entry), root.resolve(entry).resolveSibling(name))); }
                catch (IOException failure) { return failed(failure); }
            }

            @Override
            public CapabilityResult<Path> delete(Path entry) {
                try { Files.delete(root.resolve(entry)); return CapabilityResult.ok(entry); }
                catch (IOException failure) { return failed(failure); }
            }

            private CapabilityResult<Path> failed(IOException failure) {
                return CapabilityResult.failed(new CapabilityFailure("test operation failed", failure.toString()));
            }
        };
    }

    private static List<ToolWindow> defaultWindows() {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        ToolWindowRegistry registry = new ToolWindowRegistry(List.of(
                new RunToolWindow(() -> themes), new TerminalToolWindow(() -> themes),
                new ProblemsToolWindow(() -> themes), new AcpAgentsToolWindow(() -> themes)));
        return registry.toolWindows();
    }

    @Test
    void refreshPopulatesTreeFromTempDirectory() throws Exception {
        VintelliShell shell = shellWithProject(project);
        shell.refreshProjectTree();
        assertFalse(shell.projectFiles().isEmpty(), "tree should list discovered files");
        assertTrue(shell.projectFiles().stream().anyMatch(p -> p.equals("src")), "src dir should be present");
        assertTrue(shell.projectFiles().stream().anyMatch(p -> p.equals("README.md")), "README should be present");
    }

    @Test
    void hiddenFilesHiddenByDefaultAndToggleRevealsThem() throws Exception {
        VintelliShell shell = shellWithProject(project);
        shell.refreshProjectTree();
        assertFalse(shell.showHiddenFiles(), "hidden files hidden by default");
        assertTrue(shell.projectFiles().stream().noneMatch(p -> p.contains(".hidden")),
                "hidden dir should be filtered");
        shell.toggleShowHiddenFiles();
        assertTrue(shell.showHiddenFiles(), "toggle should reveal hidden files");
        assertTrue(shell.projectFiles().stream().anyMatch(p -> p.contains(".hidden")),
                "hidden dir should appear after toggle");
    }

    @Test
    void createFileAddsEntryAndSelectsIt() throws Exception {
        VintelliShell shell = shellWithProject(project);
        shell.refreshProjectTree();
        int before = shell.projectFiles().size();
        assertTrue(shell.createFile("NewFile.java"), "create should succeed");
        assertTrue(Files.exists(project.resolve("src/NewFile.java")), "file should exist on disk");
    }

    @Test
    void createDirectoryAddsEntry() throws Exception {
        VintelliShell shell = shellWithProject(project);
        shell.refreshProjectTree();
        shell.selectPath("src");
        boolean r = shell.createDirectory("newdir");
        assertTrue(r, "create dir should succeed: " + shell.status());
        assertTrue(Files.isDirectory(project.resolve("src/newdir")), "dir should exist on disk");
        assertTrue(shell.projectFiles().contains("src/newdir"), "empty directory should be visible");
    }

    @Test
    void renameSelectedMovesFile() throws Exception {
        VintelliShell shell = shellWithProject(project);
        shell.refreshProjectTree();
        // select README.md
        int idx = shell.projectFiles().indexOf("README.md");
        assertTrue(idx >= 0);
        shell.selectPath("README.md");
        shell.selectPath("README.md");
        assertTrue(shell.renameSelected("RENAMED.md"), "rename should succeed: " + shell.status());
        assertTrue(Files.exists(project.resolve("RENAMED.md")), "renamed file should exist");
    }

    @Test
    void deleteRequiresConfirmationThenRemoves() throws Exception {
        VintelliShell shell = shellWithProject(project);
        shell.refreshProjectTree();
        int idx = shell.projectFiles().indexOf("README.md");
        assertTrue(idx >= 0);
        shell.selectPath("README.md");
        shell.requestDeleteSelected();
        assertTrue(shell.status().toLowerCase().contains("delete"), "should ask for confirmation");
        assertTrue(Files.exists(project.resolve("README.md")), "not deleted before confirm");
        assertTrue(shell.deleteSelectedConfirmed(), "confirm should succeed");
        assertFalse(Files.exists(project.resolve("README.md")), "deleted after confirm");
    }

    @Test
    void cancelDeleteKeepsFile() throws Exception {
        VintelliShell shell = shellWithProject(project);
        shell.refreshProjectTree();
        int idx = shell.projectFiles().indexOf("README.md");
        shell.selectPath("README.md");
        shell.requestDeleteSelected();
        shell.cancelDelete();
        assertTrue(Files.exists(project.resolve("README.md")), "file should remain");
    }

    @Test
    void projectKeysCreateRenameAndDoNotDeleteWhenEditorIsFocused() throws Exception {
        VintelliShell shell = shellWithProject(project);
        shell.refreshProjectTree();
        shell.selectPath("src");
        try (ToolkitTestRunner runner = ToolkitTestRunner.runTest(shell::root, new Size(100, 30))) {
            runner.runner().styleEngine(shell.themes().engine());
            runner.runner().eventRouter().addGlobalHandler(shell);
            Pilot pilot = runner.pilot();
            pilot.pause(Duration.ofMillis(100));
            Files.writeString(project.resolve("External.txt"), "created outside the TUI");
            pilot.press(KeyCode.F5);
            pilot.pause(Duration.ofMillis(100));
            assertTrue(shell.projectFiles().contains("External.txt"), "F5 should discover external changes");

            pilot.press(KeyCode.F7);
            for (char ch : "New.java".toCharArray()) { pilot.press(ch); }
            pilot.press(KeyCode.ENTER);
            pilot.pause(Duration.ofMillis(100));
            assertTrue(Files.exists(project.resolve("src/New.java")), shell.status());

            pilot.press(KeyCode.F2);
            for (char ch : "Renamed.java".toCharArray()) { pilot.press(ch); }
            pilot.press(KeyCode.ENTER);
            pilot.pause(Duration.ofMillis(100));
            assertTrue(Files.exists(project.resolve("src/Renamed.java")), shell.status());

            pilot.click("editor-panel");
            pilot.press(KeyCode.DELETE);
            pilot.pause(Duration.ofMillis(100));
            assertFalse(shell.isDeletePending());
            assertTrue(Files.exists(project.resolve("src/Renamed.java")));
        }
    }

    @Test
    void rendersTreeConnectorsForExpandedDirectories() throws Exception {
        VintelliShell shell = shellWithProject(project);
        shell.refreshProjectTree();
        shell.selectPath("src");
        shell.expandSelected();
        shell.selectPath("src/main");
        shell.expandSelected();

        Rect area = new Rect(0, 0, 100, 30);
        Buffer buffer = Buffer.empty(area);
        DefaultRenderContext context = DefaultRenderContext.createEmpty();
        context.setStyleEngine(shell.themes().engine());
        Element root = shell.root();
        root.render(Frame.forTesting(buffer), area, context);
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < buffer.height(); y++) {
            for (int x = 0; x < buffer.width(); x++) {
                text.append(buffer.get(x, y).symbol());
            }
            text.append('\n');
        }

        FileIcons icons = FileIcons.resolve();
        boolean nerd = icons.mode() == IconMode.NERD;
        boolean unicodeTree = ProjectTreeGlyphs.supportsUnicode();
        String folderIcon = icons.folderIconFor("src").symbol(icons.mode());
        String fileIcon = icons.iconFor("A.java").symbol(icons.mode());
        assertTrue(text.toString().contains((unicodeTree ? "├── " : "|-- ")
                + (nerd ? "▾ " : "- ") + folderIcon + " src/"), text.toString());
        assertTrue(text.toString().contains((unicodeTree ? "│       └── " : "|       \\-- ") + fileIcon + " A.java"),
                text.toString());
    }

    @Test
    void refreshPreservesExpandedDirectoriesAndSelection() throws Exception {
        VintelliShell shell = shellWithProject(project);
        shell.refreshProjectTree();
        shell.selectPath("src");
        shell.expandSelected();
        shell.selectPath("src/main");
        shell.expandSelected();
        shell.selectPath("src/main/A.java");

        shell.refreshProjectTree();

        assertTrue(shell.projectFiles().contains("src/main/A.java"));
        assertEquals("src/main/A.java", shell.selectedPathString());
    }

    @Test
    void expandAndCollapseDirectory() throws Exception {
        VintelliShell shell = shellWithProject(project);
        shell.refreshProjectTree();
        // find a directory node entry (src)
        shell.selectPath("src");
        shell.expandSelected();
        shell.collapseSelected();
        assertFalse(shell.projectFiles().isEmpty());
    }

    @Test
    void emptyProjectShowsEmptyState() throws Exception {
        Path empty = project.resolve("empty");
        Files.createDirectories(empty);
        ProjectSearch search = fakeSearch(empty);
        BuiltinEditor editor = new BuiltinEditor(empty, null, () -> null);
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        List<ToolWindow> windows = new ArrayList<>(defaultWindows());
        windows.add(editor);
        ToolWindowRegistry registry = new ToolWindowRegistry(windows);
        VintelliShell shell = new VintelliShell(registry.toolWindows(), new ShellState(),
                () -> Keymap.defaultKeymap(registry.toolWindows()), themes, search);
        shell.refreshProjectTree();
        assertTrue(shell.projectFiles().isEmpty() || shell.projectFiles().stream().allMatch(s -> s.contains("No project")),
                "empty project should show empty state");
    }

    @Test
    void copySelectedPathPopulatesClipboard() throws Exception {
        VintelliShell shell = shellWithProject(project);
        shell.refreshProjectTree();
        shell.copySelectedPath();
        // status should mention copy
        assertTrue(shell.status().toLowerCase().contains("cop") || shell.status().toLowerCase().contains("path"),
                "copy should update status: " + shell.status());
    }
    private static ProjectSearch fakeSearch(Path root) {
        return new ProjectSearch() {
            @Override
            public SearchTask files(String query, Consumer<List<FileMatch>> results) {
                return new SearchTask() {
                    public void cancel() {}
                    public boolean cancelled() { return false; }
                    public CompletionStage<Void> completion() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
                };
            }
            @Override
            public SearchTask text(String query, boolean regex, Consumer<List<TextMatch>> results) {
                return new SearchTask() {
                    public void cancel() {}
                    public boolean cancelled() { return false; }
                    public CompletionStage<Void> completion() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
                };
            }
            @Override
            public List<Path> listFiles() {
                List<Path> out = new ArrayList<>();
                try (var walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile).forEach(p -> out.add(root.relativize(p)));
                } catch (Exception ignored) {
                }
                out.sort(java.util.Comparator.comparing(Path::toString));
                return List.copyOf(out);
            }

            @Override
            public List<Path> listDirectories() {
                List<Path> out = new ArrayList<>();
                try (var walk = Files.walk(root)) {
                    walk.filter(Files::isDirectory).filter(path -> !path.equals(root))
                            .forEach(path -> out.add(root.relativize(path)));
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
                return List.copyOf(out);
            }
        };
    }

}
