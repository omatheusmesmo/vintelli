package dev.omatheusmesmo.vintelli.tui.menu;

import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;
import dev.tamboui.text.CharWidth;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * State and rules of the menu popup: the open menu, the selected entry, the
 * wrapped lines and the scroll window. It holds no TamboUI type. The placement
 * is computed by {@link #place}, which only the render pass calls; it records
 * the hit box that the pointer rules read.
 */
final class MenuController {

    /** One visible line of the panel; {@code entryIndex} is -1 for the empty-menu notice. */
    record Row(int entryIndex, String text, boolean highlighted, boolean dimmed) {
    }

    record Placement(OverlayRegion panel, OverlayRegion inner, List<Row> rows) {
    }

    private record PopupLine(int entryIndex, String text) {
    }

    private final List<String> menus;
    private final Supplier<List<MenuEntry>> entries;
    private final Function<String, String> shortcuts;
    private int menuIndex;
    private int selected;
    private int firstVisible;
    private boolean open;
    private OverlayRegion inner = OverlayRegion.EMPTY;
    private List<Integer> renderedEntries = List.of();
    private int renderedLineCount;

    MenuController(List<String> menus, Supplier<List<MenuEntry>> entries, Function<String, String> shortcuts) {
        this.menus = List.copyOf(menus);
        if (menus.isEmpty()) {
            throw new IllegalArgumentException("A menu bar requires at least one menu");
        }
        this.entries = entries;
        this.shortcuts = shortcuts;
    }

    static int labelOffset(List<String> menus, int index, int originX) {
        int offset = originX;
        for (int menu = 0; menu < index; menu++) {
            offset += labelWidth(menus.get(menu));
        }
        return offset;
    }

    static int labelWidth(String menu) {
        return CharWidth.of(menu) + 2;
    }

    void open(int index) {
        open(index, null);
    }

    void open(int index, String actionId) {
        invalidateHitbox();
        menuIndex = Math.clamp(index, 0, menus.size() - 1);
        selected = 0;
        open = true;
        List<MenuEntry> choices = visibleEntries();
        for (int choice = 0; choice < choices.size(); choice++) {
            if (choices.get(choice).actionId().equals(actionId)) selected = choice;
        }
    }

    void close() {
        open = false;
        invalidateHitbox();
    }

    boolean isOpen() {
        return open;
    }

    int menuIndex() {
        return menuIndex;
    }

    List<MenuEntry> visibleEntries() {
        return entries.get().stream().filter(entry -> entry.menu().equals(menus.get(menuIndex))).toList();
    }

    String selectedReason() {
        List<MenuEntry> rows = visibleEntries();
        return rows.isEmpty() ? "" : rows.get(Math.min(selected, rows.size() - 1)).unavailableReason();
    }

    Optional<String> press(OverlayKey key) {
        if (!open) {
            return Optional.empty();
        }
        List<MenuEntry> rows = visibleEntries();
        selected = Math.clamp(selected, 0, Math.max(0, rows.size() - 1));
        switch (key) {
            case ESCAPE, F10 -> close();
            case LEFT -> open(Math.floorMod(menuIndex - 1, menus.size()));
            case RIGHT -> open((menuIndex + 1) % menus.size());
            case UP -> selected = Math.max(0, selected - 1);
            case DOWN -> selected = Math.min(Math.max(0, rows.size() - 1), selected + 1);
            case PAGE_DOWN -> scrollBy(Math.max(1, inner.height() - 1));
            case PAGE_UP -> scrollBy(-Math.max(1, inner.height() - 1));
            case ENTER -> {
                return confirm(rows);
            }
            default -> {
            }
        }
        return Optional.empty();
    }

    /** Coordinates are terminal cells from the last render, including the area's origin. */
    Optional<String> click(int x, int y) {
        if (!open || inner.width() == 0 || inner.height() == 0) {
            return Optional.empty();
        }
        if (!inner.contains(x, y)) {
            close();
            return Optional.empty();
        }
        int row = y - inner.y();
        if (row < renderedEntries.size()) {
            selected = renderedEntries.get(row);
            return confirm(visibleEntries());
        }
        return Optional.empty();
    }

    boolean contains(int x, int y) {
        return open && inner.contains(x, y);
    }

    /**
     * Places the panel below the first row of {@code area}, starting at column
     * {@code panelOrigin}, and returns the lines that fit. An empty result means
     * the area is too small or the menu is closed, and the hit box is cleared.
     */
    Optional<Placement> place(OverlayRegion area, int panelOrigin) {
        if (!open) {
            return Optional.empty();
        }
        if (area.width() < 2 || area.height() < 3) {
            invalidateHitbox();
            return Optional.empty();
        }
        List<MenuEntry> rows = visibleEntries();
        selected = Math.clamp(selected, 0, Math.max(0, rows.size() - 1));
        int desiredWidth = rows.stream().mapToInt(entry -> entry.label().length()
                + shortcuts.apply(entry.actionId()).length() + 7).max().orElse(24);
        int width = Math.min(area.width(), Math.min(60, Math.max(24, desiredWidth)));
        int offset = labelOffset(menus, menuIndex, panelOrigin);
        int estimatedInnerWidth = Math.max(1, width - 2);
        List<PopupLine> estimated = popupLines(rows, estimatedInnerWidth);
        OverlayRegion panel = new OverlayRegion(area.x() + Math.min(offset, area.width() - width), area.y() + 1,
                width, Math.min(area.height() - 1, Math.max(3, estimated.size() + 2)));
        OverlayRegion innerRegion = panel.inset(1);
        List<PopupLine> content = popupLines(rows, innerRegion.width());
        renderedLineCount = content.size();
        int selectedStart = -1;
        int selectedEnd = -1;
        for (int index = 0; index < content.size(); index++) {
            if (content.get(index).entryIndex() == selected) {
                if (selectedStart < 0) {
                    selectedStart = index;
                }
                selectedEnd = index;
            }
        }
        firstVisible = Math.clamp(firstVisible, 0, Math.max(0, content.size() - innerRegion.height()));
        if (selectedStart >= 0 && selectedStart >= firstVisible + innerRegion.height()) {
            firstVisible = selectedStart;
        } else if (selectedEnd >= 0 && selectedEnd < firstVisible) {
            firstVisible = selectedEnd - innerRegion.height() + 1;
        }
        inner = innerRegion;
        renderedEntries = new ArrayList<>();
        List<Row> visible = new ArrayList<>();
        for (int index = firstVisible; index < content.size() && visible.size() < innerRegion.height(); index++) {
            PopupLine line = content.get(index);
            boolean highlighted = line.entryIndex() >= 0 && line.entryIndex() == selected;
            boolean dimmed = line.entryIndex() >= 0 && !rows.get(line.entryIndex()).enabled();
            visible.add(new Row(line.entryIndex(), line.text(), highlighted, dimmed));
            renderedEntries.add(line.entryIndex());
        }
        return Optional.of(new Placement(panel, innerRegion, visible));
    }

    private void invalidateHitbox() {
        inner = OverlayRegion.EMPTY;
        firstVisible = 0;
        renderedEntries = List.of();
        renderedLineCount = 0;
    }

    private void scrollBy(int amount) {
        if (inner.height() == 0) {
            return;
        }
        firstVisible = Math.clamp(firstVisible + amount, 0, Math.max(0, renderedLineCount - inner.height()));
    }

    private Optional<String> confirm(List<MenuEntry> rows) {
        if (rows.isEmpty() || !rows.get(selected).enabled()) {
            return Optional.empty();
        }
        String action = rows.get(selected).actionId();
        close();
        return Optional.of(action);
    }

    private List<PopupLine> popupLines(List<MenuEntry> entries, int width) {
        List<PopupLine> lines = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            MenuEntry entry = entries.get(index);
            String label = " " + entry.label() + "  ";
            String shortcut = " " + shortcuts.apply(entry.actionId());
            String title = label + shortcut;
            lines.add(new PopupLine(index, clip(title, width)));
            if (!entry.enabled() && !entry.unavailableReason().isBlank()) {
                for (String wrapped : wrap(entry.unavailableReason(), Math.max(1, width - 2))) {
                    lines.add(new PopupLine(index, clip("  " + wrapped, width)));
                }
            }
        }
        if (entries.isEmpty()) {
            lines.add(new PopupLine(-1, "No actions available"));
        }
        return lines;
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            while (word.length() > width) {
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                lines.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static String clip(String text, int width) {
        return text.length() <= width ? text : text.substring(0, width);
    }
}
