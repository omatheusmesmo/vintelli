package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProjectFileOperations;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraph;
import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraph.LibraryDependency;
import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraphProvider;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch;
import dev.omatheusmesmo.vintelli.tui.icons.JavaTypeKind;
import dev.omatheusmesmo.vintelli.tui.icons.JavaTypeKinds;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Owns the project tree: the nodes, the visible rows and paths, the selection
 * with its sync to the {@link ShellState}, the VCS state of the rows, the
 * pending create, rename and delete, and the click and scroll mirrors the
 * {@link ProjectPanel} reads. Queries only read, commands only change state.
 * It holds no TamboUI type: the panel draws it and forwards the events.
 */
final class ProjectTreeController {

    enum InputMode { NONE, CREATE_FILE, CREATE_DIRECTORY, RENAME }

    private static final List<String> PROJECT_FILES = List.of(
            "vintelli/pom.xml",
            "VintelliMain.java",
            "VintelliShell.java",
            "VintelliMcpTools.java",
            "VintelliShellTest.java"
    );
    private static final String LOADING_LABEL = "Loading project files...";
    private static final String EMPTY_LABEL = "No project files found";
    private static final String UNAVAILABLE_LABEL = "Project files unavailable";

    private final Path projectRoot;
    private final ShellState state;
    private final ProjectSearch projectSearch;
    private final ProjectFileOperations fileOperations;
    private final Consumer<String> statusSink;
    private final Runnable treeRefresh;
    private final Runnable vcsRefresh;
    private final JavaTypeKinds javaKinds;
    private final ProjectDependencyGraphProvider dependencyGraphProvider;
    private final ProjectNode externalLibraries;
    private final DoubleClickTracker doubleClick = new DoubleClickTracker();
    private final StringBuilder input = new StringBuilder();
    private final List<ProjectNode> rootNodes = new ArrayList<>();
    private final Marquee marquee = new Marquee(System::nanoTime);
    private LongSupplier clock = System::nanoTime;
    private volatile List<ProjectNode> visibleNodes = List.of();
    private volatile List<String> files;
    private volatile boolean filesLoading;
    private volatile boolean unavailable;
    private volatile boolean showHidden;
    private volatile String pendingDeletePath;
    private volatile InputMode inputMode = InputMode.NONE;
    private volatile Map<String, VersionControlCapability.State> vcsStates = Map.of();
    private volatile int selected;
    private volatile int syncedIndex = -1;
    private volatile int top;
    private volatile int leftColumn;
    private volatile boolean selectedClipped;
    private volatile long revision;
    private volatile Path copiedPath;

    ProjectTreeController(Path projectRoot, ShellState state, ProjectSearch projectSearch,
            ProjectFileOperations fileOperations, Consumer<String> statusSink, Runnable treeRefresh,
            Runnable vcsRefresh) {
        this(projectRoot, state, projectSearch, fileOperations, statusSink, treeRefresh, vcsRefresh,
                JavaTypeKinds.ofFiles());
    }

    ProjectTreeController(Path projectRoot, ShellState state, ProjectSearch projectSearch,
            ProjectFileOperations fileOperations, Consumer<String> statusSink, Runnable treeRefresh,
            Runnable vcsRefresh, JavaTypeKinds javaKinds) {
        this(projectRoot, state, projectSearch, fileOperations, statusSink, treeRefresh, vcsRefresh, javaKinds, null);
    }

    ProjectTreeController(Path projectRoot, ShellState state, ProjectSearch projectSearch,
            ProjectFileOperations fileOperations, Consumer<String> statusSink, Runnable treeRefresh,
            Runnable vcsRefresh, JavaTypeKinds javaKinds, ProjectDependencyGraphProvider dependencyGraphProvider) {
        this.projectRoot = projectRoot;
        this.state = state;
        this.projectSearch = projectSearch;
        this.fileOperations = fileOperations;
        this.statusSink = statusSink;
        this.treeRefresh = treeRefresh;
        this.vcsRefresh = vcsRefresh;
        this.javaKinds = javaKinds;
        this.dependencyGraphProvider = dependencyGraphProvider;
        this.externalLibraries = dependencyGraphProvider != null && dependencyGraphProvider.supports(projectRoot)
                ? ProjectNode.externalLibraries(Path.of(".vintelli-external-libraries"), "External Libraries (Loading…)")
                : null;
        this.files = projectSearch == null ? PROJECT_FILES : List.of();
        this.filesLoading = projectSearch != null;
    }

