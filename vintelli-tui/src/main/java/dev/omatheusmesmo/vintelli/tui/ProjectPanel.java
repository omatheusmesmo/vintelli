package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.tui.icons.FileIcon;
import dev.omatheusmesmo.vintelli.tui.icons.FileIcons;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyContext;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.menu.MenuEntry;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Span;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.widgets.common.ScrollBarPolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The project panel element: it draws the {@link ProjectTreeController} rows
 * in a list, forwards the focused keys and the pointer to the controller and
 * keeps the list cursor in step with the controller selection. The shell
 * supplies the layout, focus and keymap decisions through its {@link Host}.
 */
final class ProjectPanel implements Element {

    static final String ID = "project-panel";

    private static final int HIGHLIGHT_WIDTH = 2;
    private static final int INDENT_WIDTH = 2;
    private static final int MARKER_WIDTH = 2;
    private static final String ELLIPSIS = "…";
    private static final Style DIM = Style.EMPTY.dim();

    /**
     * The text of a row, the cell column where its icon starts (-1 when the icon
     * is cut away) and the icon span, which keeps the kind colour of the icon.
     */
    private record Label(String text, int iconColumn, Span icon) {

        Label withText(String shown) {
            return new Label(shown, iconColumn, icon);
        }
    }

    interface Host {

        Keymap keymap();

        default KeyContext keyContext() {
            return KeyContext.NONE;
        }

        boolean shortcutsBlocked();

        boolean layoutVisible();

        void focusPanel();

        void requestFocus();

        void beginInput(ProjectTreeController.InputMode mode);

        void refreshTree();

        void openFile(Path file);

        boolean pendingFocus();

        void clearPendingFocus();

        void releaseSplitterDrag();

        EventResult splitterMouse(MouseEvent mouse);

        void togglePanel();

        void revealActiveFile();

        /** Opens the diff tab of a changed file, as the Show Diff entry of the tree context menu does. */
        void showDiff(Path file);
    }

    private static final List<String> HEADER_ACTIONS = List.of(
            KeyActions.PROJECT_VIEW_MENU,
            KeyActions.PROJECT_NEW_MENU,
            KeyActions.PROJECT_SELECT_OPENED_FILE,
            KeyActions.PROJECT_EXPAND_ALL,
            KeyActions.PROJECT_COLLAPSE_ALL,
            KeyActions.PROJECT_OPTIONS_MENU,
            KeyActions.PROJECT_SHOW_HIDDEN,
            KeyActions.PROJECT_HIDE);

    private final ProjectTreeController controller;
    private final FileIcons fileIcons;
    private final ReentrantLock lock;
    private final Host host;
    private final ListElement<?> list;
    private final ProjectMenus menus;
    private boolean unicodeTree;
    private long pushedRevision = -1;
    private long pushedJavaKinds = -1;
    private boolean pushedUnicode;
    private int pushedLeft;
    private int pushedSelected = -1;
    private boolean pushedClipped;
    private int pushedOffset;
    private int pushedView = -1;
    private int viewColumns;
    private volatile Rect headerRow = Rect.ZERO;
    private volatile List<ProjectHeader.Target> headerTargets = List.of();

    ProjectPanel(ProjectTreeController controller, FileIcons fileIcons, boolean unicodeTree, ReentrantLock lock,
            Host host) {
        this.controller = controller;
        this.fileIcons = fileIcons;
        this.unicodeTree = unicodeTree;
        this.lock = lock;
        this.host = host;
        this.menus = new ProjectMenus(this::menuEntries, actionId -> host.keymap().describeBindings(actionId));
        this.list = Toolkit.list();
        list.elements(rows().toArray(StyledElement<?>[]::new));
        list.id(ID);
        list.addClass("tool-panel");
        list.focusable();
        list.fill();
        list.scrollbar(ScrollBarPolicy.AS_NEEDED);
        list.autoScroll();
        list.selected(0);
    }

