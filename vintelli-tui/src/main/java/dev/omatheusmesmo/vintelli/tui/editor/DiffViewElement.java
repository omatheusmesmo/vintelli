package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.ShellEditorPalette;
import dev.omatheusmesmo.vintelli.tui.Keys;
import dev.omatheusmesmo.vintelli.tui.editor.DiffModel.Kind;
import dev.omatheusmesmo.vintelli.tui.editor.DiffModel.LineKind;
import dev.omatheusmesmo.vintelli.tui.editor.DiffModel.Row;
import dev.omatheusmesmo.vintelli.tui.editor.DiffModel.Side;
import dev.omatheusmesmo.vintelli.tui.editor.DiffModel.UnifiedLine;
import dev.omatheusmesmo.vintelli.tui.editor.DiffModel.WordRange;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.syntax.SyntaxTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Draws the active {@link DiffTab} of the editor: a title row, then either two aligned panes with line
 * numbers and a one cell connector column between them, or the unified layout with {@code -} and
 * {@code +} lines under {@code @@} headers. Changed rows take the background of their change kind,
 * changed words a stronger shade, and the text keeps the syntax colors of the editor. It routes the
 * hunk navigation, the layout toggle, the cursor keys and Enter to the editor.
 */
final class DiffViewElement extends StyledElement<DiffViewElement> {

    static final String TEXT_ROLE = "editor-diff-text";
    static final String FILLER_ROLE = "editor-diff-filler";
    static final String HUNK_HEADER_ROLE = "editor-diff-hunk-header";
    static final String PANE_TITLE_ROLE = "editor-diff-pane-title";
    static final String CURSOR_ROLE = "editor-diff-cursor";
    private static final String LINE_NUMBER_ROLE = "editor-line-number";
    private static final String CURRENT_LINE_NUMBER_ROLE = "editor-line-number-current";
    private static final String SEPARATOR = "│";
    private static final String SEPARATOR_ASCII = "|";

    private final BuiltinEditor editor;
    private final EditorSyntaxHighlighter oldHighlighter = new EditorSyntaxHighlighter();
    private final EditorSyntaxHighlighter newHighlighter = new EditorSyntaxHighlighter();
    private DiffTab highlightedTab;
    private ShellEditorPalette highlightedPalette;
    private List<Line> oldLines = List.of();
    private List<Line> newLines = List.of();

    DiffViewElement(BuiltinEditor editor) {
        this.editor = editor;
        id(BuiltinEditor.DIFF_ID).focusable().fill();
    }

    /** The background role of a changed row of {@code kind}, such as {@code editor-diff-inserted}. */
    static String kindRole(Kind kind) {
        return "editor-diff-" + kind.name().toLowerCase(Locale.ROOT);
    }

    /** The stronger shade of {@link #kindRole} that marks the changed words. */
    static String wordRole(Kind kind) {
        return kindRole(kind) + "-word";
    }

