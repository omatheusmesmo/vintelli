package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
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

    public CommandPalette(List<KeyAction> actions, Keymap keymap) {
        this.actions = List.copyOf(actions);
        this.keymap = keymap;
    }

    /**
     * Opens the palette with an empty filter, so palette and filter state never
     * survive a round trip.
     */
    public void open() {
        open = true;
        filter = "";
        selected = 0;
        refresh();
    }

    public void close() {
        open = false;
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
            return Optional.empty();
        }
        if (key.isKey(KeyCode.DOWN)) {
            selected = Math.min(filtered.isEmpty() ? 0 : filtered.size() - 1, selected + 1);
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
    }

    /**
     * Renders the palette as a centered overlay panel on top of whatever the
     * frame holds.
     */
    public void render(Frame frame, Rect area) {
        if (!open) {
            return;
        }
        int height = Math.max(MIN_HEIGHT, filtered.size() + 4);
        Rect panel = OverlayPanel.centered(area, MIN_WIDTH, MAX_WIDTH, height);
        Rect inner = OverlayPanel.renderChrome(frame, panel, "Search Everywhere");
        frame.renderWidget(rowsParagraph(inner), inner);
    }

    private Paragraph rowsParagraph(Rect inner) {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.from(Span.styled("> " + filter, PROMPT_STYLE)));
        if (filtered.isEmpty()) {
            lines.add(Line.from(Span.styled("no matching action", SHORTCUT_STYLE)));
        } else {
            int visibleRows = inner.height() - 1;
            int firstVisible = Math.max(0, selected - (visibleRows - 1));
            for (int visible = 0; visible < visibleRows; visible++) {
                int index = firstVisible + visible;
                if (index >= filtered.size()) {
                    break;
                }
                lines.add(actionRow(filtered.get(index), index == selected, inner.width()));
            }
        }
        return Paragraph.builder().text(Text.from(lines)).build();
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