    void start() {
        if (externalLibraries != null) {
            rootNodes.add(externalLibraries);
            recomputeVisible("");
        }
        state.selectFile(files.isEmpty() ? "" : files.getFirst());
        syncedIndex = 0;
    }

    ProjectDependencyGraphProvider dependencyGraphProvider() {
        return dependencyGraphProvider;
    }

    void finishDependencyLoad(ProjectDependencyGraph graph) {
        if (externalLibraries == null) {
            return;
        }
        externalLibraries.children.clear();
        externalLibraries.status = graph.message();
        externalLibraries.name = graph.modules().isEmpty() && !graph.complete()
                ? "External Libraries (Unavailable)" : graph.complete()
                        ? "External Libraries" : "External Libraries (Incomplete)";
        boolean groupModules = graph.modules().size() > 1;
        int[] sequence = {0};
        for (ProjectDependencyGraph.Module module : graph.modules()) {
            ProjectNode parent = externalLibraries;
            if (groupModules) {
                ProjectNode moduleNode = new ProjectNode(externalLibraries.relative.resolve("module-" + sequence[0]++),
                        module.name(), true);
                moduleNode.parent = externalLibraries;
                externalLibraries.children.add(moduleNode);
                parent = moduleNode;
            }
            appendDependencies(parent, module.dependencies(), sequence);
        }
        if (!graph.complete() && graph.modules().isEmpty()) {
            ProjectNode detail = new ProjectNode(externalLibraries.relative.resolve("status"),
                    graph.message().isBlank() ? "Dependency graph unavailable" : graph.message(), false);
            detail.parent = externalLibraries;
            externalLibraries.children.add(detail);
        }
        recomputeVisible(selectedRelative().orElse(""));
    }

    private static void appendDependencies(ProjectNode parent, List<LibraryDependency> dependencies, int[] sequence) {
        for (LibraryDependency dependency : dependencies) {
            String label = dependency.artifactId() + ":" + dependency.version();
            if (dependency.disposition() == ProjectDependencyGraph.Disposition.OMITTED) {
                label += " (omitted)";
            } else if (dependency.disposition() == ProjectDependencyGraph.Disposition.MISSING) {
                label += " (not cached)";
            }
            ProjectNode node = new ProjectNode(parent.relative.resolve("dependency-" + sequence[0]++), label,
                    !dependency.children().isEmpty());
            node.dependency = true;
            node.scope = dependency.scope();
            node.optional = dependency.optional();
            node.disposition = dependency.disposition();
            node.jar = dependency.jar();
            node.parent = parent;
            parent.children.add(node);
            appendDependencies(node, dependency.children(), sequence);
        }
    }

    void useClock(LongSupplier clock) {
        this.clock = clock;
        marquee.useClock(clock);
    }

    void useMarquee(boolean enabled) {
        marquee.enable(enabled);
    }

    void onMarqueeChange(Runnable listener) {
        marquee.onChange(listener);
    }

    boolean marqueeEnabled() {
        return marquee.enabled();
    }

    boolean marqueeMoving() {
        return marquee.moving();
    }

    int marqueeOffset() {
        return marquee.offset();
    }

    /** Advances the marquee of the selected name by one tick; the shell calls it, never a render. */
    void tickMarquee() {
        marquee.follow(selectedRelative().orElse(""));
        marquee.tick();
    }

    List<String> files() {
        return List.copyOf(files);
    }

    int fileCount() {
        return files.size();
    }

    List<ProjectNode> visibleNodes() {
        return List.copyOf(visibleNodes);
    }

    boolean filesLoading() {
        return filesLoading;
    }

    String emptyLabel() {
        if (unavailable) {
            return UNAVAILABLE_LABEL;
        }
        return filesLoading ? LOADING_LABEL : EMPTY_LABEL;
    }

    int selected() {
        return selected;
    }

    int top() {
        return top;
    }

    int leftColumn() {
        return leftColumn;
    }

