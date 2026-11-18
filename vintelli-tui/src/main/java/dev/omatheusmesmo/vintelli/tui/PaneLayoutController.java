package dev.omatheusmesmo.vintelli.tui;

import java.io.IOException;

import dev.omatheusmesmo.vintelli.tui.layout.ShellLayout;
import dev.omatheusmesmo.vintelli.tui.layout.ShellLayoutStore;

/**
 * Owns the size of the project pane and of the bottom tool windows, their
 * defaults and caps, the persisted layout, and the splitter drag. It holds no
 * TamboUI type: the shell hands in the rendered areas and the visible windows
 * as plain values, and the element sizes go back out through {@link Host}.
 * The shell keeps arranging the elements, so the placement is the shell's
 * answer to {@link Host#placeProject(int)} and {@link Host#placeBottom(int)}.
 *
 * <p>A default-sized pane follows the viewport: the project pane shrinks and
 * hides, and the bottom pane shrinks and hides, only while the editor would be
 * too small. The decision is made at render time from the viewport and is never
 * stored, so the panes come back when the terminal grows. A customized size and
 * a pane the user showed explicitly keep their place at the minimum they allow.
 */
final class PaneLayoutController {

    static final int PROJECT_PANEL_WIDTH = 30;
    static final int SIDE_PANEL_WIDTH = 20;
    static final int ACP_SIDE_PANEL_WIDTH = 32;
    static final int TOOL_STRIP_WIDTH = ToolStripElement.WIDTH;
    static final int PANE_SEPARATOR_WIDTH = 1;
    static final int DEFAULT_EDITOR_WIDTH = 40;

    /** A rectangle of terminal cells, free of any TamboUI type. */
    record Area(int x, int y, int width, int height) {
    }

    /** What the controller reads from the shell and what it hands back to it. */
    interface Host {

        Area viewport();

        Area verticalSeparator();

        Area horizontalSeparator();

        int projectPanelWidth();

        int centerWidth();

        int centerHeight();

        boolean layoutReady();

        boolean bottomWindowsHidden();

        int defaultBottomHeight();

        int bottomRows(int availableHeight);

        default boolean bottomRevealed() {
            return false;
        }

        boolean leftWindowsOpen();

        boolean rightRegistered();

        boolean rightVisible();

        boolean activeRightIsAgents();

        boolean overlayOpen();

        void placeProject(int leftRegionWidth);

        void placeBottom(int rows);

        void status(String message);
    }

    private enum SplitterDrag { PROJECT, BOTTOM }

    private final ShellLayout layout;
    private final ShellLayoutStore store;
    private final Host host;
    private SplitterDrag splitter;
    private boolean dragChanged;
    private boolean projectRevealed;

    PaneLayoutController(ShellLayout layout, ShellLayoutStore store, Host host) {
        this.layout = layout;
        this.store = store;
        this.host = host;
    }

    /**
     * The project width a default-sized project pane takes in a viewport of the
     * given width: the fixed width while the editor keeps
     * {@link #DEFAULT_EDITOR_WIDTH} columns beside it, shrunk to leave the editor
     * that many, and never below {@link ShellLayout#MIN_PROJECT_WIDTH}. An unknown
     * viewport keeps the fixed width.
     */
    static int defaultProjectWidth(int viewportWidth, int rightStripeWidth) {
        if (viewportWidth <= 0) {
            return PROJECT_PANEL_WIDTH;
        }
        int projectAndEditor = projectAndEditorColumns(viewportWidth, rightStripeWidth);
        return Math.max(ShellLayout.MIN_PROJECT_WIDTH,
                Math.min(PROJECT_PANEL_WIDTH, projectAndEditor - DEFAULT_EDITOR_WIDTH));
    }

    /**
     * Whether a default-sized project pane fits: even at its minimum width it
     * must leave the editor {@link ShellLayout#MIN_EDITOR_WIDTH} columns. A pane
     * that does not fit is not shown by default, and the left tool strip stays.
     */
    static boolean defaultProjectFits(int viewportWidth, int rightStripeWidth) {
        if (viewportWidth <= 0) {
            return true;
        }
        int editorAtMinimum = projectAndEditorColumns(viewportWidth, rightStripeWidth) - ShellLayout.MIN_PROJECT_WIDTH;
        return editorAtMinimum >= ShellLayout.MIN_EDITOR_WIDTH;
    }

    private static int projectAndEditorColumns(int viewportWidth, int rightStripeWidth) {
        return viewportWidth - TOOL_STRIP_WIDTH - PANE_SEPARATOR_WIDTH - rightStripeWidth;
    }

