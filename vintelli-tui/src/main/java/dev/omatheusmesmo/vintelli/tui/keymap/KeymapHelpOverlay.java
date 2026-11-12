package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.omatheusmesmo.vintelli.tui.OverlayInput;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.ArrayList;
import java.util.List;

/**
 * Help overlay listing the active keymap: every named action of the catalog
 * with the shortcuts the active profile (plus user file, when layered)
 * currently binds, and the IntelliJ chord of the action from the comparison
 * table as a reference column. A row that does not fit the panel drops the
 * IntelliJ column first, then shortens the action name, and never cuts the
 * active chord. Reachable through the {@code KeymapHelp} action ({@code F1} in
 * every shipped profile); Escape or F1 closes it.
 */
public final class KeymapHelpOverlay {

    private static final int MIN_WIDTH = 64;
    private static final int MAX_WIDTH = 78;
    private static final Style LABEL_STYLE = Style.create();
    private static final Style SHORTCUT_STYLE = Style.create().bold();
    private static final Style HINT_STYLE = Style.create().dim().italic();
    private static final Style FOOTER_STYLE = Style.create().dim();

    private final Keymap keymap;
    private final KeymapHelpController controller;

    public KeymapHelpOverlay(Keymap keymap) {
        this.keymap = keymap;
        this.controller = new KeymapHelpController(keymap.actions());
    }

    /**
     * Opens the overlay; the title announces the active keymap source, so a
     * layered user file is always visible.
     */
    public void open() {
        controller.open();
    }

    public void close() {
        controller.close();
    }

    public boolean isOpen() {
        return controller.isOpen();
    }

    /**
     * Consumes every event while the overlay is open: Escape and the
     * {@code KeymapHelp} action close it, the overlay stays modal otherwise.
     */
    public void handleKey(KeyEvent key) {
        controller.press(OverlayInput.key(key), keymap.matchesReserved(key, KeyActions.KEYMAP_HELP));
    }

    /**
     * Renders the overlay as a centered panel: one row per action, label left
     * and the bound shortcut right-aligned, unbound actions marked as such;
     * actions that do not fit are counted in the footer instead of being
     * silently cut off.
     */
    public void render(Frame frame, Rect area) {
        if (!controller.isOpen()) {
            return;
        }
        Rect panel = OverlayPanel.centered(area, MIN_WIDTH, MAX_WIDTH, controller.panelHeight());
        Rect inner = OverlayPanel.renderChrome(frame, panel, "Keymap: " + keymap.source());

        List<Line> lines = new ArrayList<>();
        int visibleRows = inner.height() - 1;
        int shown = controller.shownActions(visibleRows);
        for (int index = 0; index < shown; index++) {
            lines.add(row(controller.actions().get(index), inner.width()));
        }
        int hidden = controller.hiddenActions(visibleRows);
        String clipped = hidden > 0 ? " (+" + hidden + " more)" : "";
        lines.add(Line.from(Span.styled("Escape closes the overlay" + clipped, FOOTER_STYLE)));
        frame.renderWidget(Paragraph.builder().text(Text.from(lines)).build(), inner);
    }

    private Line row(KeyAction action, int width) {
        String shortcut = keymap.describeBindings(action.id());
        OverlayPanel.ChordRow row = OverlayPanel.ChordRow.fit(action.label(), shortcut,
                IntellijReference.helpText(action.id()), width);
        Style chordStyle = shortcut.isEmpty() ? HINT_STYLE : SHORTCUT_STYLE;
        List<Span> spans = new ArrayList<>();
        spans.add(Span.styled(" " + row.label(), LABEL_STYLE));
        spans.add(Span.styled(row.filler(), chordStyle));
        spans.add(Span.styled(row.chord(), chordStyle));
        if (!row.hint().isEmpty()) {
            spans.add(Span.styled(" " + row.hint(), HINT_STYLE));
        }
        return Line.from(spans);
    }
}