    long revision() {
        return revision;
    }

    boolean showHidden() {
        return showHidden;
    }

    ProjectNode selectedNode() {
        List<ProjectNode> nodes = visibleNodes;
        return selected < 0 || selected >= nodes.size() ? null : nodes.get(selected);
    }

    JavaTypeKind javaKind(ProjectNode node) {
        if (node.externalLibraries || node.dependency) {
            return JavaTypeKind.CLASS;
        }
        return javaKinds.kindOf(projectRoot.resolve(node.relative));
    }

    long javaKindVersion() {
        return javaKinds.version();
    }

    Optional<String> selectedRelative() {
        return selected < 0 || selected >= files.size() ? Optional.empty() : Optional.of(files.get(selected));
    }

    Optional<Path> selectedPath() {
        ProjectNode node = selectedNode();
        return node != null && (node.externalLibraries || node.dependency)
                ? Optional.ofNullable(node.jar) : selectedRelative().map(projectRoot::resolve);
    }

    VersionControlCapability.State vcsState(String relative) {
        return vcsStates.get(relative.replace('\\', '/'));
    }

    Map<String, VersionControlCapability.State> vcsStates() {
        return vcsStates;
    }

    Path copiedPath() {
        return copiedPath;
    }

    boolean inputActive() {
        return inputMode != InputMode.NONE;
    }

    InputMode inputMode() {
        return inputMode;
    }

    String inputText() {
        return input.toString();
    }

    boolean deletePending() {
        return pendingDeletePath != null;
    }

    void publishVcsStates(Map<String, VersionControlCapability.State> states) {
        vcsStates = Map.copyOf(states);
        revision++;
    }

    void setSelection(int index) {
        if (index != selected) {
            marquee.restart();
        }
        selected = index;
    }

    void syncSelection() {
        if (files.isEmpty()) {
            syncedIndex = selected;
            state.selectFile("");
            if (filesLoading) {
                statusSink.accept(LOADING_LABEL);
            }
            return;
        }
        if (selected == syncedIndex) {
            return;
        }
        syncedIndex = selected;
        state.selectFile(files.get(selected));
        statusSink.accept("Selected: " + files.get(selected));
    }

    void resetSync() {
        state.selectFile(files.isEmpty() ? "" : files.getFirst());
        syncedIndex = 0;
    }

    void moveSelection(int delta) {
        if (files.isEmpty()) {
            statusSink.accept(filesLoading ? LOADING_LABEL : EMPTY_LABEL);
            return;
        }
        int target = Math.clamp(selected + delta, 0, files.size() - 1);
        if (target != selected) {
            selected = target;
            marquee.restart();
            syncSelection();
        }
    }

    /**
     * Keeps the index of the first drawn row in step with the selection. The
     * panel calls it while it renders, so the offset that maps a click to its
     * row lives here; it changes only to bring the selected row into view.
     */
    void trackScroll(int visibleRows) {
        if (visibleRows <= 0) {
            return;
        }
        int total = Math.max(1, files.size());
        int current = Math.clamp(selected, 0, total - 1);
        int offset = top;
        if (current < offset) {
            offset = current;
        } else if (current >= offset + visibleRows) {
            offset = current - visibleRows + 1;
        }
        top = Math.clamp(offset, 0, Math.max(0, total - visibleRows));
    }

    /**
     * Fits the selected row to the view. With the marquee on, the indent and the
     * icon stay in place and the name of the selected row scrolls, so the view keeps
     * its leading columns. With the marquee off, a name that fits the view, whose row
     * overflows by less than the name keeps one column of it, is cut at the end
     * with an ellipsis, so the leading columns of every row stay. Otherwise the
     * offset moves to show the end of the row, which cuts the leading columns of
     * every row as before.
     */
    void trackColumn(int selectedWidth, int viewWidth, int nameWidth) {
        int overflow = Math.max(0, selectedWidth - viewWidth);
        if (marquee.enabled()) {
            boolean prefixVisible = selectedWidth - nameWidth < viewWidth;
            leftColumn = prefixVisible ? 0 : overflow;
            marquee.fit(prefixVisible ? overflow : 0);
            selectedClipped = prefixVisible && overflow > 0;
            return;
        }
        boolean nameHidden = nameWidth > viewWidth || overflow >= nameWidth - 1;
        leftColumn = nameHidden ? overflow : 0;
        selectedClipped = overflow > 0 && !nameHidden;
    }