    /** The role of the connector cell of a row in a change of {@code kind}. */
    static String connectorRole(Kind kind) {
        return "editor-diff-connector-" + kind.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public Size preferredSize(int width, int height, RenderContext context) {
        return Size.of(width, height);
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
        synchronized (editor) {
            DiffTab tab = editor.activeDiffTab();
            if (tab == null || area.isEmpty()) {
                return;
            }
            Rect body = new Rect(area.x(), area.y() + 1, area.width(), Math.max(0, area.height() - 1));
            tab.layout(area.width(), body.height());
            highlight(tab);
            Buffer buffer = frame.buffer();
            Style text = EditorElement.roleStyle(context, TEXT_ROLE);
            buffer.setStyle(area, text);
            if (tab.unified()) {
                title(buffer, area.x(), area.y(), area.width(), "HEAD → Working (unified)", context);
                unified(buffer, body, tab, context);
            } else {
                int leftWidth = (area.width() - 1) / 2;
                title(buffer, area.x(), area.y(), leftWidth, tab.model().headPresent() ? "HEAD" : "HEAD (added file)",
                        context);
                title(buffer, area.x() + leftWidth, area.y(), area.width() - leftWidth,
                        tab.model().workingPresent() ? " Working" : " Working (deleted)", context);
                sideBySide(buffer, body, leftWidth, tab, context);
            }
        }
    }

    @Override
    public EventResult handleKeyEvent(KeyEvent event, boolean focused) {
        synchronized (editor) {
            if (editor.menuNavigationActive() || editor.overlayInputActive() || editor.activeDiffTab() == null) {
                return EventResult.UNHANDLED;
            }
            Keymap keymap = editor.keymap();
            if (keymap.matches(event, KeyActions.DIFF_PREVIOUS_HUNK)) {
                editor.diffHunk(false);
            } else if (keymap.matches(event, KeyActions.DIFF_NEXT_HUNK)) {
                editor.diffHunk(true);
            } else if (keymap.matches(event, KeyActions.DIFF_TOGGLE_UNIFIED)) {
                editor.toggleDiffLayout();
            } else if (event.hasAlt() || event.hasCtrl()) {
                return EventResult.UNHANDLED;
            } else if (Keys.is(event, KeyCode.ENTER)) {
                editor.openDiffLine();
            } else if (!moveCursor(editor.activeDiffTab(), event)) {
                return EventResult.UNHANDLED;
            }
            return EventResult.HANDLED;
        }
    }

    private static boolean moveCursor(DiffTab tab, KeyEvent event) {
        if (Keys.is(event, KeyCode.UP)) {
            tab.move(-1);
        } else if (Keys.is(event, KeyCode.DOWN)) {
            tab.move(1);
        } else if (Keys.is(event, KeyCode.PAGE_UP)) {
            tab.page(-1);
        } else if (Keys.is(event, KeyCode.PAGE_DOWN)) {
            tab.page(1);
        } else if (Keys.is(event, KeyCode.HOME)) {
            tab.move(-tab.lineCount());
        } else if (Keys.is(event, KeyCode.END)) {
            tab.move(tab.lineCount());
        } else {
            return false;
        }
        return true;
    }

    private void highlight(DiffTab tab) {
        ShellEditorPalette palette = editor.palette();
        if (tab == highlightedTab && Objects.equals(palette, highlightedPalette)) {
            return;
        }
        String language = language(tab.file().getFileName().toString());
        List<String> left = new ArrayList<>();
        List<String> right = new ArrayList<>();
        for (Row row : tab.model().rows()) {
            if (row.left() != null) {
                left.add(row.left().text());
            }
            if (row.right() != null) {
                right.add(row.right().text());
            }
        }
        oldHighlighter.editorPalette(palette);
        newHighlighter.editorPalette(palette);
        oldLines = oldHighlighter.highlight(String.join("\n", left), language, Style.EMPTY, SyntaxTheme.DEFAULTS);
        newLines = newHighlighter.highlight(String.join("\n", right), language, Style.EMPTY, SyntaxTheme.DEFAULTS);
        highlightedTab = tab;
        highlightedPalette = palette;
    }

    private void title(Buffer buffer, int x, int y, int width, String label, RenderContext context) {
        if (width <= 0) {
            return;
        }
        Style style = EditorElement.roleStyle(context, TEXT_ROLE)
                .patch(EditorElement.roleStyle(context, PANE_TITLE_ROLE));
        buffer.setString(x, y, " ".repeat(width), style);
        buffer.setLine(x, y, Line.from(Span.styled(" " + label, style)), width);
    }

    private void sideBySide(Buffer buffer, Rect body, int leftWidth, DiffTab tab, RenderContext context) {
        List<Row> rows = tab.model().rows();
        int numberWidth = numberWidth(tab);
        int connector = body.x() + leftWidth;
        int rightWidth = body.width() - leftWidth - 1;
        String separator = editor.icons().mode() == IconMode.ASCII ? SEPARATOR_ASCII : SEPARATOR;
        for (int offset = 0; offset < body.height(); offset++) {
            int index = tab.scroll() + offset;
            int y = body.y() + offset;
            if (index >= rows.size()) {
                break;
            }
            Row row = rows.get(index);
            boolean cursor = index == tab.cursor();
            side(buffer, body.x(), y, leftWidth, numberWidth, row.left(), row.kind(), oldLines, cursor, context);
            if (row.hunk() >= 0) {
                buffer.setString(connector, y, " ", EditorElement.roleStyle(context, TEXT_ROLE)
                        .patch(EditorElement.roleStyle(context, connectorRole(row.kind()))));
            } else {
                buffer.setString(connector, y, separator, EditorElement.roleStyle(context, TEXT_ROLE)
                        .patch(EditorElement.roleStyle(context, LINE_NUMBER_ROLE)));
            }
            side(buffer, connector + 1, y, rightWidth, numberWidth, row.right(), row.kind(), newLines, cursor,
                    context);
        }
    }

    private void side(Buffer buffer, int x, int y, int width, int numberWidth, Side side, Kind kind,
            List<Line> highlighted, boolean cursor, RenderContext context) {
        if (width <= 0) {
            return;
        }
        Style text = EditorElement.roleStyle(context, TEXT_ROLE);
        if (side == null) {
            buffer.setString(x, y, " ".repeat(width), text.patch(EditorElement.roleStyle(context, FILLER_ROLE)));
            return;
        }
        Style row = kind == Kind.EQUAL ? text : text.patch(EditorElement.roleStyle(context, kindRole(kind)));
        if (cursor && kind == Kind.EQUAL) {
            row = row.patch(EditorElement.roleStyle(context, CURSOR_ROLE));
        }
        buffer.setString(x, y, " ".repeat(width), row);
        gutter(buffer, x, y, width, List.of(side.line()), numberWidth, row, cursor, context);
        int textX = x + numberWidth + 1;
        int textWidth = width - numberWidth - 1;
        if (textWidth > 0) {
            buffer.setLine(textX, y, Line.from(spans(side.text(), side.line(), highlighted, side.words(), row,
                    kind == Kind.EQUAL ? null : wordRole(kind), context)), textWidth);
        }
    }

    private void unified(Buffer buffer, Rect body, DiffTab tab, RenderContext context) {
        List<UnifiedLine> lines = tab.model().unified();
        Style text = EditorElement.roleStyle(context, TEXT_ROLE);
        if (lines.isEmpty() && body.height() > 0) {
            buffer.setString(body.x(), body.y(), " No differences", text);
            return;
        }
        int numberWidth = numberWidth(tab);
        for (int offset = 0; offset < body.height(); offset++) {
            int index = tab.scroll() + offset;
            if (index >= lines.size()) {
                break;
            }
            UnifiedLine line = lines.get(index);
            int y = body.y() + offset;
            boolean cursor = index == tab.cursor();
            if (line.kind() == LineKind.HEADER) {
                Style header = text.patch(EditorElement.roleStyle(context, HUNK_HEADER_ROLE));
                if (cursor) {
                    header = header.patch(EditorElement.roleStyle(context, CURSOR_ROLE));
                }
                buffer.setString(body.x(), y, " ".repeat(body.width()), header);
                buffer.setLine(body.x(), y, Line.from(Span.styled(line.text(), header)), body.width());
                continue;
            }
            Kind kind = switch (line.kind()) {
                case REMOVED -> Kind.DELETED;
                case ADDED -> Kind.INSERTED;
                default -> Kind.EQUAL;
            };
            Style row = kind == Kind.EQUAL ? text : text.patch(EditorElement.roleStyle(context, kindRole(kind)));
            if (cursor && kind == Kind.EQUAL) {
                row = row.patch(EditorElement.roleStyle(context, CURSOR_ROLE));
            }
            buffer.setString(body.x(), y, " ".repeat(body.width()), row);
            List<Integer> numbers = new ArrayList<>();
            numbers.add(line.oldLine());
            numbers.add(line.newLine());
            int gutterWidth = gutter(buffer, body.x(), y, body.width(), numbers, numberWidth, row, cursor,
                    context);
            String marker = switch (line.kind()) {
                case REMOVED -> "-";
                case ADDED -> "+";
                default -> " ";
            };
            int markerX = body.x() + gutterWidth;
            if (markerX >= body.right()) {
                continue;
            }
            buffer.setString(markerX, y, marker, row);
            int textWidth = body.right() - markerX - 1;
            if (textWidth > 0) {
                boolean old = line.kind() == LineKind.REMOVED;
                buffer.setLine(markerX + 1, y, Line.from(spans(line.text(), old ? line.oldLine() : line.newLine(),
                        old ? oldLines : newLines, line.words(), row,
                        kind == Kind.EQUAL ? null : wordRole(kind), context)), textWidth);
            }
        }
    }

    /**
     * Draws right aligned line numbers, a blank for 0, each followed by one space. @return the cells drawn
     */
    private static int gutter(Buffer buffer, int x, int y, int width, List<Integer> numbers, int numberWidth,
            Style row, boolean cursor, RenderContext context) {
        Style number = row.patch(EditorElement.roleStyle(context,
                cursor ? CURRENT_LINE_NUMBER_ROLE : LINE_NUMBER_ROLE));
        if (cursor) {
            number = number.patch(EditorElement.roleStyle(context, CURSOR_ROLE));
        }
        StringBuilder cells = new StringBuilder();
        for (int line : numbers) {
            String digits = line <= 0 ? "" : Integer.toString(line);
            cells.append(" ".repeat(Math.max(0, numberWidth - digits.length()))).append(digits).append(' ');
        }
        String shown = cells.length() > width ? cells.substring(0, width) : cells.toString();
        buffer.setString(x, y, shown, number);
        return shown.length();
    }

    private static List<Span> spans(String content, int line, List<Line> highlighted, List<WordRange> words,
            Style row, String wordRole, RenderContext context) {
        List<Span> spans = new ArrayList<>();
        Line source = line - 1 < highlighted.size() ? highlighted.get(line - 1) : null;
        if (source == null || !source.rawContent().equals(content)) {
            spans.add(Span.styled(content, row));
        } else {
            for (Span span : source.spans()) {
                Style styled = row.patch(span.style());
                spans.add(Span.styled(span.content(), row.bg().map(styled::bg).orElse(styled)));
            }
        }
        if (wordRole == null) {
            return spans;
        }
        Style word = EditorElement.roleStyle(context, wordRole);
        for (WordRange range : words) {
            spans = EditorSyntaxHighlighter.styleRange(spans, range.start(), range.end(), style -> style.patch(word));
        }
        return spans;
    }

    private static int numberWidth(DiffTab tab) {
        return Math.max(2, Integer.toString(tab.model().maxLine()).length());
    }

    private static String language(String file) {
        int dot = file.lastIndexOf('.');
        return dot < 0 ? "text" : file.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
