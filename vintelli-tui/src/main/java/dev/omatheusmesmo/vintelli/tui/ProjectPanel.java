package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.tui.icons.FileIcons;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.widgets.common.ScrollBarPolicy;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

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

    interface Host {

        Keymap keymap();

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
    }

    private final ProjectTreeController controller;
    private final FileIcons fileIcons;
    private final ReentrantLock lock;
    private final Host host;
    private final ListElement<?> list;
    private boolean unicodeTree;
    private long pushedRevision = -1;
    private boolean pushedUnicode;
    private int pushedLeft;

    ProjectPanel(ProjectTreeController controller, FileIcons fileIcons, boolean unicodeTree, ReentrantLock lock,
            Host host) {
        this.controller = controller;
        this.fileIcons = fileIcons;
        this.unicodeTree = unicodeTree;
        this.lock = lock;
        this.host = host;
        this.list = Toolkit.list(rows());
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
            list.items(rows());
            pushedRevision = controller.revision();
            pushedUnicode = unicode;
        }
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
            controller.trackColumn(selectedLabelWidth(), viewWidth(area));
            pushSelection();
            list.render(frame, area, context);
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
     * Routes a left press on the project list. A press on a row selects it and
     * focuses the panel; a second press on the same row within the double click
     * window, or a press on the expand marker of a directory, does what Enter
     * does. A press below the last row or on the scrollbar column only focuses
     * the panel. While a new name or a rename is being typed, or a delete waits
     * for confirmation, the press changes nothing.
     *
     * @return true when the press landed on the project list and was consumed
     */
    boolean clicked(MouseEvent mouse) {
        Rect area = list.renderedArea();
        if (!host.layoutVisible() || !contains(area, mouse)) {
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
     * Handles the project keys: the name prompt, the delete confirmation and the
     * project shortcuts. Returns false when the key belongs to the shell or the list.
     */
    boolean handleKey(KeyEvent key) {
        if (controller.inputActive()) {
            if (key.isKey(KeyCode.ESCAPE)) {
                controller.cancelInput();
            } else if (key.isKey(KeyCode.ENTER)) {
                controller.submitInput();
            } else if (key.isKey(KeyCode.BACKSPACE) && !controller.inputText().isEmpty()) {
                controller.deleteInputCharacter();
            } else if (key.code() == KeyCode.CHAR && key.codePoint() >= 32 && !key.hasCtrl() && !key.hasAlt()) {
                controller.appendInput(key.codePoint());
            }
            return true;
        }
        if (controller.deletePending()) {
            if (key.isCharIgnoreCase('y')) {
                controller.confirmDelete();
            } else if (key.isCharIgnoreCase('n') || key.isKey(KeyCode.ESCAPE)) {
                controller.cancelDelete();
            }
            return true;
        }
        if (host.shortcutsBlocked()) {
            return false;
        }
        Keymap keymap = host.keymap();
        if (keymap.matches(key, KeyActions.PROJECT_NEW_FILE)) {
            host.beginInput(ProjectTreeController.InputMode.CREATE_FILE);
        } else if (keymap.matches(key, KeyActions.PROJECT_NEW_DIRECTORY)) {
            host.beginInput(ProjectTreeController.InputMode.CREATE_DIRECTORY);
        } else if (keymap.matches(key, KeyActions.PROJECT_RENAME)) {
            host.beginInput(ProjectTreeController.InputMode.RENAME);
        } else if (keymap.matches(key, KeyActions.PROJECT_DELETE)) {
            controller.requestDelete();
        } else if (keymap.matches(key, KeyActions.PROJECT_REFRESH)) {
            host.refreshTree();
        } else {
            return false;
        }
        return true;
    }

    private void openSelected() {
        controller.activateSelected().ifPresent(host::openFile);
    }

    private void pushSelection() {
        if (pushedRevision != controller.revision() || pushedUnicode != unicodeTree
                || pushedLeft != controller.leftColumn()) {
            list.items(rows());
            pushedRevision = controller.revision();
            pushedUnicode = unicodeTree;
            pushedLeft = controller.leftColumn();
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

    private List<String> rows() {
        List<String> files = controller.files();
        if (files.isEmpty()) {
            return List.of(controller.emptyLabel());
        }
        int left = controller.leftColumn();
        List<ProjectNode> nodes = controller.visibleNodes();
        if (nodes.isEmpty()) {
            return files.stream().map(file -> dropColumns(vcsDecorated(file, file), left)).toList();
        }
        return nodes.stream().map(node -> dropColumns(nodeLabel(node), left)).toList();
    }

    private String nodeLabel(ProjectNode node) {
        StringBuilder label = new StringBuilder(" ".repeat(node.depth() * INDENT_WIDTH));
        if (node.directory) {
            label.append(disclosureGlyph(node)).append(' ');
        } else {
            label.append(vcsSlot(node.relative.toString()));
        }
        return label.append((node.directory ? fileIcons.folderIconFor(node.name) : fileIcons.iconFor(node.name))
                .symbol(fileIcons.mode())).append(' ').append(node.compactName()).toString();
    }

    private int selectedLabelWidth() {
        ProjectNode node = controller.selectedNode();
        return node == null ? 0 : CharWidth.of(nodeLabel(node));
    }

    private int viewWidth(Rect area) {
        boolean scrollbar = controller.fileCount() > area.height();
        return area.width() - HIGHLIGHT_WIDTH - (scrollbar ? 1 : 0);
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

    private String vcsSlot(String relative) {
        VersionControlCapability.State state = controller.vcsState(relative);
        return state == null ? "  " : vcsMarker(state) + " ";
    }

    private String vcsDecorated(String relative, String label) {
        VersionControlCapability.State state = controller.vcsState(relative);
        return state == null ? label : vcsMarker(state) + " " + label;
    }

    private static String vcsMarker(VersionControlCapability.State state) {
        return switch (state) {
            case ADDED -> "A";
            case DELETED -> "D";
            case MODIFIED -> "M";
            case UNTRACKED -> "?";
            case UNMERGED -> "!";
        };
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