    boolean unicodeTree() {
        return unicodeTree;
    }

    void useUnicodeTree(boolean unicode) {
        this.unicodeTree = unicode;
        if (controller.fileCount() > 0) {
            long kinds = controller.javaKindVersion();
            list.elements(rows().toArray(StyledElement<?>[]::new));
            pushedRevision = controller.revision();
            pushedJavaKinds = kinds;
            pushedUnicode = unicode;
        }
    }

    IconMode iconMode() {
        return fileIcons.mode();
    }

    /** The header row of the pane, which the shell places above the list. */
    ProjectHeader header(Supplier<ShellThemes> themes, Supplier<String> focusMark) {
        return new ProjectHeader(this, themes, focusMark);
    }

    /** The READ_FILE footer row under the list, which the shell places below it. */
    PaneHeader footer(Supplier<ShellThemes> themes, ReadFileSummary readFile) {
        return new PaneHeader("project-read-file", context -> false, readFile::title, themes, () -> " ", false);
    }

    /** Loads the project files in the background and refreshes the tree once they are listed. */
    void discoverFiles() {
        lock.lock();
        try {
            if (controller.filesLoading()) {
                host.refreshTree();
            }
        } catch (Exception unavailable) {
            controller.markUnavailable(unavailable.getMessage());
        } finally {
            lock.unlock();
        }
    }

    void headerRendered(Rect row, List<ProjectHeader.Target> targets) {
        headerRow = row;
        headerTargets = List.copyOf(targets);
    }

