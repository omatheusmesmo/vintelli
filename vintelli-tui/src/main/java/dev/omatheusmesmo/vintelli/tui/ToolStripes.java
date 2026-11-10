package dev.omatheusmesmo.vintelli.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.tamboui.text.CharWidth;

/**
 * Owns the left and right icon strips: the icons with their glyph and active
 * state, the click on an icon and the hit test of a click. The shell places the
 * elements {@link #leftElement()} and {@link #rightElement()} return in the
 * layout, and asks {@link #iconAt} which icon a click landed on.
 */
final class ToolStripes {

    static final String PROJECT_STRIP_ID = "project";
    static final String PROJECT_GLYPH = "";

    /** What the strips read from the shell and what a click on an icon changes there. */
    interface Host {

        boolean projectVisible();

        boolean windowVisible(String id);

        ToolWindow activeWindow(ToolWindow.Anchor anchor);

        void toggleProjectPanel();

        void switchTo(ToolWindow window);

        void toggleWindow(String id);
    }

    private final Map<ToolWindow.Anchor, List<ToolWindow>> windowsByAnchor;
    private final IconMode mode;
    private final Host host;
    private volatile ToolStripElement leftStrip;
    private volatile ToolStripElement rightStrip;

    ToolStripes(Map<ToolWindow.Anchor, List<ToolWindow>> windowsByAnchor, IconMode mode, Host host) {
        this.windowsByAnchor = windowsByAnchor;
        this.mode = mode;
        this.host = host;
    }

    ToolStripElement leftElement() {
        leftStrip = new ToolStripElement("tool-bar-left", this::leftIcons);
        return leftStrip;
    }

    /** The right strip, or null when no tool window is anchored right. */
    ToolStripElement rightElement() {
        if (windowsByAnchor.getOrDefault(ToolWindow.Anchor.RIGHT, List.of()).isEmpty()) {
            rightStrip = null;
            return null;
        }
        rightStrip = new ToolStripElement("tool-bar-right", this::rightIcons);
        return rightStrip;
    }

    /** The icon id under a click, looked up in the left strip first and then in the right one. */
    Optional<String> iconAt(int x, int y) {
        ToolStripElement left = leftStrip;
        Optional<String> icon = left == null ? Optional.empty() : left.iconAt(x, y);
        ToolStripElement right = rightStrip;
        if (icon.isEmpty() && right != null) {
            icon = right.iconAt(x, y);
        }
        return icon;
    }

    /**
     * A click on a strip icon. The project icon toggles the project panel. A
     * hidden window, or a visible one that is not active, is activated. The
     * active visible window is hidden, so the next click shows it again.
     */
    void click(String id) {
        if (PROJECT_STRIP_ID.equals(id)) {
            host.toggleProjectPanel();
            return;
        }
        ToolWindow window = windowsByAnchor.values().stream()
                .flatMap(List::stream)
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElse(null);
        if (window == null) {
            return;
        }
        if (!host.windowVisible(id) || window != host.activeWindow(window.anchor())) {
            host.switchTo(window);
        } else {
            host.toggleWindow(id);
        }
    }

    private String projectGlyph() {
        return mode == IconMode.ASCII ? "P" : PROJECT_GLYPH;
    }

    List<ToolStripElement.Icon> leftIcons() {
        List<ToolStripElement.Icon> icons = new ArrayList<>();
        icons.add(new ToolStripElement.Icon(PROJECT_STRIP_ID, projectGlyph(), host.projectVisible(), false));
        for (ToolWindow window : windowsByAnchor.getOrDefault(ToolWindow.Anchor.LEFT, List.of())) {
            icons.add(icon(window, false));
        }
        for (ToolWindow window : windowsByAnchor.getOrDefault(ToolWindow.Anchor.BOTTOM, List.of())) {
            icons.add(icon(window, true));
        }
        return icons;
    }

    List<ToolStripElement.Icon> rightIcons() {
        List<ToolStripElement.Icon> icons = new ArrayList<>();
        for (ToolWindow window : windowsByAnchor.getOrDefault(ToolWindow.Anchor.RIGHT, List.of())) {
            icons.add(icon(window, false));
        }
        return icons;
    }

    private ToolStripElement.Icon icon(ToolWindow window, boolean bottomAnchored) {
        boolean active = window == host.activeWindow(window.anchor());
        return new ToolStripElement.Icon(window.id(), glyph(window, mode), active, bottomAnchored);
    }

    /**
     * The one cell glyph of a window: its own icon when the face is not ASCII
     * and the icon is one cell wide, otherwise the upper case first letter of
     * its title.
     */
    static String glyph(ToolWindow window, IconMode mode) {
        return nerdIcon(window, mode).orElseGet(() -> titleLetter(window));
    }

    /** The window icon when the face draws icons and the icon is one cell wide. */
    static Optional<String> nerdIcon(ToolWindow window, IconMode mode) {
        String icon = window.icon();
        if (mode != IconMode.ASCII && !icon.isEmpty() && CharWidth.of(icon) == 1) {
            return Optional.of(icon);
        }
        return Optional.empty();
    }

    private static String titleLetter(ToolWindow window) {
        String title = window.title();
        return title.isEmpty() ? "?" : title.substring(0, 1).toUpperCase(Locale.ROOT);
    }
}
