package dev.omatheusmesmo.vintelli.tui;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import dev.omatheusmesmo.vintelli.tui.layout.ShellLayout;

/**
 * Owns which tool windows are visible, which one is active per dock anchor
 * (left, right and bottom), which one holds the focus, and the stored bottom
 * height that counts the status row. The same rules serve every anchor: a
 * window only has to declare its {@link ToolWindow#anchor()}. The shell reads
 * the rules through this class, the {@link ToolWindowPanel} view draws them,
 * and the shell hands the key and mouse routing back through {@link Host}. It
 * holds no TamboUI type, so the rules are testable without a UI.
 */
final class ToolWindowHost {

    static final int TOOL_WINDOW_HEIGHT = 9;
    static final int BOTTOM_HEIGHT = TOOL_WINDOW_HEIGHT + ToolTabBarElement.ROWS + 1;

    private static final int EMPTY_BOTTOM_HEIGHT = TOOL_WINDOW_HEIGHT + 2;
    private static final String EDITOR_FOCUS = "editor-panel";
    private static final String TOOL_FOCUS_PREFIX = "tool-";

    /** What the host asks the shell for: persistence, focus, capture release and the status line. */
    interface Host {

        void persistLayout();

        void moveFocus(String focusId);

        void windowHidden(ToolWindow window);

        void status(String message);
    }

    private final List<ToolWindow> toolWindows;
    private final Map<ToolWindow.Anchor, List<ToolWindow>> windowsByAnchor;
    private final ShellLayout layout;
    private final Host host;
    private final Map<ToolWindow.Anchor, Integer> activeIndexByAnchor = new ConcurrentHashMap<>();
    private volatile String focusedId;

    ToolWindowHost(List<ToolWindow> toolWindows, ShellLayout layout, Host host) {
        this.toolWindows = toolWindows;
        this.layout = layout;
        this.host = host;
        this.windowsByAnchor = toolWindows.stream()
                .collect(Collectors.groupingBy(ToolWindow::anchor, Collectors.toList()));
        for (ToolWindow.Anchor anchor : ToolWindow.Anchor.values()) {
            List<ToolWindow> windows = visibleWindows(anchor);
            if (!windows.isEmpty()) {
                int selected = anchor == ToolWindow.Anchor.RIGHT
                        ? windows.stream().map(ToolWindow::id).toList().indexOf("structure")
                        : -1;
                activeIndexByAnchor.put(anchor, selected < 0 ? 0 : selected);
            }
        }
    }

    Map<ToolWindow.Anchor, List<ToolWindow>> windowsByAnchor() {
        return windowsByAnchor;
    }

    List<ToolWindow> registered(ToolWindow.Anchor anchor) {
        return windowsByAnchor.getOrDefault(anchor, List.of());
    }

    boolean hasWindows(ToolWindow.Anchor anchor) {
        return !registered(anchor).isEmpty();
    }

    List<ToolWindow> visibleWindows(ToolWindow.Anchor anchor) {
        return registered(anchor).stream()
                .filter(window -> layout.isVisible(window.id()))
                .toList();
    }

    ToolWindow activeWindow(ToolWindow.Anchor anchor) {
        List<ToolWindow> visible = visibleWindows(anchor);
        int index = activeIndex(anchor);
        return index >= 0 && index < visible.size() ? visible.get(index) : null;
    }

    int activeIndex(ToolWindow.Anchor anchor) {
        return activeIndexByAnchor.getOrDefault(anchor, 0);
    }

    boolean isVisible(String id) {
        return layout.isVisible(id);
    }

    boolean isFocused(String id) {
        return id.equals(focusedId);
    }

    boolean isActive(ToolWindow window) {
        return window == activeWindow(window.anchor()) && isFocused(window.id());
    }

    void focusWindow(ToolWindow window) {
        focusedId = window.id();
        host.moveFocus(TOOL_FOCUS_PREFIX + window.id());
    }

    void clearFocus() {
        focusedId = null;
    }

    /**
     * Makes the window the active one of its anchor and focuses it. A hidden
     * window is shown first, as the tab row and the strip icons expect.
     */
    void switchTo(ToolWindow window) {
        if (!layout.isVisible(window.id())) {
            show(window.id());
        }
        int anchorIndex = visibleWindows(window.anchor()).indexOf(window);
        if (anchorIndex >= 0) {
            activeIndexByAnchor.put(window.anchor(), anchorIndex);
        }
        focusWindow(window);
    }

    void show(String id) {
        layout.setVisible(id, true);
        host.persistLayout();
        host.status("Tool window shown: " + id);
    }

    void hide(String id) {
        layout.setVisible(id, false);
        host.persistLayout();
        host.status("Tool window hidden: " + id);
    }

    /**
     * Toggles the window and returns the window the shell must switch to when
     * hiding the active one left its anchor with another visible window. An
     * empty result means the shell keeps its state and the status line says
     * whether the window is now shown or hidden.
     */
    Optional<ToolWindow> toggle(String id) {
        boolean wasVisible = layout.isVisible(id);
        layout.toggle(id);
        host.persistLayout();
        if (wasVisible && !layout.isVisible(id)) {
            ToolWindow target = toolWindows.stream().filter(window -> window.id().equals(id)).findFirst().orElse(null);
            if (target != null) {
                releaseHidden(target);
                List<ToolWindow> remaining = visibleWindows(target.anchor());
                if (!remaining.isEmpty() && !remaining.contains(target)) {
                    return Optional.of(remaining.getFirst());
                }
            }
        }
        host.status(layout.isVisible(id) ? "Tool window shown: " + id : "Tool window hidden: " + id);
        return Optional.empty();
    }

    private void releaseHidden(ToolWindow hidden) {
        host.windowHidden(hidden);
        if (hidden.id().equals(focusedId)) {
            focusedId = null;
            host.moveFocus(EDITOR_FOCUS);
        }
    }

    void reset() {
        for (ToolWindow.Anchor anchor : ToolWindow.Anchor.values()) {
            if (hasWindows(anchor)) {
                activeIndexByAnchor.put(anchor, 0);
            }
        }
    }

    boolean bottomWindowsHidden() {
        return hasWindows(ToolWindow.Anchor.BOTTOM) && visibleWindows(ToolWindow.Anchor.BOTTOM).isEmpty();
    }

    int defaultBottomHeight() {
        return hasWindows(ToolWindow.Anchor.BOTTOM) ? BOTTOM_HEIGHT : EMPTY_BOTTOM_HEIGHT;
    }

    /**
     * The rows the bottom pane takes. The stored height counts the status row,
     * which runs under the whole shell, so the pane takes one row less.
     */
    int bottomPaneRows(boolean customized, int availableHeight) {
        if (bottomWindowsHidden()) {
            return 0;
        }
        int stored = customized ? layout.bottomHeight(availableHeight) : defaultBottomHeight();
        return paneRows(stored);
    }

    int bottomRows(int availableHeight) {
        return paneRows(layout.bottomHeight(availableHeight));
    }

    void storeBottomHeight(int requestedCells, int availableHeight) {
        layout.bottomHeight(requestedCells, availableHeight);
    }

    void adoptBottomDefault(int availableHeight) {
        layout.bottomHeight(defaultBottomHeight(), availableHeight);
    }

    void resizeBottom(int delta, int availableHeight) {
        layout.resizeBottom(delta, availableHeight);
    }

    private static int paneRows(int storedHeight) {
        return Math.max(0, storedHeight - 1);
    }
}