    boolean selectedClipped() {
        return selectedClipped;
    }

    boolean registerClick(int index) {
        return doubleClick.register(index, clock.getAsLong());
    }

    void resetDoubleClick() {
        doubleClick.reset();
    }

    void refresh(boolean updateStatus) {
        if (projectSearch == null) {
            return;
        }
        List<Path> found = projectSearch.listFiles();
        List<Path> directories = projectSearch.listDirectories();
        Set<Path> directoryPaths = new HashSet<>(directories);
        List<Path> entries = new ArrayList<>(directories);
        entries.addAll(found);
        List<Path> filtered = showHidden ? entries
                : entries.stream().filter(ProjectTreeController::isVisibleEntry).toList();
        buildTree(filtered, directoryPaths);
        String previous = files.isEmpty() ? ""
                : (selected >= 0 && selected < files.size() ? files.get(selected) : "");
        recomputeVisible(previous);
        revalidateJavaKinds();
        filesLoading = false;
        unavailable = false;
        if (updateStatus) {
            statusSink.accept(visibleNodes.isEmpty() ? EMPTY_LABEL : "Project files loaded");
        }
    }

    private void revalidateJavaKinds() {
        for (ProjectNode node : visibleNodes) {
            if (!node.directory) {
                javaKinds.revalidate(projectRoot.resolve(node.relative));
            }
        }
    }

    void markUnavailable(String detail) {
        files = List.of();
        filesLoading = false;
        unavailable = true;
        visibleNodes = List.of();
        rootNodes.clear();
        if (externalLibraries != null) {
            rootNodes.add(externalLibraries);
        }
        revision++;
        state.selectFile("");
        statusSink.accept("Project files unavailable: " + detail);
    }

    private static boolean isVisibleEntry(Path path) {
        for (int i = 0; i < path.getNameCount(); i++) {
            if (path.getName(i).toString().startsWith(".")) {
                return false;
            }
        }
        return true;
    }

