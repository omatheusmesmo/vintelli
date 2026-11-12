package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.omatheusmesmo.vintelli.tui.OverlayInput;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * IntelliJ-like Find Action overlay: Search Everywhere reachable through the
 * {@code SearchEverywhere} action, the fallback for every chord the terminal
 * cannot deliver, such as Double Shift and Ctrl+Shift+letter. Lists the whole
 * keymap catalog with the active shortcut next to each action, and the IntelliJ
 * chord in parentheses only while it differs from the active one and a terminal
 * can send it, filters as the user types, moves with the arrow keys and confirms
 * with Enter; the shell dispatches the picked action.
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
    private final CommandPaletteController controller;

    public CommandPalette(List<KeyAction> actions, Keymap keymap) {
        this.controller = new CommandPaletteController(actions);
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
        controller.openWith(initialFilter);
    }

    public void close() {
        controller.close();
    }

    public boolean isOpen() {
        return controller.isOpen();
    }

    /**
     * Consumes the event while the palette is open and returns the action the
     * user confirmed with Enter, if any; every other event is swallowed as
     * filter input, selection movement or an ignored key.
     */
    public Optional<KeyAction> handleKey(KeyEvent key) {
        return controller.press(OverlayInput.key(key), key.codePoint());
    }

    /** Handles pointer input for the visible palette; a second click activates a row. */
    public Optional<KeyAction> handleMouse(MouseEvent event) {
        return controller.pointer(event.x(), event.y(), event.isClick(), OverlayInput.scrollDirection(event),
                System.nanoTime());
    }

    /**
     * Renders the palette as a centered overlay panel on top of whatever the
     * frame holds.
     */
    public void render(Frame frame, Rect area) {
        if (!controller.isOpen()) {
            return;
        }
        int height = Math.max(MIN_HEIGHT, controller.matchCount() + 4);
        Rect panel = OverlayPanel.centered(area, MIN_WIDTH, MAX_WIDTH, height);
        Rect inner = OverlayPanel.renderChrome(frame, panel, "Search Everywhere");
        controller.layout(OverlayInput.region(panel), OverlayInput.region(inner));
        frame.renderWidget(rowsParagraph(inner), inner);
    }

    private Paragraph rowsParagraph(Rect inner) {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.from(Span.styled("> " + controller.filter(), PROMPT_STYLE)));
        if (controller.matchCount() == 0) {
            lines.add(Line.from(Span.styled("no matching action", SHORTCUT_STYLE)));
        } else {
            List<KeyAction> visible = controller.visibleActions();
            for (int row = 0; row < visible.size(); row++) {
                int index = controller.firstVisible() + row;
                lines.add(actionRow(visible.get(row), index == controller.selectedIndex(), inner.width()));
            }
        }
        return Paragraph.builder().text(Text.from(lines)).build();
    }

    private Line actionRow(KeyAction action, boolean selectedRow, int width) {
        String shortcut = keymap.describeBindings(action.id());
        OverlayPanel.ChordRow row = OverlayPanel.ChordRow.fit(action.label(), shortcut,
                IntellijReference.paletteHint(keymap, action.id()), width);
        Style style = selectedRow ? SELECTION_STYLE : LABEL_STYLE;
        Style cushion = selectedRow ? style : SHORTCUT_STYLE;
        List<Span> spans = new ArrayList<>();
        spans.add(Span.styled(" " + row.label(), style));
        spans.add(Span.styled(row.filler(), cushion));
        spans.add(Span.styled(row.chord(), selectedRow ? style : SHORTCUT_STYLE));
        if (!row.hint().isEmpty()) {
            spans.add(Span.styled(" " + row.hint(), selectedRow ? style : HINT_STYLE));
        }
        return Line.from(spans);
    }
}
