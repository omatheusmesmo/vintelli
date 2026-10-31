package dev.omatheusmesmo.vintelli.tui.layout;

/** Ratios are independent of terminal size; cell dimensions preserve usable adjoining panels. */
public final class ShellLayout {
    public static final double DEFAULT_PROJECT_RATIO = 34.0 / 120;
    public static final double DEFAULT_BOTTOM_RATIO = 9.0 / 40;
    private double projectRatio;
    private double bottomRatio;

    public ShellLayout() {
        this(DEFAULT_PROJECT_RATIO, DEFAULT_BOTTOM_RATIO);
    }

    public ShellLayout(double projectRatio, double bottomRatio) {
        this.projectRatio = validRatio(projectRatio, DEFAULT_PROJECT_RATIO);
        this.bottomRatio = validRatio(bottomRatio, DEFAULT_BOTTOM_RATIO);
    }

    public double projectRatio() {
        return projectRatio;
    }

    public double bottomRatio() {
        return bottomRatio;
    }

    public int projectWidth(int availableWidth) {
        return cells(projectRatio, availableWidth, 12, 16);
    }

    public int bottomHeight(int availableHeight) {
        return cells(bottomRatio, availableHeight, 3, 4);
    }

    public void resizeProject(int delta, int availableWidth) {
        projectWidth(projectWidth(availableWidth) + delta, availableWidth);
    }

    public void resizeBottom(int delta, int availableHeight) {
        bottomHeight(bottomHeight(availableHeight) + delta, availableHeight);
    }

    /** Set the left panel size from a splitter drag, measured relative to the main area's left edge. */
    public void projectWidth(int requestedCells, int availableWidth) {
        if (availableWidth > 0) {
            projectRatio = (double) clampCells(requestedCells, availableWidth, 12, 16) / availableWidth;
        }
    }

    /** Set the bottom panel size from a splitter drag, measured up from the content area's bottom. */
    public void bottomHeight(int requestedCells, int availableHeight) {
        if (availableHeight > 0) {
            bottomRatio = (double) clampCells(requestedCells, availableHeight, 3, 4) / availableHeight;
        }
    }

    private static double validRatio(double value, double fallback) {
        return Double.isFinite(value) && value > 0 && value < 1 ? value : fallback;
    }

    private static int cells(double ratio, int available, int minimum, int remaining) {
        return clampCells((int) Math.round(ratio * Math.max(0, available)), available, minimum, remaining);
    }

    private static int clampCells(int value, int available, int minimum, int remaining) {
        int size = Math.max(0, available);
        if (size < minimum + remaining) {
            return Math.clamp(value, 0, Math.max(0, size - 1));
        }
        return Math.clamp(value, minimum, size - remaining);
    }
}