    private static String slashed(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    void expandSelected() {
        ProjectNode node = selectedNode();
        if (node != null && node.directory && !node.expanded) {
            setChainExpanded(node, true);
            recomputeVisible(selectedRelative().orElse(""));
            statusSink.accept("Expanded " + slashed(node.relative));
        }
    }

    void collapseSelected() {
        ProjectNode node = selectedNode();
        if (node != null && node.directory && node.expanded) {
            setChainExpanded(node, false);
            recomputeVisible(selectedRelative().orElse(""));
            statusSink.accept("Collapsed " + slashed(node.relative));
        }
    }

    boolean selectedDirectory() {
        ProjectNode node = selectedNode();
        return node != null && node.directory;
    }

    void toggleSelected() {
        ProjectNode node = selectedNode();
        if (node != null && node.directory) {
            setChainExpanded(node, !node.expanded);
            recomputeVisible(selectedRelative().orElse(""));
            statusSink.accept((node.expanded ? "Expanded " : "Collapsed ") + slashed(node.relative));
        }
    }

    /**
     * Sets the expansion of a compacted chain on every folder of it, so the
     * state survives when a later change breaks the chain into separate rows.
     */
    private static void setChainExpanded(ProjectNode row, boolean expanded) {
        for (ProjectNode node = row; ; node = node.parent) {
            node.expanded = expanded;
            if (node.parent == null || !node.parent.compactsChild()) {
                return;
            }
        }
    }

    void expandAll() {
        setExpanded(rootNodes, true);
        recomputeVisible(selectedRelative().orElse(""));
        statusSink.accept("Expanded all");
    }

    /**
     * Collapses every directory and keeps the selection on the top level folder
     * that holds the selected row, so the selection stays visible.
     */
    void collapseAll() {
        ProjectNode top = selectedNode();
        while (top != null && top.parent != null) {
            top = top.parent;
        }
        String keep = top == null ? "" : top.relative.toString().replace('\\', '/');
        setExpanded(rootNodes, false);
        recomputeVisible(keep);
        statusSink.accept("Collapsed all");
    }

    private static void setExpanded(List<ProjectNode> nodes, boolean expanded) {
        for (ProjectNode node : nodes) {
            if (node.directory) {
                node.expanded = expanded;
            }
            if (node.children != null) {
                setExpanded(node.children, expanded);
            }
        }
    }

    /**
     * The Enter action on the selected row: a directory toggles and returns
     * empty, a file is returned for the editor to open.
     */
    Optional<Path> activateSelected() {
        if (files.isEmpty()) {
            statusSink.accept(filesLoading ? LOADING_LABEL : EMPTY_LABEL);
            return Optional.empty();
        }
        ProjectNode node = selectedNode();
        if (node != null && node.externalLibraries) {
            toggleSelected();
            return Optional.empty();
        }
        if (node != null && node.dependency) {
            if (node.jar != null) {
                statusSink.accept("Dependency " + node.name + " — cached at " + node.jar.getFileName());
                return Optional.empty();
            }
            if (!node.children.isEmpty()) {
                toggleSelected();
                return Optional.empty();
            }
            statusSink.accept("Dependency: " + node.name + " (" + node.scope + ") — "
                    + (node.status.isBlank() ? "artifact is not cached" : node.status));
            return Optional.empty();
        }
        if (node != null && node.directory) {
            toggleSelected();
            return Optional.empty();
        }
        if (node != null && node.directory) {
            toggleSelected();
            return Optional.empty();
        }
        return Optional.of(Path.of(files.get(selected)));
    }

    void expandPrefixes(Path relative) {
        Path accumulated = Path.of("");
        for (Path part : relative) {
            accumulated = accumulated.resolve(part);
            ProjectNode node = findNode(accumulated.toString().replace('\\', '/'));
            if (node != null) {
                node.expanded = true;
            }
        }
    }

    /**
     * Expands the directories that lead to {@code file} and returns its path
     * relative to the project, or null with a status when it lies outside.
     */
    String expandToFile(Path file) {
        try {
            Path relative = projectRoot.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
            expandPrefixes(relative);
            return relative.toString().replace('\\', '/');
        } catch (IllegalArgumentException outside) {
            statusSink.accept("File outside project");
            return null;
        }
    }

    /**
     * Selects the row of {@code relative}. A folder folded into a compacted
     * chain has no row of its own, so it selects the row of the chain it is in.
     */
    void selectPath(String relative) {
        int index = rowIndexOf(relative);
        if (index >= 0) {
            String row = files.get(index);
            selected = index;
            syncedIndex = index;
            state.selectFile(row);
            statusSink.accept("Selected: " + row);
        }
    }

    private int rowIndexOf(String relative) {
        int exact = files.indexOf(relative);
        if (exact >= 0) {
            return exact;
        }
        for (int index = 0; index < files.size(); index++) {
            if (files.get(index).startsWith(relative + "/")) {
                return index;
            }
        }
        return -1;
    }

    boolean beginInput(InputMode mode) {
        ProjectNode selectedNode = selectedNode();
        if (selectedNode != null && (selectedNode.externalLibraries || selectedNode.dependency)) {
            statusSink.accept("External libraries are read-only");
            return false;
        }
        if (fileOperations == null) {
            statusSink.accept("Project file operations unavailable");
            return false;
        }
        if (mode == InputMode.RENAME && selectedNode() == null) {
            statusSink.accept("No selection");
            return false;
        }
        inputMode = mode;
        input.setLength(0);
        refreshPrompt();
        return true;
    }

    void refreshPrompt() {
        String label = switch (inputMode) {
            case CREATE_FILE -> "New file";
            case CREATE_DIRECTORY -> "New directory";
            case RENAME -> "Rename";
            case NONE -> "";
        };
        statusSink.accept(label + ": " + input + " (Enter to confirm, Escape to cancel)");
    }

    void appendInput(int codePoint) {
        input.appendCodePoint(codePoint);
        refreshPrompt();
    }

    void deleteInputCharacter() {
        if (!input.isEmpty()) {
            input.delete(input.offsetByCodePoints(input.length(), -1), input.length());
            refreshPrompt();
        }
    }

    void cancelInput() {
        inputMode = InputMode.NONE;
        statusSink.accept("Project action cancelled");
    }

    void submitInput() {
        String name = input.toString();
        InputMode mode = inputMode;
        inputMode = InputMode.NONE;
        switch (mode) {
            case CREATE_FILE -> createFile(name);
            case CREATE_DIRECTORY -> createDirectory(name);
            case RENAME -> renameSelected(name);
            case NONE -> { }
        }
    }

    boolean createFile(String name) {
        return createEntry(name, false);
    }

    boolean createDirectory(String name) {
        return createEntry(name, true);
    }

    private boolean createEntry(String name, boolean directory) {
        if (name == null || name.isBlank()) {
            statusSink.accept("A name is required");
            return false;
        }
        ProjectNode parent = selectedNode();
        if (parent != null && (parent.externalLibraries || parent.dependency)) {
            statusSink.accept("External libraries are read-only");
            return false;
        }
        if (parent != null && !parent.directory) {
            parent = parent.parent;
        }
        if (fileOperations == null) {
            statusSink.accept("Project file operations unavailable");
            return false;
        }
        Path parentRelative = parent == null ? Path.of("") : parent.relative;
        CapabilityResult<Path> result = directory ? fileOperations.createDirectory(parentRelative, name)
                : fileOperations.createFile(parentRelative, name);
        if (result instanceof CapabilityResult.Failed<Path> failed) {
            statusSink.accept("Create failed: " + failed.failure().detail());
            return false;
        }
        if (name.startsWith(".")) {
            showHidden = true;
        }
        if (parent != null) {
            parent.expanded = true;
        }
        Path newRelative = parentRelative.resolve(name);
        treeRefresh.run();
        selectPath(newRelative.toString().replace('\\', '/'));
        statusSink.accept((directory ? "Directory" : "File") + " created: " + newRelative);
        return true;
    }

    boolean renameSelected(String newName) {
        ProjectNode node = selectedNode();
        if (node != null && (node.externalLibraries || node.dependency)) {
            statusSink.accept("External libraries are read-only");
            return false;
        }
        if (node == null) {
            statusSink.accept("No selection");
            return false;
        }
        if (newName == null || newName.isBlank()) {
            statusSink.accept("A name is required");
            return false;
        }
        if (fileOperations == null) {
            statusSink.accept("Project file operations unavailable");
            return false;
        }
        CapabilityResult<Path> result = fileOperations.rename(node.relative, newName);
        if (result instanceof CapabilityResult.Failed<Path> failed) {
            statusSink.accept("Rename failed: " + failed.failure().detail());
            return false;
        }
        Path parent = node.relative.getParent();
        Path newRelative = parent == null ? Path.of(newName) : parent.resolve(newName);
        treeRefresh.run();
        selectPath(newRelative.toString().replace('\\', '/'));
        statusSink.accept("Renamed to " + newName);
        return true;
    }

    void requestDelete() {
        ProjectNode node = selectedNode();
        if (node != null && (node.externalLibraries || node.dependency)) {
            statusSink.accept("External libraries are read-only");
            return;
        }
        if (node == null) {
            statusSink.accept("No selection");
            return;
        }
        pendingDeletePath = node.relative.toString().replace('\\', '/');
        statusSink.accept("Delete '" + node.name + "'? (y/n)");
    }

    boolean confirmDelete() {
        if (pendingDeletePath == null || pendingDeletePath.isBlank()) {
            return false;
        }
        if (fileOperations == null) {
            statusSink.accept("Project file operations unavailable");
            return false;
        }
        CapabilityResult<Path> result = fileOperations.delete(Path.of(pendingDeletePath));
        if (result instanceof CapabilityResult.Failed<Path> failed) {
            statusSink.accept("Delete failed: " + failed.failure().detail());
            return false;
        }
        String deleted = pendingDeletePath;
        pendingDeletePath = null;
        treeRefresh.run();
        statusSink.accept("Deleted " + deleted);
        return true;
    }

    void cancelDelete() {
        pendingDeletePath = null;
        statusSink.accept("Delete cancelled");
    }

    void copySelectedPath() {
        ProjectNode node = selectedNode();
        if (node == null) {
            statusSink.accept("No selection");
            return;
        }
        copiedPath = projectRoot.resolve(node.relative.toString().replace('/', File.separatorChar));
        statusSink.accept("Copied path: " + copiedPath);
    }

    void setShowHidden(boolean value) {
        showHidden = value;
    }

    private void buildTree(List<Path> found, Set<Path> directoryPaths) {
        Set<Path> expanded = new HashSet<>();
        collectExpanded(rootNodes, expanded);
        List<ProjectNode> newRoots = new ArrayList<>();
        Map<Path, ProjectNode> byPath = new HashMap<>();
        for (Path file : found) {
            ProjectNode parent = null;
            Path accumulated = Path.of("");
            for (int i = 0; i < file.getNameCount(); i++) {
                accumulated = accumulated.resolve(file.getName(i));
                boolean leaf = i == file.getNameCount() - 1;
                ProjectNode node = byPath.get(accumulated);
                if (node == null) {
                    node = new ProjectNode(accumulated, file.getName(i).toString(),
                            !leaf || directoryPaths.contains(accumulated));
                    node.expanded = expanded.contains(accumulated);
                    node.parent = parent;
                    byPath.put(accumulated, node);
                    if (parent == null) {
                        newRoots.add(node);
                    } else {
                        parent.children.add(node);
                    }
                } else if (!leaf) {
                    node.directory = true;
                }
                parent = node;
            }
        }
        sortNodes(newRoots);
        rootNodes.clear();
        rootNodes.addAll(newRoots);
        if (externalLibraries != null) {
            externalLibraries.parent = null;
            rootNodes.add(externalLibraries);
        }
    }

    private static void collectExpanded(List<ProjectNode> nodes, Set<Path> expanded) {
        for (ProjectNode node : nodes) {
            if (node.expanded) {
                expanded.add(node.relative);
            }
            collectExpanded(node.children, expanded);
        }
    }

    private void sortNodes(List<ProjectNode> nodes) {
        if (nodes == null || nodes.size() < 2) {
            if (nodes != null) {
                for (ProjectNode node : new ArrayList<>(nodes)) {
                    if (node != null && node.children != null && !node.children.isEmpty()
                            && node.children != nodes) {
                        sortNodes(node.children);
                    }
                }
            }
            return;
        }
        List<ProjectNode> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparing((ProjectNode node) -> !node.directory)
                .thenComparing(node -> node.name));
        nodes.clear();
        nodes.addAll(sorted);
        for (ProjectNode node : new ArrayList<>(nodes)) {
            if (node != null && node.children != null && !node.children.isEmpty()
                    && node.children != nodes) {
                sortNodes(node.children);
            }
        }
    }