    boolean isSizeCustomized() {
        return layout.isSizeCustomized();
    }

    /**
     * Whether the project pane is drawn. A stored hidden pane stays hidden. A
     * customized size, or a pane shown by an explicit choice, is always drawn;
     * a default-sized pane is drawn only while it fits the viewport.
     */
    boolean projectShown() {
        if (!layout.isProjectVisible()) {
            return false;
        }
        return layout.isSizeCustomized() || projectRevealed || defaultProjectFits(viewportWidth(), rightStripeWidth());
    }

    /** Shows or hides the project pane by an explicit choice, which keeps it shown at a size that does not fit. */
    void setProjectShown(boolean visible) {
        layout.setProjectVisible(visible);
        projectRevealed = visible;
    }

    /** The left column holds the project pane, a left tool window, or both. */
    boolean leftColumnShown() {
        return projectShown() || host.leftWindowsOpen();
    }

    int leftRegionWidth(int projectWidth) {
        return TOOL_STRIP_WIDTH + (leftColumnShown() ? projectWidth + PANE_SEPARATOR_WIDTH : 0);
    }

    /** The width of the project column: the sized width once the user sized it, otherwise the default for the viewport. */
    int projectWidth() {
        if (layout.isSizeCustomized()) {
            return sizedProjectWidth();
        }
        return defaultProjectWidth(viewportWidth(), reservedRightStripeWidth());
    }

    /**
     * The right column a default project width leaves room for: the strip only,
     * so opening a right window does not move a default project pane that still
     * fits. A pane that does not fit beside an open right window is hidden by
     * {@link #projectShown()} instead.
     */
    private int reservedRightStripeWidth() {
        return host.rightRegistered() ? TOOL_STRIP_WIDTH : 0;
    }

    int rightStripeWidth() {
        if (!host.rightRegistered()) {
            return 0;
        }
        int panelWidth = 0;
        if (host.rightVisible()) {
            panelWidth = host.activeRightIsAgents() ? ACP_SIDE_PANEL_WIDTH : SIDE_PANEL_WIDTH;
        }
        return panelWidth + TOOL_STRIP_WIDTH;
    }

    /**
     * The height the bottom pane is measured against: the terminal height less
     * the status row, which runs under the whole shell.
     */
    int availableHeight() {
        Area viewport = host.viewport();
        if (viewport.height() > 1) {
            return viewport.height() - ToolWindowHost.STATUS_ROWS;
        }
        return host.centerHeight() + ToolWindowHost.BOTTOM_HEIGHT;
    }

    int sizedProjectWidth() {
        int base = projectBase();
        int sized = Math.min(layout.projectWidth(base), Math.max(0, projectCap(base)));
        return base > 0 ? Math.max(1, sized) : sized;
    }

    void resizeProject(int delta) {
        if (projectBase() <= 0 || !host.layoutReady()) {
            host.status("Project layout is not ready");
            return;
        }
        if (!leftColumnShown()) {
            host.status("Project panel is hidden");
            return;
        }
        adoptDefaultSizes();
        int current = projectShown() ? host.projectPanelWidth() : sizedProjectWidth();
        setProjectWidth(current + delta);
        host.placeProject(leftRegionWidth(sizedProjectWidth()));
        persist();
        host.status("Project panel: " + sizedProjectWidth() + " columns");
    }

    void resizeBottom(int delta) {
        int available = availableHeight();
        if (available <= 0 || !host.layoutReady()) {
            host.status("Tool window layout is not ready");
            return;
        }
        if (host.bottomWindowsHidden()) {
            host.status("Bottom tool windows are hidden");
            return;
        }
        adoptDefaultSizes();
        layout.bottomHeight(Math.max(0, available - host.centerHeight()), available);
        layout.resizeBottom(delta, available);
        int height = host.bottomRows(available);
        host.placeBottom(height);
        persist();
        host.status("Tool window height: " + height + " rows");
    }

    boolean dragging() {
        return splitter != null;
    }

    /**
     * Starts a splitter drag when the press lands on a splitter. Any drag in
     * progress ends first, so a press always closes the previous gesture.
     */
    boolean press(int x, int y, boolean primary) {
        finish();
        if (!primary || host.overlayOpen()) {
            return false;
        }
        Area vertical = host.verticalSeparator();
        Area horizontal = host.horizontalSeparator();
        Area viewport = host.viewport();
        if (vertical.width() <= 0 || vertical.height() <= 0 || horizontal.width() <= 0 || horizontal.height() <= 0
                || viewport.width() <= 0 || viewport.height() <= 1) {
            return false;
        }
        int right = horizontal.x() + horizontal.width() - 1;
        int bottom = vertical.y() + vertical.height() - 1;
        if (!host.bottomWindowsHidden() && y == horizontal.y() && x >= horizontal.x() && x <= right) {
            splitter = SplitterDrag.BOTTOM;
        } else if (leftColumnShown() && x == vertical.x() && y >= vertical.y() && y <= bottom) {
            splitter = SplitterDrag.PROJECT;
        } else {
            return false;
        }
        dragChanged = false;
        return true;
    }

