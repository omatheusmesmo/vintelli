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

/**
 * Help overlay listing the active keymap: every named action of the catalog
 * with the shortcuts the active profile (plus user file, when layered)
 * currently binds, and the real IntelliJ shortcut as a reference column
 * whenever it differs. Reachable through the {@code KeymapHelp} action
 * ({@code F1} in every shipped profile); Escape or F1 closes it.
 */
public final class KeymapHelpOverlay {

    private static final int MIN_WIDTH = 64;
    private static final int MAX_WIDTH = 78;
    private static final Style LABEL_STYLE = Style.create();
    private static final Style SHORTCUT_STYLE = Style.create().bold();
    private static final Style HINT_STYLE = Style.create().dim().italic();
    private static final Style FOOTER_STYLE = Style.create().dim();

    private final Keymap keymap;
    private boolean open;

    public KeymapHelpOverlay(Keymap keymap) {
        this.keymap = keymap;
    }

    /**
     * Opens the overlay; the title announces the active keymap source, so a
     * layered user file is always visible.
     */
    public void open() {
        open = true;
    }

    public void close() {
        open = false;
    }

    public boolean isOpen() {
        return open;
    }

    /**
     * Consumes every event while the overlay is open: Escape and the
     * {@code KeymapHelp} action close it, the overlay stays modal otherwise.
     */
    public void handleKey(KeyEvent key) {
        if (key.isKey(KeyCode.ESCAPE) || keymap.matchesReserved(key, KeyActions.KEYMAP_HELP)) {
            close();
        }
    }

    /**
     * Renders the overlay as a centered panel: one row per action, label left
     * and the bound shortcut right-aligned, unbound actions marked as such;
     * actions that do not fit are counted in the footer instead of being
     * silently cut off.
     */
    public void render(Frame frame, Rect area) {
        if (!open) {
            return;
        }
        List<KeyAction> actions = keymap.actions();
        int height = Math.max(6, actions.size() + 5);
        Rect panel = OverlayPanel.centered(area, MIN_WIDTH, MAX_WIDTH, height);
        Rect inner = OverlayPanel.renderChrome(frame, panel, "Keymap: " + keymap.source());

        List<Line> lines = new ArrayList<>();
        int visibleRows = inner.height() - 1;
        int shown = Math.min(actions.size(), visibleRows);
        for (int index = 0; index < shown; index++) {
            lines.add(row(actions.get(index), inner.width()));
        }
        String clipped = actions.size() > shown ? " (+" + (actions.size() - shown) + " more)" : "";
        lines.add(Line.from(Span.styled("Escape closes the overlay" + clipped, FOOTER_STYLE)));
        frame.renderWidget(Paragraph.builder().text(Text.from(lines)).build(), inner);
    }

    private Line row(KeyAction action, int width) {
        String shortcut = keymap.describeBindings(action.id());
        String chord = shortcut.isEmpty() ? "unbound" : shortcut;
        boolean withHint = !action.intellijShortcut().isEmpty();
        String hint = withHint ? "(IntelliJ: " + action.intellijShortcut() + ")" : "";
        String label = OverlayPanel.truncate(action.label(), width - chord.length() - hint.length() - 2);
        String filler = " ".repeat(Math.max(1, width - label.length() - chord.length() - hint.length() - 2));
        Style chordStyle = shortcut.isEmpty() ? HINT_STYLE : SHORTCUT_STYLE;
        List<Span> spans = new ArrayList<>();
        spans.add(Span.styled(" " + label, LABEL_STYLE));
        spans.add(Span.styled(filler, chordStyle));
        spans.add(Span.styled(chord, chordStyle));
        if (withHint) {
            spans.add(Span.styled(" " + hint, HINT_STYLE));
        }
        return Line.from(spans);
    }
}
