package dev.omatheusmesmo.vintelli.tui.menu;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** Immediate-mode menu popup. Its returned action ids use the shell's normal dispatcher. */
public final class MenuPopup {
    private final List<String> menus;
    private final Supplier<List<MenuEntry>> entries;
    private final Function<String, String> shortcuts;
    private int menuIndex;
    private int selected;
    private int firstVisible;
    private boolean open;
    private Rect inner = new Rect(0, 0, 0, 0);
    private List<Integer> renderedEntries = List.of();
    private int renderedLineCount;

    public MenuPopup(List<String> menus, Supplier<List<MenuEntry>> entries,
            Function<String, String> shortcuts) {
        this.menus = List.copyOf(menus);
        if (menus.isEmpty()) {
            throw new IllegalArgumentException("A menu bar requires at least one menu");
        }
        this.entries = entries;
        this.shortcuts = shortcuts;
    }

    public void open(int index) {
        invalidateHitbox();
        menuIndex = Math.clamp(index, 0, menus.size() - 1);
        selected = 0;
        open = true;
    }

    public void close() {
        open = false;
        invalidateHitbox();
    }

    private void invalidateHitbox() {
        inner = new Rect(0, 0, 0, 0);
        firstVisible = 0;
        renderedEntries = List.of();
        renderedLineCount = 0;
    }

    public boolean isOpen() {
        return open;
    }

    public int menuIndex() {
        return menuIndex;
    }

    public List<MenuEntry> visibleEntries() {
        return entries.get().stream().filter(entry -> entry.menu().equals(menus.get(menuIndex))).toList();
    }

    public String selectedReason() {
        List<MenuEntry> rows = visibleEntries();
        return rows.isEmpty() ? "" : rows.get(Math.min(selected, rows.size() - 1)).unavailableReason();
    }

    public Optional<String> handleKey(KeyEvent key) {
        if (!open) {
            return Optional.empty();
        }
        List<MenuEntry> rows = visibleEntries();
        selected = Math.clamp(selected, 0, Math.max(0, rows.size() - 1));
        if (key.isKey(KeyCode.ESCAPE) || key.isKey(KeyCode.F10)) {
            close();
        } else if (key.isKey(KeyCode.LEFT)) {
            open(Math.floorMod(menuIndex - 1, menus.size()));
        } else if (key.isKey(KeyCode.RIGHT)) {
            open((menuIndex + 1) % menus.size());
        } else if (key.isKey(KeyCode.UP)) {
            selected = Math.max(0, selected - 1);
        } else if (key.isKey(KeyCode.DOWN)) {
            selected = Math.min(Math.max(0, rows.size() - 1), selected + 1);
        } else if (key.isKey(KeyCode.PAGE_DOWN)) {
            scrollBy(Math.max(1, inner.height() - 1));
        } else if (key.isKey(KeyCode.PAGE_UP)) {
            scrollBy(-Math.max(1, inner.height() - 1));
        } else if (key.isKey(KeyCode.ENTER)) {
            return confirm(rows);
        }
        return Optional.empty();
    }

    private void scrollBy(int amount) {
        if (inner.height() == 0) {
            return;
        }
        firstVisible = Math.clamp(firstVisible + amount, 0, Math.max(0, renderedLineCount - inner.height()));
    }

    /** Coordinates are terminal cells from the last render, including the area's origin. */
    public Optional<String> click(int x, int y) {
        if (!open || inner.width() == 0 || inner.height() == 0) {
            return Optional.empty();
        }
        if (x < inner.x() || x >= inner.x() + inner.width()
                || y < inner.y() || y >= inner.y() + inner.height()) {
            close();
            return Optional.empty();
        }
        int row = y - inner.y();
        if (row < renderedEntries.size()) {
            selected = renderedEntries.get(row);
            List<MenuEntry> rows = visibleEntries();
            return confirm(rows);
        }
        return Optional.empty();
    }

    private Optional<String> confirm(List<MenuEntry> rows) {
        if (rows.isEmpty() || !rows.get(selected).enabled()) {
            return Optional.empty();
        }
        String action = rows.get(selected).actionId();
        close();
        return Optional.of(action);
    }

    public void render(Frame frame, Rect area) {
        if (!open) {
            return;
        }
        if (area.width() < 2 || area.height() < 3) {
            invalidateHitbox();
            return;
        }
        List<MenuEntry> rows = visibleEntries();
        selected = Math.clamp(selected, 0, Math.max(0, rows.size() - 1));
        int desiredWidth = rows.stream().mapToInt(entry -> entry.label().length()
                + shortcuts.apply(entry.actionId()).length() + 7).max().orElse(24);
        int width = Math.min(area.width(), Math.min(60, Math.max(24, desiredWidth)));
        int offset = 0;
        for (int index = 0; index < menuIndex; index++) {
            offset += menus.get(index).length() + 2;
        }
        int estimatedInnerWidth = Math.max(1, width - 2);
        List<PopupLine> content = popupLines(rows, estimatedInnerWidth);
        Rect panel = new Rect(area.x() + Math.min(offset, area.width() - width), area.y() + 1,
                width, Math.min(area.height() - 1, Math.max(3, content.size() + 2)));
        frame.renderWidget(Clear.INSTANCE, panel);
        Block block = Block.builder().borders(Borders.ALL).build();
        frame.renderWidget(block, panel);
        inner = block.inner(panel);
        content = popupLines(rows, inner.width());
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
        firstVisible = Math.clamp(firstVisible, 0, Math.max(0, content.size() - inner.height()));
        if (selectedStart >= 0 && selectedStart >= firstVisible + inner.height()) {
            firstVisible = selectedStart;
        } else if (selectedEnd >= 0 && selectedEnd < firstVisible) {
            firstVisible = selectedEnd - inner.height() + 1;
        }
        List<Line> lines = new ArrayList<>();
        renderedEntries = new ArrayList<>();
        for (int index = firstVisible; index < content.size() && lines.size() < inner.height(); index++) {
            PopupLine popupLine = content.get(index);
            Style style = Style.create();
            if (popupLine.entryIndex() >= 0) {
                MenuEntry entry = rows.get(popupLine.entryIndex());
                if (popupLine.entryIndex() == selected) {
                    style = style.reversed();
                }
                if (!entry.enabled()) {
                    style = style.dim();
                }
            }
            lines.add(Line.from(Span.styled(popupLine.text(), style)));
            renderedEntries.add(popupLine.entryIndex());
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(lines)).build(), inner);
    }

    private List<PopupLine> popupLines(List<MenuEntry> entries, int width) {
        List<PopupLine> lines = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            MenuEntry entry = entries.get(index);
            String label = " " + entry.label() + "  ";
            String shortcut = " " + shortcuts.apply(entry.actionId());
            // Keep shortcut and label together where possible, while reserving room for the hint.
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

    private record PopupLine(int entryIndex, String text) {
    }
}
