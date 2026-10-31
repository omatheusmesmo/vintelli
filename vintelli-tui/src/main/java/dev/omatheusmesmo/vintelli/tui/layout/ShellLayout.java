package dev.omatheusmesmo.vintelli.tui.layout;

import java.util.LinkedHashSet;
import java.util.Set;

/** Ratios are independent of terminal size; cell dimensions preserve usable adjoining panels. */
public final class ShellLayout {
    public static final double DEFAULT_PROJECT_RATIO = 34.0 / 120;
    public static final double DEFAULT_BOTTOM_RATIO = 9.0 / 40;
    private double projectRatio;
    private double bottomRatio;
    private final Set<String> allWindows;
    private final Set<String> visibleWindows;

    public ShellLayout() {
        this(DEFAULT_PROJECT_RATIO, DEFAULT_BOTTOM_RATIO, null, null);
    }

    public ShellLayout(double projectRatio, double bottomRatio) {
        this(projectRatio, bottomRatio, null, null);
    }

    public ShellLayout(double projectRatio, double bottomRatio, Set<String> visibleWindows) {
        this(projectRatio, bottomRatio, visibleWindows, null);
    }

    public ShellLayout(double projectRatio, double bottomRatio, Set<String> visibleWindows, Set<String> allWindows) {
        this.projectRatio = validRatio(projectRatio, DEFAULT_PROJECT_RATIO);
        this.bottomRatio = validRatio(bottomRatio, DEFAULT_BOTTOM_RATIO);
        this.allWindows = allWindows == null ? Set.of() : Set.copyOf(allWindows);
        if (visibleWindows == null) {
            this.visibleWindows = this.allWindows.isEmpty() ? new LinkedHashSet<>() : new LinkedHashSet<>(this.allWindows);
        } else {
            this.visibleWindows = new LinkedHashSet<>(visibleWindows);
        }
    }

    public double projectRatio() {
        return projectRatio;
    }

    public double bottomRatio() {
        return bottomRatio;
    }

    public Set<String> visibleWindows() {
        return Set.copyOf(visibleWindows);
    }

    public boolean isVisible(String windowId) {
        if (allWindows.isEmpty()) {
            return visibleWindows.isEmpty() || visibleWindows.contains(windowId);
        }
        return visibleWindows.contains(windowId);
    }

    public void setVisible(String windowId, boolean visible) {
        if (visible) {
            visibleWindows.add(windowId);
        } else {
            visibleWindows.remove(windowId);
        }
    }

    public void toggle(String windowId) {
        if (visibleWindows.contains(windowId)) {
            visibleWindows.remove(windowId);
        } else {
            visibleWindows.add(windowId);
        }
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
