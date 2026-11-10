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
 */
final class PaneLayoutController {

    static final int PROJECT_PANEL_WIDTH = 30;
    static final int SIDE_PANEL_WIDTH = 20;
    static final int ACP_SIDE_PANEL_WIDTH = 32;
    static final int TOOL_STRIP_WIDTH = ToolStripElement.WIDTH;
    static final int PANE_SEPARATOR_WIDTH = 1;

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

    PaneLayoutController(ShellLayout layout, ShellLayoutStore store, Host host) {
        this.layout = layout;
        this.store = store;
        this.host = host;
    }

    boolean isSizeCustomized() {
        return layout.isSizeCustomized();
    }

    int leftRegionWidth(int projectWidth) {
        return TOOL_STRIP_WIDTH + (layout.isProjectVisible() ? projectWidth + PANE_SEPARATOR_WIDTH : 0);
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

    int availableHeight() {
        Area viewport = host.viewport();
        if (viewport.height() > 1) {
            return viewport.height() - 1;
        }
        return host.centerHeight() + ToolWindowHost.BOTTOM_HEIGHT;
    }

    int sizedProjectWidth() {
        int base = projectBase();
        return Math.min(layout.projectWidth(base), Math.max(0, projectCap(base)));
    }

    void resizeProject(int delta) {
        if (projectBase() <= 0 || !host.layoutReady()) {
            host.status("Project layout is not ready");
            return;
        }
        if (!layout.isProjectVisible()) {
            host.status("Project panel is hidden");
            return;
        }
        adoptDefaultSizes();
        setProjectWidth(host.projectPanelWidth() + delta);
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
        } else if (layout.isProjectVisible() && x == vertical.x() && y >= vertical.y() && y <= bottom) {
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
     * Turns the fixed default sizes into ratios just before the first resize, so
     * resizing one pane leaves the other at the size it has on screen.
     */
    private void adoptDefaultSizes() {
        if (!layout.isSizeCustomized()) {
            layout.projectWidth(PROJECT_PANEL_WIDTH, projectBase());
            layout.bottomHeight(host.defaultBottomHeight(), availableHeight());
        }
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
