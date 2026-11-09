package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.omatheusmesmo.vintelli.tui.DoubleClickTracker;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * IntelliJ-like Find Action overlay: Search Everywhere reachable through the
 * {@code SearchEverywhere} action, the fallback for every chord the terminal
 * cannot deliver, such as Double Shift and Ctrl+Shift+letter. Lists the whole
 * keymap catalog with the active shortcut next to each action, and the real
 * IntelliJ shortcut as a second reference column whenever it differs, filters
 * as the user types, moves with the arrow keys and confirms with Enter; the
 * shell dispatches the picked action.
 *
 * <p>While the palette is open it captures the whole keyboard, bare letters
 * included, so filter text never reaches the keymap; the shell keeps the quit
 * chord and the palette and help toggles for itself.
 */
public final class CommandPalette {

    private static final int MIN_WIDTH = 20;
    private static final int MAX_WIDTH = 72;
    private static final int MIN_HEIGHT = 5;
    private static final Style PROMPT_STYLE = Style.create().bold();
    private static final Style LABEL_STYLE = Style.create();
    private static final Style SHORTCUT_STYLE = Style.create().dim();
    private static final Style HINT_STYLE = Style.create().dim().italic();
    private static final Style SELECTION_STYLE = Style.create().reversed().bold();

    private final Keymap keymap;
    private final List<KeyAction> actions;
    private final List<KeyAction> filtered = new ArrayList<>();
    private String filter = "";
    private int selected;
    private boolean open;
    private Rect renderedPanel = Rect.ZERO;
    private Rect renderedInner = Rect.ZERO;
    private int renderedFirst;
    private int renderedRows;
    private List<KeyAction> renderedActions = List.of();
    private int scrollOffset;
    private final DoubleClickTracker doubleClick = new DoubleClickTracker();

    public CommandPalette(List<KeyAction> actions, Keymap keymap) {
        this.actions = List.copyOf(actions);
        this.keymap = keymap;
    }

    /**
     * Opens the palette with an empty filter, so palette and filter state never
     * survive a round trip.
     */
    public void open() {
        openWith("");
    }

    /**
     * Opens the palette with a starting filter, so a surface such as the
     * toolbar Settings button can open the list already narrowed to one topic.
     */
    public void openWith(String initialFilter) {
        open = true;
        filter = initialFilter;
        selected = 0;
        scrollOffset = 0;
        doubleClick.reset();
        refresh();
    }

    public void close() {
        open = false;
        renderedPanel = Rect.ZERO;
        renderedActions = List.of();
        doubleClick.reset();
    }

    public boolean isOpen() {
        return open;
    }

    /**
     * Consumes the event while the palette is open and returns the action the
     * user confirmed with Enter, if any; every other event is swallowed as
     * filter input, selection movement or an ignored key.
     */
    public Optional<KeyAction> handleKey(KeyEvent key) {
        if (!open) {
            return Optional.empty();
        }
        if (key.isKey(KeyCode.ESCAPE)) {
            close();
            return Optional.empty();
        }
        if (key.isKey(KeyCode.ENTER)) {
            return filtered.isEmpty() ? Optional.empty() : Optional.of(filtered.get(selected));
        }
        if (key.isKey(KeyCode.UP)) {
            selected = Math.max(0, selected - 1);
            keepSelectionVisible();
            return Optional.empty();
        }
        if (key.isKey(KeyCode.DOWN)) {
            selected = Math.min(filtered.isEmpty() ? 0 : filtered.size() - 1, selected + 1);
            keepSelectionVisible();
            return Optional.empty();
        }
        if (isFilterKey(key)) {
            filter += Character.toString(key.codePoint());
        } else if (key.isKey(KeyCode.BACKSPACE)) {
            filter = filter.isEmpty() ? "" : filter.substring(0, filter.length() - 1);
        } else {
            return Optional.empty();
        }
        refresh();
        scrollOffset = 0;
        return Optional.empty();
    }

    private static boolean isFilterKey(KeyEvent key) {
        return key.code() == KeyCode.CHAR && key.codePoint() >= 32 && !key.hasCtrl() && !key.hasAlt();
    }

    /**
     * Case-insensitive substring match over label and action id, so both
     * {@code run} and {@code ActivateRunToolWindow} find the same entry.
     */
    private void refresh() {
        String query = filter.toLowerCase();
        filtered.clear();
        for (KeyAction action : actions) {
            if (action.label().toLowerCase().contains(query) || action.id().toLowerCase().contains(query)) {
                filtered.add(action);
            }
        }
        if (selected >= filtered.size()) {
            selected = Math.max(0, filtered.size() - 1);
        }
        keepSelectionVisible();
    }

    /**
     * Renders the palette as a centered overlay panel on top of whatever the
     * frame holds.
     */
    public void render(Frame frame, Rect area) {
        if (!open) {
            renderedPanel = Rect.ZERO;
            return;
        }
        int height = Math.max(MIN_HEIGHT, filtered.size() + 4);
        Rect panel = OverlayPanel.centered(area, MIN_WIDTH, MAX_WIDTH, height);
        Rect inner = OverlayPanel.renderChrome(frame, panel, "Search Everywhere");
        renderedPanel = panel;
        renderedInner = inner;
        frame.renderWidget(rowsParagraph(inner), inner);
    }

