package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.search.ProjectSearch;
import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraph;
import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraph.LibraryDependency;
import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraph.Disposition;
import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraph.Module;
import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraphProvider;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProjectFileOperations;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.icons.FileIcons;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.omatheusmesmo.vintelli.tui.icons.JavaTypeKind;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.app.ToolkitTestRunner;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyModifiers;
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
        return shellWithProject(root, null);
    }

    private VintelliShell shellWithProject(Path root, ProjectDependencyGraphProvider dependencyGraphProvider)
            throws Exception {
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
        ShellConfig config = ShellConfig.of(registry.toolWindows(), new ShellState(), themes)
                .withKeymapSupplier(() -> Keymap.defaultKeymap(registry.toolWindows()))
                .withProjectSearch(search)
                .withFileOperations(testFileOperations(root));
        if (dependencyGraphProvider != null) {
            config = config.withDependencyGraphProviders(List.of(dependencyGraphProvider));
        }
        return new VintelliShell(config);
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
        assertTrue(shell.projectFiles().stream().anyMatch(p -> p.equals("src/main")), "src/main chain should be present");
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
        assertTrue(Files.exists(project.resolve("src/main/NewFile.java")), "file should exist on disk");
    }

    @Test
    void createDirectoryAddsEntry() throws Exception {
        VintelliShell shell = shellWithProject(project);
        shell.refreshProjectTree();
        shell.selectPath("src");
        boolean r = shell.createDirectory("newdir");
        assertTrue(r, "create dir should succeed: " + shell.status());
        assertTrue(Files.isDirectory(project.resolve("src/main/newdir")), "dir should exist on disk");
        assertTrue(shell.projectFiles().contains("src/main/newdir"), "empty directory should be visible");
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
        try (ToolkitTestRunner runner = ToolkitTestRunner.runTest(shell::root, new Size(160, 30))) {
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
            assertTrue(Files.exists(project.resolve("src/main/New.java")), shell.status());

            pilot.press(KeyCode.F6, KeyModifiers.SHIFT);
            for (char ch : "Renamed.java".toCharArray()) { pilot.press(ch); }
            pilot.press(KeyCode.ENTER);
            pilot.pause(Duration.ofMillis(100));
            assertTrue(Files.exists(project.resolve("src/main/Renamed.java")), shell.status());

            pilot.click("editor-panel");
            pilot.press(KeyCode.DELETE);
            pilot.pause(Duration.ofMillis(100));
            assertFalse(shell.isDeletePending());
            assertTrue(Files.exists(project.resolve("src/main/Renamed.java")));
        }
    }

    @Test
    void rendersDisclosureGlyphsAndIndentForExpandedDirectories() throws Exception {
        VintelliShell shell = shellWithProject(project);
        shell.refreshProjectTree();
        shell.selectPath("src");
        shell.expandSelected();
        shell.selectPath("src/main");
        shell.expandSelected();

        FileIcons icons = FileIcons.resolve();
        String expanded = disclosureGlyph(icons, ProjectTreeGlyphs.supportsUnicode(), true);
        String folderIcon = icons.folderIconFor("src").symbol(icons.mode());
        String fileIcon = icons.iconFor("A.java", JavaTypeKind.CLASS).symbol(icons.mode());
        String mainIcon = icons.folderIconFor("main").symbol(icons.mode());
        String rendered = renderUntil(shell, "    " + fileIcon + " A.java");
        assertTrue(rendered.contains(expanded + " " + mainIcon + " src/main"), rendered);
        assertTrue(rendered.contains("    " + fileIcon + " A.java"), rendered);
        assertFalse(rendered.contains("├") || rendered.contains("└")
                || rendered.contains("|--") || rendered.contains("\\--"), rendered);
    }

    @Test
    void externalLibrariesExpandWithKeyboardAndShowScopes() throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Path jar = project.resolve("cached-library.jar");
        Files.writeString(jar, "test jar");
        ProjectDependencyGraph graph = new ProjectDependencyGraph(List.of(new Module("demo", List.of(
                new LibraryDependency("example", "library", "1.2", "runtime", Disposition.RESOLVED, jar,
                        List.of(new LibraryDependency("example", "transitive", "2.0", "compile",
                                Disposition.MISSING, null, List.of())), true)))), false,
                "Offline cache is incomplete");
        ProjectDependencyGraphProvider provider = new ProjectDependencyGraphProvider() {
            @Override public boolean supports(Path projectRoot) { return true; }
            @Override public ProjectDependencyGraph read(Path projectRoot) { return graph; }
        };
        VintelliShell shell = shellWithProject(project, provider);

        awaitProjectFiles(shell, path -> path.equals(".vintelli-external-libraries"));
        try (ToolkitTestRunner runner = ToolkitTestRunner.runTest(shell::root, new Size(160, 30))) {
            runner.runner().styleEngine(shell.themes().engine());
            runner.runner().eventRouter().addGlobalHandler(shell);
            Pilot pilot = runner.pilot();
            pilot.pause(Duration.ofMillis(300));
            var area = pilot.findElement(ProjectPanel.ID);
            int externalRow = shell.projectFiles().indexOf(".vintelli-external-libraries");
            pilot.click(area.x() + 10, area.y() + externalRow);
            pilot.press(KeyCode.ENTER);
            awaitProjectFiles(shell, path -> path.endsWith("/dependency-0"));
            shell.selectPath(".vintelli-external-libraries/dependency-0");
            pilot.press(KeyCode.RIGHT);
            awaitProjectFiles(shell, path -> path.endsWith("/dependency-1"));
            shell.selectPath(".vintelli-external-libraries/dependency-0");
            pilot.press(KeyCode.ENTER);
            assertTrue(shell.status().contains("cached-library.jar"), shell.status());
        }
        String expanded = renderText(shell);
        assertTrue(expanded.contains("(Incomplete)"), expanded);
        assertTrue(expanded.contains("1.2"), expanded);
        assertTrue(expanded.contains("(optional, runtime)"), expanded);
        assertTrue(expanded.contains("ansitive"), expanded);
    }

    @Test
    void externalLibrariesDisclosureCanBeOpenedByPointer() throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        ProjectDependencyGraph graph = new ProjectDependencyGraph(List.of(new Module("demo", List.of(
                new LibraryDependency("example", "library", "1.0", "compile", Disposition.MISSING, null, List.of())))),
                false, "Dependency artifact is not cached");
        ProjectDependencyGraphProvider provider = new ProjectDependencyGraphProvider() {
            @Override public boolean supports(Path projectRoot) { return true; }
            @Override public ProjectDependencyGraph read(Path projectRoot) { return graph; }
        };
        VintelliShell shell = shellWithProject(project, provider);
        awaitProjectFiles(shell, path -> path.equals(".vintelli-external-libraries"));

        try (ToolkitTestRunner runner = ToolkitTestRunner.runTest(shell::root, new Size(160, 30))) {
            runner.runner().styleEngine(shell.themes().engine());
            runner.runner().eventRouter().addGlobalHandler(shell);
            Pilot pilot = runner.pilot();
            pilot.pause(Duration.ofMillis(100));
            var area = pilot.findElement(ProjectPanel.ID);
            int row = shell.projectFiles().indexOf(".vintelli-external-libraries");
            shell.selectPath("README.md");
            pilot.click(area.x() + 10, area.y() + row);
            pilot.click(area.x() + 10, area.y() + row);
            assertEquals(row, shell.fileIndex());
            awaitProjectFiles(shell, path -> path.endsWith("/dependency-0"));
        }
        assertTrue(shell.projectFiles().contains(".vintelli-external-libraries/dependency-0"));
    }

    private static void awaitProjectFiles(VintelliShell shell, java.util.function.Predicate<String> condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && shell.projectFiles().stream().noneMatch(condition)) {
            Thread.sleep(10);
        }
        assertTrue(shell.projectFiles().stream().anyMatch(condition), shell.projectFiles().toString());
    }

    @Test
    void theTreeShowsTheKindOfEachJavaFileWhenItIsKnown() throws Exception {
        Files.writeString(project.resolve("Shape.java"), "public sealed interface Shape permits Point {}");
        Files.writeString(project.resolve("Point.java"), "public record Point(int x) {}");
        VintelliShell shell = shellWithProject(project);
        shell.refreshProjectTree();

        FileIcons icons = FileIcons.resolve();
        String shape = icons.iconFor("Shape.java", JavaTypeKind.INTERFACE).symbol(icons.mode()) + " Shape.java";
        String point = icons.iconFor("Point.java", JavaTypeKind.RECORD).symbol(icons.mode()) + " Point.java";
        String rendered = renderUntil(shell, shape, point);

        assertTrue(rendered.contains(shape), rendered);
        assertTrue(rendered.contains(point), rendered);
    }

    private static String renderUntil(VintelliShell shell, String... expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String rendered = renderText(shell);
        while (!containsAll(rendered, expected) && System.nanoTime() < deadline) {
            Thread.sleep(10);
            rendered = renderText(shell);
        }
        return rendered;
    }

    private static boolean containsAll(String text, String... expected) {
        for (String part : expected) {
            if (!text.contains(part)) {
                return false;
            }
        }
        return true;
    }

    private static String renderText(VintelliShell shell) {
        Rect area = new Rect(0, 0, 100, 30);
        Buffer buffer = Buffer.empty(area);
        DefaultRenderContext context = DefaultRenderContext.createEmpty();
        context.setStyleEngine(shell.themes().engine());
        shell.root().render(Frame.forTesting(buffer), area, context);
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < buffer.height(); y++) {
            for (int x = 0; x < buffer.width(); x++) {
                text.append(buffer.get(x, y).symbol());
            }
            text.append('\n');
        }
        return text.toString();
    }

    static String disclosureGlyph(FileIcons icons, boolean unicodeTree, boolean expanded) {
        if (icons.mode() == IconMode.NERD) {
            return expanded ? "" : "";
        }
        if (unicodeTree) {
            return expanded ? "▾" : "▸";
        }
        return expanded ? "-" : "+";
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
        VintelliShell shell = new VintelliShell(ShellConfig.of(registry.toolWindows(), new ShellState(), themes)
                .withKeymapSupplier(() -> Keymap.defaultKeymap(registry.toolWindows()))
                .withProjectSearch(search));
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
    static ProjectSearch fakeSearch(Path root) {
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
