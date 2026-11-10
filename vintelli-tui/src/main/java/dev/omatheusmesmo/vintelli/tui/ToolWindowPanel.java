package dev.omatheusmesmo.vintelli.tui;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.tamboui.layout.Rect;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.toolkit.elements.TextElement;

/**
 * Draws the tool window stripes: the tab row of each anchor, the active window
 * under it and the empty bottom state. The window choices come from the
 * {@link ToolWindowHost}; this class only builds elements from them and keeps
 * the tab bars and the rendered window areas that clicks are answered from.
 * The tab bars and the areas are written while the frame is built and rendered,
 * which is the render pass the shell runs under its frame lock.
 */
final class ToolWindowPanel {

    private static final String EMPTY_TOOL_WINDOWS_TEXT = "no tool window beans registered; implement ToolWindow and expose the bean";

    private final ToolWindowHost host;
    private final IconMode mode;
    private final Supplier<ShellThemes> themes;
    private final Supplier<String> focusMark;
    private final ToolWindowElement.Host elements;
    private final Map<ToolWindow.Anchor, ToolTabBarElement> tabBars = new ConcurrentHashMap<>();
    private final Map<ToolWindow.Anchor, Rect> activeAreas = new ConcurrentHashMap<>();

    ToolWindowPanel(ToolWindowHost host, IconMode mode, Supplier<ShellThemes> themes, Supplier<String> focusMark,
            ToolWindowElement.Host elements) {
        this.host = host;
        this.mode = mode;
        this.themes = themes;
        this.focusMark = focusMark;
        this.elements = elements;
    }

    Element stripe(ToolWindow.Anchor anchor) {
        List<ToolWindow> windows = host.visibleWindows(anchor);
        if (windows.isEmpty()) {
            tabBars.remove(anchor);
            return Toolkit.text("").length(0);
        }
        List<ToolTabBarElement.Label> labels = windows.stream()
                .map(window -> new ToolTabBarElement.Label(tabMarker(window),
                        anchor == ToolWindow.Anchor.RIGHT ? compactRightTabTitle(window) : window.title()))
                .toList();
        int clampedTab = Math.min(Math.max(host.activeIndex(anchor), 0), windows.size() - 1);
        ToolTabBarElement tabs = new ToolTabBarElement(tabBarId(anchor), labels, clampedTab, tabGlyphs());
        tabBars.put(anchor, tabs);

        Column column = Toolkit.column().id("tool-stripe-" + anchor.name().toLowerCase(Locale.ROOT));
        column.add(tabs);
        column.add(Toolkit.lazy(() -> activeWindowElement(anchor)));
        column.fill();
        return column;
    }

    Element bottomStripe() {
        if (!host.hasWindows(ToolWindow.Anchor.BOTTOM)) {
            TextElement empty = Toolkit.text(EMPTY_TOOL_WINDOWS_TEXT);
            empty.id("tool-windows-empty");
            empty.length(ToolWindowHost.TOOL_WINDOW_HEIGHT + 1);
            return empty;
        }
        Column column = Toolkit.column().id("bottom-stripe");
        column.add(stripe(ToolWindow.Anchor.BOTTOM));
        column.fill();
        return column;
    }

    /** The tab under a click, looked up in every tab bar that is drawn. */
    Optional<ToolWindow> tabAt(int x, int y) {
        for (Map.Entry<ToolWindow.Anchor, ToolTabBarElement> entry : tabBars.entrySet()) {
            int index = entry.getValue().tabAt(x, y);
            List<ToolWindow> windows = host.visibleWindows(entry.getKey());
            if (index >= 0 && index < windows.size()) {
                return Optional.of(windows.get(index));
            }
        }
        return Optional.empty();
    }

    Rect activeArea(ToolWindow.Anchor anchor) {
        return activeAreas.getOrDefault(anchor, Rect.ZERO);
    }

    /**
     * Remembers where the active window of a bottom or right stripe was drawn,
     * so pointer events inside that area reach the window. Other windows and
     * the left anchor are not clickable through this area.
     */
    void rendered(ToolWindow window, Rect area) {
        ToolWindow.Anchor anchor = window.anchor();
        if ((anchor == ToolWindow.Anchor.BOTTOM || anchor == ToolWindow.Anchor.RIGHT)
                && window == host.activeWindow(anchor)) {
            activeAreas.put(anchor, area);
        }
    }

    private Element activeWindowElement(ToolWindow.Anchor anchor) {
        List<ToolWindow> windows = host.visibleWindows(anchor);
        if (windows.isEmpty()) {
            return Toolkit.text("").length(0);
        }
        int clamped = Math.min(host.activeIndex(anchor), windows.size() - 1);
        if (clamped < 0) {
            clamped = 0;
        }
        ToolWindow window = windows.get(clamped);
        Column pane = Toolkit.column().id("tool-pane-" + window.id());
        pane.add(new PaneHeader("tool-" + window.id(), context -> host.isFocused(window.id()), window::title,
                () -> themes.get(), focusMark));
        pane.add(new ToolWindowElement(window, -1, elements));
        return anchor == ToolWindow.Anchor.BOTTOM ? pane.length(ToolWindowHost.TOOL_WINDOW_HEIGHT) : pane.fill();
    }

    private static String tabBarId(ToolWindow.Anchor anchor) {
        return "tool-tabs-" + anchor.name().toLowerCase(Locale.ROOT);
    }

    private String tabMarker(ToolWindow window) {
        return ToolStripes.nerdIcon(window, mode).orElseGet(() -> String.valueOf(window.shortcut()));
    }

    private ToolTabBarElement.Glyphs tabGlyphs() {
        return mode == IconMode.ASCII
                ? new ToolTabBarElement.Glyphs("-", "<", ">")
                : new ToolTabBarElement.Glyphs("━", "«", "»");
    }

    private static String compactRightTabTitle(ToolWindow window) {
        return switch (window.id()) {
            case "acp.agents" -> "ACP";
            case "structure" -> "Struct";
            default -> window.title();
        };
    }
}