    private Paragraph rowsParagraph(Rect inner) {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.from(Span.styled("> " + filter, PROMPT_STYLE)));
        if (filtered.isEmpty()) {
            lines.add(Line.from(Span.styled("no matching action", SHORTCUT_STYLE)));
        } else {
            int visibleRows = Math.max(0, inner.height() - 1);
            int firstVisible = Math.clamp(scrollOffset, 0, Math.max(0, filtered.size() - visibleRows));
            if (selected < firstVisible) {
                firstVisible = selected;
            } else if (visibleRows > 0 && selected >= firstVisible + visibleRows) {
                firstVisible = selected - visibleRows + 1;
            }
            scrollOffset = firstVisible;
            renderedFirst = firstVisible;
            renderedRows = 0;
            List<KeyAction> visibleActions = new ArrayList<>();
            for (int visible = 0; visible < visibleRows; visible++) {
                int index = firstVisible + visible;
                if (index >= filtered.size()) {
                    break;
                }
                lines.add(actionRow(filtered.get(index), index == selected, inner.width()));
                visibleActions.add(filtered.get(index));
                renderedRows++;
            }
            renderedActions = List.copyOf(visibleActions);
        }
        if (filtered.isEmpty()) {
            renderedFirst = 0;
            renderedRows = 0;
            renderedActions = List.of();
        }
        return Paragraph.builder().text(Text.from(lines)).build();
    }

    /** Handles pointer input for the visible palette; a second click activates a row. */
    public Optional<KeyAction> handleMouse(MouseEvent event) {
        if (!open) {
            return Optional.empty();
        }
        if (event.isClick() && !inside(renderedPanel, event.x(), event.y())) {
            close();
            return Optional.empty();
        }
        if (!inside(renderedPanel, event.x(), event.y()) || !renderedActions.equals(currentVisibleActions())) {
            return Optional.empty();
        }
        if (event.isScroll() && (event.kind() == MouseEventKind.SCROLL_UP
                || event.kind() == MouseEventKind.SCROLL_DOWN)) {
            doubleClick.reset();
            if (!inside(renderedInner, event.x(), event.y()) || event.y() < renderedInner.y() + 1
                    || renderedRows == 0) {
                return Optional.empty();
            }
            int direction = event.kind() == MouseEventKind.SCROLL_UP ? -1 : 1;
            scrollOffset = Math.clamp(scrollOffset + direction * 3, 0,
                    Math.max(0, filtered.size() - Math.max(0, renderedInner.height() - 1)));
            selected = Math.clamp(selected, scrollOffset,
                    Math.min(filtered.size() - 1, scrollOffset + Math.max(1, renderedInner.height() - 1) - 1));
            return Optional.empty();
        }
        if (!event.isClick() || event.y() < renderedInner.y() + 1
                || !inside(renderedInner, event.x(), event.y())) {
            if (event.isClick()) {
                doubleClick.reset();
            }
            return Optional.empty();
        }
        int row = event.y() - renderedInner.y() - 1;
        if (row < 0 || row >= renderedRows || event.x() < renderedInner.x()
                || event.x() >= renderedInner.x() + renderedInner.width()) {
            doubleClick.reset();
            return Optional.empty();
        }
        int index = renderedFirst + row;
        selected = index;
        return doubleClick.register(index, System.nanoTime()) ? Optional.of(filtered.get(index)) : Optional.empty();
    }

    private List<KeyAction> currentVisibleActions() {
        int end = Math.min(filtered.size(), renderedFirst + renderedRows);
        return renderedFirst < 0 || renderedFirst >= end ? List.of()
                : List.copyOf(filtered.subList(renderedFirst, end));
    }

    private void keepSelectionVisible() {
        int visibleRows = Math.max(0, renderedInner.height() - 1);
        if (visibleRows == 0) {
            return;
        }
        if (selected < scrollOffset) {
            scrollOffset = selected;
        } else if (selected >= scrollOffset + visibleRows) {
            scrollOffset = selected - visibleRows + 1;
        }
    }

    private static boolean inside(Rect rect, int x, int y) {
        return rect.width() > 0 && rect.height() > 0 && x >= rect.x() && x < rect.x() + rect.width()
                && y >= rect.y() && y < rect.y() + rect.height();
    }

    private Line actionRow(KeyAction action, boolean selectedRow, int width) {
        String shortcut = keymap.describeBindings(action.id());
        boolean withHint = !action.intellijShortcut().isEmpty() && !action.intellijShortcut().equals(shortcut);
        String hint = withHint ? "(IntelliJ: " + action.intellijShortcut() + ")" : "";
        String label = OverlayPanel.truncate(action.label(), width - shortcut.length() - hint.length() - 2);
        String filler = " ".repeat(Math.max(1, width - label.length() - shortcut.length() - hint.length() - 2));
        Style style = selectedRow ? SELECTION_STYLE : LABEL_STYLE;
        Style cushion = selectedRow ? style : SHORTCUT_STYLE;
        List<Span> spans = new ArrayList<>();
        spans.add(Span.styled(" " + label, style));
        spans.add(Span.styled(filler, cushion));
        spans.add(Span.styled(shortcut, selectedRow ? style : SHORTCUT_STYLE));
        if (withHint) {
            spans.add(Span.styled(" " + hint, selectedRow ? style : HINT_STYLE));
        }
        return Line.from(spans);
    }
}