    /** Resizes the dragged pane to the pointer; returns false when no drag is in progress. */
    boolean drag(int x, int y) {
        if (splitter == null) {
            return false;
        }
        resizeSplitter(x, y);
        return true;
    }

    /** A pointer move or any other press ends a drag that did not get its release. */
    void move() {
        finish();
    }

    /** Ends the drag on release; returns false when no drag was in progress. */
    boolean release() {
        if (splitter == null) {
            return false;
        }
        finish();
        return true;
    }

    /** Ends an in-progress drag when its input surface stops delivering events. */
    void cancel() {
        finish();
    }

    void persist() {
        try {
            store.save(layout);
        } catch (IOException failure) {
            host.status("Cannot save layout: " + failure.getMessage());
        }
    }

    private void resizeSplitter(int x, int y) {
        Area viewport = host.viewport();
        if (x < 0 || y < 0 || viewport.width() <= 0 || viewport.height() <= 1
                || host.centerWidth() <= 0 || host.centerHeight() <= 0 || !host.layoutReady()) {
            return;
        }
        if (splitter == SplitterDrag.PROJECT) {
            if (projectBase() < ShellLayout.MIN_PROJECT_WIDTH + ShellLayout.MIN_EDITOR_WIDTH) {
                return;
            }
            adoptDefaultSizes();
            setProjectWidth(x - viewport.x() - TOOL_STRIP_WIDTH);
            int width = sizedProjectWidth();
            host.placeProject(leftRegionWidth(width));
            dragChanged = true;
            host.status("Project panel: " + width + " columns");
        } else if (splitter == SplitterDrag.BOTTOM) {
            int available = viewport.height() - 1;
            if (available < ShellLayout.MIN_BOTTOM_HEIGHT + ShellLayout.MIN_EDITOR_HEIGHT) {
                return;
            }
            adoptDefaultSizes();
            int mainTop = viewport.y() + 1;
            int editorHeight = y - mainTop + 1;
            layout.bottomHeight(available - editorHeight, available);
            int height = host.bottomRows(available);
            host.placeBottom(height);
            dragChanged = true;
            host.status("Tool window height: " + height + " rows");
        }
    }

    private void finish() {
        if (splitter != null && dragChanged) {
            persist();
        }
        splitter = null;
        dragChanged = false;
    }

    /**
     * Turns the default sizes into ratios just before the first resize, so
     * resizing one pane leaves the other at the size it has on screen. The sizes
     * adopted are the ones drawn now, not the fixed defaults.
     */
    private void adoptDefaultSizes() {
        if (!layout.isSizeCustomized()) {
            layout.projectWidth(projectWidth(), projectBase());
            layout.bottomHeight(drawnDefaultBottomHeight(), availableHeight());
        }
    }

    private int drawnDefaultBottomHeight() {
        int rows = ToolWindowHost.defaultBottomRows(host.defaultBottomHeight() - ToolWindowHost.STATUS_ROWS,
                host.viewport().height(), host.bottomRevealed());
        return rows > 0 ? rows + ToolWindowHost.STATUS_ROWS : host.defaultBottomHeight();
    }

    private int viewportWidth() {
        return host.viewport().width();
    }

    /**
     * The width the project ratio is measured against. It leaves out the left
     * strip only, so opening or closing a right window never moves the project
     * pane; the right region is taken off by {@link #projectCap(int)} instead.
     */
    private int projectBase() {
        Area viewport = host.viewport();
        if (viewport.width() > 0) {
            return viewport.width() - TOOL_STRIP_WIDTH;
        }
        return host.projectPanelWidth() + host.centerWidth();
    }

    /** The widest project pane that still leaves the editor its minimum beside the open right region. */
    private int projectCap(int base) {
        return base - rightStripeWidth() - ShellLayout.MIN_EDITOR_WIDTH - PANE_SEPARATOR_WIDTH;
    }

    private void setProjectWidth(int requestedCells) {
        int base = projectBase();
        layout.projectWidth(Math.min(requestedCells, projectCap(base)), base);
    }
}