    @Override
    public void render(Frame frame, Rect area, RenderContext context) {
        lock.lock();
        try {
            if (context instanceof DefaultRenderContext drc) {
                drc.registerElement(this, area);
            }
            if (context instanceof DefaultRenderContext drc && host.pendingFocus()) {
                drc.focusManager().setFocus(ID);
                host.clearPendingFocus();
            }
            controller.trackScroll(area.height());
            viewColumns = viewWidth(area);
            controller.trackColumn(selectedLabelWidth(), viewColumns, selectedNameWidth());
            pushSelection();
            list.render(frame, area, context);
            if (!headerRow.isEmpty() && menus.isOpen()) {
                menus.render(frame, new Rect(headerRow.x(), headerRow.y(), headerRow.width(),
                        area.y() + area.height() - headerRow.y()));
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
        return list.preferredSize(availableWidth, availableHeight, context);
    }

    @Override
    public EventResult handleKeyEvent(KeyEvent event, boolean focused) {
        lock.lock();
        try {
            host.releaseSplitterDrag();
            if (focused && handleKey(event)) {
                return EventResult.HANDLED;
            }
            pushSelection();
            EventResult result = list.handleKeyEvent(event, focused);
            pullSelection();
            return result;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public EventResult handleMouseEvent(MouseEvent event) {
        lock.lock();
        try {
            if (host.splitterMouse(event) == EventResult.HANDLED) {
                return EventResult.HANDLED;
            }
            pushSelection();
            EventResult result = list.handleMouseEvent(event);
            pullSelection();
            return result;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String id() {
        return list.id();
    }

    @Override
    public boolean isFocusable() {
        return list.isFocusable();
    }

    @Override
    public Rect renderedArea() {
        return list.renderedArea();
    }

    @Override
    public Constraint constraint() {
        return list.constraint();
    }

    /**
     * Routes a left press on the project pane. A press on an open header menu
     * picks its entry; a press outside it closes the menu and goes on. A press on
     * a header button runs the action of its shortcut, and a press elsewhere on
     * the header row only focuses the panel. A press on the project list selects
     * the row and focuses the panel; a second press on the same row within the
     * double click window, or a press on the expand marker of a directory, does
     * what Enter does. A press below the last row or on the scrollbar column only
     * focuses the panel. While a new name or a rename is being typed, or a delete
     * waits for confirmation, the press changes nothing.
     *
     * @return true when the press landed on the project pane and was consumed
     */
    boolean clicked(MouseEvent mouse) {
        if (!host.layoutVisible()) {
            return false;
        }
        if (menus.isOpen()) {
            if (menus.contains(mouse.x(), mouse.y())) {
                menus.click(mouse.x(), mouse.y()).ifPresent(this::runAction);
                return true;
            }
            menus.close();
        }
        Optional<ProjectHeader.Target> target = headerTargetAt(mouse);
        if (target.isPresent()) {
            if (!controller.inputActive() && !controller.deletePending()) {
                host.focusPanel();
                runAction(target.get().actionId());
            }
            return true;
        }
        if (contains(headerRow, mouse)) {
            host.focusPanel();
            return true;
        }
        Rect area = list.renderedArea();
        if (!contains(area, mouse)) {
            return false;
        }
        if (controller.inputActive()) {
            controller.refreshPrompt();
            return true;
        }
        if (controller.deletePending()) {
            return true;
        }
        host.focusPanel();
        int index = controller.top() + mouse.y() - area.y();
        boolean scrollbarColumn = controller.fileCount() > area.height() && mouse.x() >= area.right() - 1;
        if (index < 0 || index >= controller.fileCount() || scrollbarColumn) {
            controller.resetDoubleClick();
            return true;
        }
        controller.setSelection(index);
        controller.syncSelection();
        boolean doubled = controller.registerClick(index);
        ProjectNode node = controller.selectedNode();
        if (doubled || (node != null && onExpandMarker(node, mouse.x(), area))) {
            openSelected();
        }
        return true;
    }

    /**
     * Selects the tree row under a right click and opens its context menu there: Show Diff for a file
     * with a version control change, then Rename and Delete. A press anywhere else is not consumed.
     *
     * @return true when the press landed on a tree row
     */
    boolean rightClicked(MouseEvent mouse) {
        if (!host.layoutVisible() || controller.inputActive() || controller.deletePending()) {
            return false;
        }
        Rect area = list.renderedArea();
        if (!contains(area, mouse)) {
            return false;
        }
        int index = controller.top() + mouse.y() - area.y();
        if (index < 0 || index >= controller.fileCount()) {
            return false;
        }
        host.focusPanel();
        controller.setSelection(index);
        controller.syncSelection();
        controller.resetDoubleClick();
        menus.openAt(ProjectMenus.CONTEXT, mouse.x(), mouse.y());
        return true;
    }

    /**
     * Handles the project keys: the name prompt, the delete confirmation and the
     * project shortcuts. Returns false when the key belongs to the shell or the list.
     */
    boolean handleKey(KeyEvent key) {
        if (menus.isOpen()) {
            menus.handleKey(key).ifPresent(this::runAction);
            return true;
        }
        if (controller.inputActive()) {
            if (Keys.is(key, KeyCode.ESCAPE)) {
                controller.cancelInput();
            } else if (Keys.is(key, KeyCode.ENTER)) {
                controller.submitInput();
            } else if (Keys.is(key, KeyCode.BACKSPACE) && !controller.inputText().isEmpty()) {
                controller.deleteInputCharacter();
            } else if (key.isTyping()) {
                controller.appendInput(key.codePoint());
            }
            return true;
        }
        if (controller.deletePending()) {
            if (key.isCharIgnoreCase('y')) {
                controller.confirmDelete();
            } else if (key.isCharIgnoreCase('n') || Keys.is(key, KeyCode.ESCAPE)) {
                controller.cancelDelete();
            }
            return true;
        }
        if (Keys.is(key, KeyCode.RIGHT) && controller.selectedDirectory()) {
            controller.expandSelected();
            return true;
        }
        if (Keys.is(key, KeyCode.LEFT) && controller.selectedDirectory()) {
            controller.collapseSelected();
            return true;
        }
        if (host.shortcutsBlocked()) {
            return false;
        }
        Keymap keymap = host.keymap();
        KeyContext context = host.keyContext();
        if (keymap.matches(key, KeyActions.PROJECT_NEW_FILE, context)) {
            host.beginInput(ProjectTreeController.InputMode.CREATE_FILE);
        } else if (keymap.matches(key, KeyActions.PROJECT_NEW_DIRECTORY, context)) {
            host.beginInput(ProjectTreeController.InputMode.CREATE_DIRECTORY);
        } else if (keymap.matches(key, KeyActions.PROJECT_RENAME)) {
            host.beginInput(ProjectTreeController.InputMode.RENAME);
        } else if (keymap.matches(key, KeyActions.PROJECT_DELETE)) {
            controller.requestDelete();
        } else if (keymap.matches(key, KeyActions.PROJECT_REFRESH)) {
            host.refreshTree();
        } else {
            Optional<String> headerAction = HEADER_ACTIONS.stream().filter(id -> keymap.matches(key, id)).findFirst();
            if (headerAction.isEmpty()) {
                return false;
            }
            runAction(headerAction.get());
        }
        return true;
    }

    /**
     * Runs a header action for the shell dispatcher, which reaches it from the
     * command palette and the keymap. Returns false for every other action.
     */
    boolean performAction(String actionId) {
        if (!HEADER_ACTIONS.contains(actionId)) {
            return false;
        }
        runAction(actionId);
        return true;
    }

    /**
     * Runs a project tree command of the global keymap: the new and rename
     * prompts, the delete request, the refresh and the selection moves. The
     * overlays close before the command, as every shell command does. Returns
     * false for every other action.
     */
    boolean handle(String actionId, Runnable closeOverlays) {
        return switch (actionId) {
            case KeyActions.PROJECT_NEW_FILE -> {
                closeOverlays.run();
                host.beginInput(ProjectTreeController.InputMode.CREATE_FILE);
                yield true;
            }
            case KeyActions.PROJECT_NEW_DIRECTORY -> {
                closeOverlays.run();
                host.beginInput(ProjectTreeController.InputMode.CREATE_DIRECTORY);
                yield true;
            }
            case KeyActions.PROJECT_RENAME -> {
                closeOverlays.run();
                host.beginInput(ProjectTreeController.InputMode.RENAME);
                yield true;
            }
            case KeyActions.PROJECT_DELETE -> {
                closeOverlays.run();
                controller.requestDelete();
                yield true;
            }
            case KeyActions.PROJECT_REFRESH -> {
                closeOverlays.run();
                host.refreshTree();
                yield true;
            }
            case KeyActions.ACTIVATE_PROJECT -> {
                closeOverlays.run();
                host.requestFocus();
                host.focusPanel();
                yield true;
            }
            case KeyActions.MOVE_UP -> {
                closeOverlays.run();
                controller.moveSelection(-1);
                yield true;
            }
            case KeyActions.MOVE_DOWN -> {
                closeOverlays.run();
                controller.moveSelection(1);
                yield true;
            }
            default -> false;
        };
    }

    /**
     * The one path a header button, its shortcut and its menu entry take. A
     * menu opens below its button; the entries run the same action ids as the
     * New, Hide and tree shortcuts.
     */
    void runAction(String actionId) {
        switch (actionId) {
            case KeyActions.PROJECT_VIEW_MENU -> openMenu(ProjectMenus.VIEW, actionId);
            case KeyActions.PROJECT_NEW_MENU -> openMenu(ProjectMenus.NEW, actionId);
            case KeyActions.PROJECT_OPTIONS_MENU -> openMenu(ProjectMenus.OPTIONS, actionId);
            case KeyActions.PROJECT_VIEW_PROJECT -> menus.close();
            case KeyActions.PROJECT_NEW_FILE -> host.beginInput(ProjectTreeController.InputMode.CREATE_FILE);
            case KeyActions.PROJECT_NEW_DIRECTORY -> host.beginInput(ProjectTreeController.InputMode.CREATE_DIRECTORY);
            case KeyActions.PROJECT_SELECT_OPENED_FILE -> host.revealActiveFile();
            case KeyActions.PROJECT_EXPAND_ALL -> controller.expandAll();
            case KeyActions.PROJECT_COLLAPSE_ALL -> controller.collapseAll();
            case KeyActions.PROJECT_SHOW_HIDDEN -> {
                controller.setShowHidden(!controller.showHidden());
                host.refreshTree();
            }
            case KeyActions.PROJECT_HIDE -> host.togglePanel();
            case KeyActions.PROJECT_RENAME -> host.beginInput(ProjectTreeController.InputMode.RENAME);
            case KeyActions.PROJECT_DELETE -> controller.requestDelete();
            case KeyActions.VCS_SHOW_DIFF -> changedSelection().ifPresent(host::showDiff);
            default -> {
            }
        }
    }

    private void openMenu(String menu, String actionId) {
        host.focusPanel();
        menus.open(menu, anchorOf(actionId));
    }

    private int anchorOf(String actionId) {
        return headerTargets.stream()
                .filter(target -> target.actionId().equals(actionId))
                .findFirst()
                .map(target -> target.cell().x())
                .orElse(headerRow.isEmpty() ? list.renderedArea().x() : headerRow.x());
    }

    private Optional<ProjectHeader.Target> headerTargetAt(MouseEvent mouse) {
        return headerTargets.stream().filter(target -> contains(target.cell(), mouse)).findFirst();
    }

    private List<MenuEntry> menuEntries() {
        return List.of(
                new MenuEntry(ProjectMenus.VIEW, KeyActions.PROJECT_VIEW_PROJECT, check(true) + " Project"),
                new MenuEntry(ProjectMenus.NEW, KeyActions.PROJECT_NEW_FILE, "New File"),
                new MenuEntry(ProjectMenus.NEW, KeyActions.PROJECT_NEW_DIRECTORY, "New Directory"),
                new MenuEntry(ProjectMenus.OPTIONS, KeyActions.PROJECT_SHOW_HIDDEN,
                        check(controller.showHidden()) + " Show Hidden Files"),
                new MenuEntry(ProjectMenus.CONTEXT, KeyActions.VCS_SHOW_DIFF, "Show Diff",
                        changedSelection().isPresent() ? "" : "Not a changed file"),
                new MenuEntry(ProjectMenus.CONTEXT, KeyActions.PROJECT_RENAME, "Rename"),
                new MenuEntry(ProjectMenus.CONTEXT, KeyActions.PROJECT_DELETE, "Delete"));
    }

    private Optional<Path> changedSelection() {
        return controller.selectedRelative()
                .filter(relative -> controller.vcsState(relative) != null)
                .flatMap(relative -> controller.selectedPath())
                .filter(Files::isRegularFile);
    }

    private String check(boolean checked) {
        if (!checked) {
            return "  ";
        }
        return unicodeTree ? "✓" : "*";
    }

    private void openSelected() {
        controller.activateSelected().ifPresent(host::openFile);
    }

    private void pushSelection() {
        boolean clipped = controller.selectedClipped();
        if (pushedRevision != controller.revision() || pushedUnicode != unicodeTree
                || pushedLeft != controller.leftColumn() || pushedJavaKinds != controller.javaKindVersion()
                || pushedView != viewColumns || pushedClipped != clipped
                || (clipped && pushedSelected != controller.selected())
                || (clipped && pushedOffset != controller.marqueeOffset())) {
            long kinds = controller.javaKindVersion();
            list.elements(rows().toArray(StyledElement<?>[]::new));
            pushedRevision = controller.revision();
            pushedJavaKinds = kinds;
            pushedUnicode = unicodeTree;
            pushedLeft = controller.leftColumn();
            pushedView = viewColumns;
            pushedClipped = clipped;
            pushedSelected = controller.selected();
            pushedOffset = controller.marqueeOffset();
        }
        if (list.selected() != controller.selected()) {
            list.selected(controller.selected());
        }
    }

    private void pullSelection() {
        int listed = list.selected();
        if (listed != controller.selected()) {
            controller.setSelection(listed);
            controller.syncSelection();
        }
    }

    private List<StyledElement<?>> rows() {
        List<String> files = controller.files();
        if (files.isEmpty()) {
            return List.of(Toolkit.text(controller.emptyLabel()));
        }
        int left = controller.leftColumn();
        List<ProjectNode> nodes = controller.visibleNodes();
        if (nodes.isEmpty()) {
            return files.stream().<StyledElement<?>>map(file -> Toolkit.text(dropColumns(vcsDecorated(file, file), left)))
                    .toList();
        }
        boolean clipped = controller.selectedClipped();
        int selectedIndex = controller.selected();
        List<StyledElement<?>> labels = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            ProjectNode node = nodes.get(index);
            Label label = dropColumns(nodeLabel(node), left);
            if (clipped && index == selectedIndex) {
                label = controller.marqueeEnabled() ? scrolledLabel(node, label)
                        : label.withText(clipEnd(label.text(), viewColumns));
            }
            labels.add(nodeRow(node, label));
        }
        return labels;
    }

    private StyledElement<?> nodeRow(ProjectNode node, Label label) {
        String suffix = node.dependency && !node.scope.isBlank() ? dependencySuffix(node) : "";
        int suffixStart = label.text().endsWith(suffix) ? label.text().length() - suffix.length()
                : label.text().length();
        return new ProjectRowElement(spans(label, suffixStart));
    }

    private Label nodeLabel(ProjectNode node) {
        String head = " ".repeat(node.depth() * INDENT_WIDTH)
                + (node.directory ? disclosureGlyph(node) + " " : vcsSlot(node.relative.toString()));
        FileIcon icon = node.directory ? fileIcons.folderIconFor(node.name)
                : fileIcons.iconFor(node.name, controller.javaKind(node));
        StringBuilder label = new StringBuilder(head);
        label.append(icon.symbol(fileIcons.mode())).append(' ').append(node.compactName());
        if (node.dependency && !node.scope.isBlank()) {
            label.append(dependencySuffix(node));
        } else if (node.externalLibraries && !node.status.isBlank()) {
            label.append("  ").append(node.status);
        }
        return new Label(label.toString(), CharWidth.of(head), icon.span(fileIcons.mode()));
    }

    private static List<Span> spans(Label label, int suffixStart) {
        String text = label.text();
        List<Span> spans = new ArrayList<>();
        int iconStart = iconStart(label);
        if (iconStart < 0) {
            addRange(spans, text, 0, suffixStart, Style.EMPTY);
            addRange(spans, text, suffixStart, text.length(), DIM);
            return spans;
        }
        int iconEnd = iconStart + label.icon().content().length();
        int dimStart = Math.max(iconEnd, suffixStart);
        addRange(spans, text, 0, iconStart, Style.EMPTY);
        spans.add(label.icon());
        addRange(spans, text, iconEnd, dimStart, Style.EMPTY);
        addRange(spans, text, dimStart, text.length(), DIM);
        return spans;
    }

    private static void addRange(List<Span> spans, String text, int from, int to, Style style) {
        if (from < to) {
            spans.add(Span.styled(text.substring(from, to), style));
        }
    }

    /**
     * The char index where the icon symbol starts in the shown text, or -1 when
     * the icon is cut away or no longer sits at its column.
     */
    private static int iconStart(Label label) {
        if (label.iconColumn() < 0) {
            return -1;
        }
        int start = indexAtColumn(label.text(), label.iconColumn());
        return start >= 0 && label.text().startsWith(label.icon().content(), start) ? start : -1;
    }

    private static int indexAtColumn(String text, int column) {
        int cells = 0;
        int index = 0;
        while (index < text.length() && cells < column) {
            int codePoint = text.codePointAt(index);
            cells += CharWidth.of(codePoint);
            index += Character.charCount(codePoint);
        }
        return cells == column ? index : -1;
    }

    private static String dependencySuffix(ProjectNode node) {
        return "  (" + (node.optional ? "optional, " : "") + node.scope + ")";
    }

    private Label scrolledLabel(ProjectNode node, Label label) {
        String name = node.compactName();
        String text = label.text();
        String prefix = text.substring(0, text.length() - name.length());
        return label.withText(prefix
                + Marquee.window(name, controller.marqueeOffset(), viewColumns - CharWidth.of(prefix)));
    }

    private int selectedLabelWidth() {
        ProjectNode node = controller.selectedNode();
        return node == null ? 0 : CharWidth.of(nodeLabel(node).text());
    }

    private int selectedNameWidth() {
        ProjectNode node = controller.selectedNode();
        return node == null ? 0 : CharWidth.of(node.compactName());
    }

    private int viewWidth(Rect area) {
        boolean scrollbar = controller.fileCount() > area.height();
        return area.width() - HIGHLIGHT_WIDTH - (scrollbar ? 1 : 0);
    }

    private static Label dropColumns(Label label, int columns) {
        int iconColumn = label.iconColumn() >= columns ? label.iconColumn() - columns : -1;
        return new Label(dropColumns(label.text(), columns), iconColumn, label.icon());
    }

    private static String dropColumns(String label, int columns) {
        int skipped = 0;
        int index = 0;
        while (index < label.length() && skipped < columns) {
            int codePoint = label.codePointAt(index);
            index += Character.charCount(codePoint);
            skipped += CharWidth.of(codePoint);
        }
        String rest = label.substring(index);
        return skipped > columns ? " ".repeat(skipped - columns) + rest : rest;
    }

    static String clipEnd(String label, int columns) {
        if (CharWidth.of(label) <= columns) {
            return label;
        }
        int budget = columns - CharWidth.of(ELLIPSIS);
        int kept = 0;
        int index = 0;
        while (index < label.length()) {
            int codePoint = label.codePointAt(index);
            int width = CharWidth.of(codePoint);
            if (kept + width > budget) {
                break;
            }
            kept += width;
            index += Character.charCount(codePoint);
        }
        return label.substring(0, index) + ELLIPSIS;
    }

    private String vcsSlot(String relative) {
        VersionControlCapability.State state = controller.vcsState(relative);
        return state == null ? "  " : VcsMarkers.of(state) + " ";
    }

    private String vcsDecorated(String relative, String label) {
        VersionControlCapability.State state = controller.vcsState(relative);
        return state == null ? label : VcsMarkers.of(state) + " " + label;
    }

    /**
     * The column span of the disclosure glyph of a directory row. The row is drawn
     * after the highlight symbol, with two columns per row above it and then the
     * glyph slot, cut by the horizontal offset, so the geometry here follows that
     * label layout.
     */
    private boolean onExpandMarker(ProjectNode node, int x, Rect area) {
        if (!node.directory) {
            return false;
        }
        int start = area.x() + HIGHLIGHT_WIDTH + node.depth() * INDENT_WIDTH - controller.leftColumn();
        return x >= Math.max(start, area.x() + HIGHLIGHT_WIDTH) && x < start + MARKER_WIDTH;
    }

    private static boolean contains(Rect area, MouseEvent mouse) {
        return mouse.x() >= area.x() && mouse.x() < area.x() + area.width()
                && mouse.y() >= area.y() && mouse.y() < area.y() + area.height();
    }

    private String disclosureGlyph(ProjectNode node) {
        if (fileIcons.mode() == IconMode.NERD) {
            return node.expanded ? "" : "";
        }
        if (unicodeTree) {
            return node.expanded ? "▾" : "▸";
        }
        return node.expanded ? "-" : "+";
    }
}