    private void recomputeVisible(String previousSelection) {
        List<ProjectNode> objects = new ArrayList<>();
        appendVisible(rootNodes, objects);
        visibleNodes = List.copyOf(objects);
        List<String> paths = new ArrayList<>();
        for (ProjectNode node : objects) {
            paths.add(node.relative.toString().replace('\\', '/'));
        }
        files = List.copyOf(paths);
        revision++;
        int index = paths.indexOf(previousSelection);
        if (index < 0 && !paths.isEmpty()) {
            index = 0;
        }
        selected = Math.max(index, 0);
        syncedIndex = selected;
        if (paths.isEmpty()) {
            state.selectFile("");
        } else if (selected < paths.size()) {
            state.selectFile(paths.get(selected));
        }
        vcsRefresh.run();
    }

    private void appendVisible(List<ProjectNode> nodes, List<ProjectNode> output) {
        for (ProjectNode node : nodes) {
            ProjectNode row = node.compactEnd();
            output.add(row);
            if (row.directory && row.expanded && row.children != null) {
                appendVisible(row.children, output);
            }
        }
    }

    private ProjectNode findNode(String relative) {
        List<ProjectNode> current = rootNodes;
        ProjectNode found = null;
        Path accumulated = Path.of("");
        for (String part : relative.split("/")) {
            if (part.isBlank()) {
                continue;
            }
            accumulated = accumulated.resolve(part);
            ProjectNode next = null;
            for (ProjectNode candidate : current) {
                if (candidate.relative.equals(accumulated)) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            found = next;
            current = next.children == null ? List.of() : next.children;
        }
        return found;
    }
}
