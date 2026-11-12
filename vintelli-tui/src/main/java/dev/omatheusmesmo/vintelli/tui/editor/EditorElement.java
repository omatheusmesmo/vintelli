package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.ShellEditorPalette;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
import dev.omatheusmesmo.vintelli.tui.VcsMarkers;
import dev.omatheusmesmo.vintelli.tui.icons.FileIcon;
import dev.omatheusmesmo.vintelli.tui.icons.FileIcons;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.css.cascade.CssStyleResolver;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.ColorConverter;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.toolkit.elements.Row;
import dev.tamboui.toolkit.elements.TextAreaElement;
import dev.tamboui.toolkit.elements.TextElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.input.TextAreaState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Toolkit view for the built-in editor. Calls into document state are serialized by its owner. */
final class EditorElement implements Element {
    private static final String UNDERLINE = "━";
    private static final String UNDERLINE_ASCII = "-";
    private static final String OVERFLOW_LEFT = "«";
    private static final String OVERFLOW_LEFT_ASCII = "<";
    private static final String OVERFLOW = "»";
    private static final String OVERFLOW_ASCII = ">";
    private static final String DIRTY_MARK = "●";
    private static final String DIRTY_MARK_ASCII = "*";
    private static final String LINE_NUMBER_ROLE = "editor-line-number";
    private static final String CURRENT_LINE_NUMBER_ROLE = "editor-line-number-current";
    private static final String VERIFIED_BREAKPOINT_ROLE = "editor-breakpoint-verified";
    private static final String PENDING_BREAKPOINT_ROLE = "editor-breakpoint-pending";
    private static final String REJECTED_BREAKPOINT_ROLE = "editor-breakpoint-rejected";
    private static final String EXECUTION_LINE_ROLE = "editor-execution-line";
    private static final String CARET_ROLE = "editor-caret";
    private static final String QUICK_FIX_ROLE = "editor-quick-fix";
    private static final String QUICK_FIX = "☼ ";
    private static final String QUICK_FIX_ASCII = "* ";
    private static final List<String> TYPING_FOCUS_IDS = List.of(BuiltinEditor.TEXT_AREA_ID, "editor-panel", "editor-content");
    private static final int WHEEL_STEP = 3;

    private final BuiltinEditor editor;
    private final TextAreaElement textArea = Toolkit.textArea();
    private final MenuAwareTextArea menuAwareTextArea;
    private final EditorTabBarElement tabBar = new EditorTabBarElement();
    private final EditorFind find = new EditorFind();
    private final EditorFindRow findRow = new EditorFindRow(find);
    private Rect renderedArea = Rect.ZERO;
    private Rect bodyArea = Rect.ZERO;
    private Rect textBody = Rect.ZERO;
    private EditorDocument dragDocument;
    private Rect caretCell;

    EditorElement(BuiltinEditor editor) {
        this.editor = editor;
        textArea.id(null).focusable(false);
        menuAwareTextArea = new MenuAwareTextArea();
    }

    void renderTabBar(Frame frame, Rect area, RenderContext context, boolean focused, String focusMark) {
        FileIcons icons = editor.icons();
        boolean ascii = icons.mode() == IconMode.ASCII;
        List<EditorTabBarElement.Label> labels = new ArrayList<>();
        int selected = 0;
        List<BuiltinEditor.TabView> tabs = editor.tabs();
        for (int index = 0; index < tabs.size(); index++) {
            BuiltinEditor.TabView tab = tabs.get(index);
            String name = fileName(tab.file());
            String vcs = editor.vcsState(tab.file()).map(state -> " " + VcsMarkers.of(state)).orElse("");
            String dirty = tab.dirty() ? " " + (ascii ? DIRTY_MARK_ASCII : DIRTY_MARK) : "";
            FileIcon icon = icons.iconFor(name);
            labels.add(new EditorTabBarElement.Label(icon.span(icons.mode()), name + vcs + dirty));
            if (tab.active()) {
                selected = index;
            }
        }
        tabBar.update(labels, selected, focused, new EditorTabBarElement.Glyphs(focusMark,
                ascii ? UNDERLINE_ASCII : UNDERLINE,
                ascii ? OVERFLOW_LEFT_ASCII : OVERFLOW_LEFT,
                ascii ? OVERFLOW_ASCII : OVERFLOW));
        tabBar.render(frame, area, context);
    }

    int tabAt(int x, int y) {
        return tabBar.tabAt(x, y);
    }

    private final class MenuAwareTextArea extends StyledElement<MenuAwareTextArea> {
        private MenuAwareTextArea() {
            id(BuiltinEditor.TEXT_AREA_ID).focusable().fill();
        }

        @Override
        protected void renderContent(Frame frame, Rect area, RenderContext context) {
            textBody = area;
            EditorDocument document = editor.document();
            if (document == null) {
                textArea.render(frame, area, context);
            } else {
                document.paintViewport(area.height(), () -> textArea.render(frame, area, context));
                findRow.paintMatches(frame.buffer(), area, document, context);
            }
            paintCaretRow(frame.buffer(), area);
            caretCell = caretCellOf(area);
            if (!find.isOpenOn(document) && TYPING_FOCUS_IDS.stream().anyMatch(context::isFocused)) {
                paintCaret(frame.buffer(), area, context);
            }
        }

        @Override
        public Size preferredSize(int width, int height, RenderContext context) {
            return textArea.preferredSize(width, height, context);
        }

        @Override
        public EventResult handleKeyEvent(KeyEvent event, boolean focused) {
            return EditorElement.this.handleKeyEvent(event, focused);
        }
    }

    /**
     * Paints the caret row across the whole text width, but only on cells that
     * still carry the plain editor background and are not reversed by the
     * selection: diagnostic backgrounds and the selection keep their own look.
     */
    private void paintCaretRow(Buffer buffer, Rect area) {
        EditorDocument document = editor.document();
        if (document == null || area.isEmpty()) {
            return;
        }
        TextAreaState state = document.state();
        int row = state.cursorRow() - state.scrollRow();
        if (row < 0 || row >= area.height()) {
            return;
        }
        ShellEditorPalette palette = editor.palette();
        Color caretRow = ColorConverter.INSTANCE.convert(palette.caretRow()).orElse(null);
        if (caretRow == null) {
            return;
        }
        Color plain = ColorConverter.INSTANCE.convert(palette.background()).orElse(null);
        int y = area.y() + row;
        for (int x = area.x(); x < area.right(); x++) {
            Cell cell = buffer.get(x, y);
            Style style = cell.style();
            Color background = style.bg().orElse(null);
            boolean selected = style.addModifiers().contains(Modifier.REVERSED);
            if (!selected && (background == null || background.equals(plain))) {
                buffer.set(x, y, cell.patchStyle(Style.EMPTY.bg(caretRow)));
            }
        }
    }

    /**
     * Paints the caret cell with the {@code editor-caret} role, using the same column
     * arithmetic as TamboUI's cursor path in clip mode. It runs after the caret row pass,
     * which would otherwise overwrite the caret cell. Cells outside the viewport get nothing.
     */
    private void paintCaret(Buffer buffer, Rect area, RenderContext context) {
        if (area.isEmpty()) {
            return;
        }
        TextAreaState state = textArea.getState();
        int row = state.cursorRow() - state.scrollRow();
        int column = caretColumn(state);
        if (row < 0 || row >= area.height() || column < 0 || column >= area.width()) {
            return;
        }
        int x = area.x() + column;
        int y = area.y() + row;
        buffer.set(x, y, buffer.get(x, y).patchStyle(caretStyle(context)));
    }

    private static int caretColumn(TextAreaState state) {
        int cursorCol = state.cursorCol();
        if (cursorCol < state.scrollCol()) {
            return -1;
        }
        String line = state.getLine(state.cursorRow());
        int from = Math.min(state.scrollCol(), line.length());
        int to = Math.min(cursorCol, line.length());
        return CharWidth.of(line.substring(from, to));
    }

    private static Style caretStyle(RenderContext context) {
        return Style.EMPTY.reversed().patch(roleStyle(context, CARET_ROLE));
    }

    private Rect caretCellOf(Rect area) {
        TextAreaState state = textArea.getState();
        int row = state.cursorRow() - state.scrollRow();
        int column = caretColumn(state);
        if (area.isEmpty() || row < 0 || row >= area.height() || column < 0 || column >= area.width()) {
            return null;
        }
        return new Rect(area.x() + column, area.y() + row, 1, 1);
    }

    /** The screen cell of the caret as the last frame drew it, empty when the caret was scrolled out of view. */
    Optional<Rect> caretCell() {
        return Optional.ofNullable(caretCell);
    }

    @Override public String id() { return "editor-content"; }
    @Override public boolean isFocusable() { return true; }
    @Override public Rect renderedArea() { return renderedArea; }
    @Override public Size preferredSize(int width, int height, RenderContext context) { return Size.of(width, height); }

    @Override public void render(Frame frame, Rect area, RenderContext context) {
        synchronized (editor) {
            EditorDocument document = editor.document();
            find.syncTo(document);
            if (!area.equals(renderedArea)) {
                dragDocument = null;
                if (document != null) { document.requestReveal(); }
            }
            renderedArea = area;
            textBody = Rect.ZERO;
            bodyArea = Rect.ZERO;
            Column column = Toolkit.column().id("editor-document");
            if (document == null) {
                column.add(Toolkit.text("Open a file from Project or Go to File").fill());
            } else {
                Row body = Toolkit.row().fill();
                int gutterWidth = Integer.toString(document.lineCount()).length() + 3;
                boolean finding = find.isOpenOn(document);
                int bodyHeight = Math.max(0, area.height() - (finding ? 2 : 1));
                if (document.consumeReveal()) {
                    document.state().ensureCursorVisible(bodyHeight, Math.max(0, area.width() - gutterWidth));
                }
                bodyArea = new Rect(area.x(), area.y(), area.width(), bodyHeight);
                body.add(Toolkit.richText(gutter(document, bodyHeight, gutterWidth, context))
                        .length(gutterWidth).id("editor-gutter"));
                document.highlighter().editorPalette(editor.palette());
                textArea.state(document.state()).highlighter(document.highlighter(), language(document.file().toString()))
                        .clip().fill();
                body.add(menuAwareTextArea);
                column.add(body);
                if (finding) {
                    column.add(findRow);
                }
                TextElement statusLine = Toolkit.text(editor.status()).length(1);
                if (editor.closeConfirmationPending()) {
                    statusLine.id("editor-close-confirmation").focusable().onKeyEvent(key -> {
                        editor.handleKey(key);
                        return EventResult.HANDLED;
                    });
                } else {
                    statusLine.id("editor-status");
                }
                column.add(statusLine);
            }
            RenderContext renderContext = context == null ? DefaultRenderContext.createEmpty() : context;
            column.render(frame, area, renderContext);
            if (renderContext instanceof DefaultRenderContext drc) {
                String focusTarget = editor.consumeFocusRequest();
                if (focusTarget != null) {
                    drc.focusManager().setFocus(focusTarget);
                }
            }
        }
    }

    private Text gutter(EditorDocument document, int height, int width, RenderContext context) {
        Style number = roleStyle(context, LINE_NUMBER_ROLE);
        Style current = roleStyle(context, CURRENT_LINE_NUMBER_ROLE);
        int caret = document.state().cursorRow();
        Map<Integer, DebugCapability.SourceBreakpoint> breakpoints = editor.breakpoints(document);
        boolean ascii = editor.icons().mode() == IconMode.ASCII;
        List<Line> lines = new ArrayList<>();
        int first = document.state().scrollRow();
        int end = Math.min(document.state().lineCount(), first + height);
        for (int row = first; row < end; row++) {
            boolean diagnostic = document.hasDiagnosticAt(row);
            var breakpoint = breakpoints.get(document.documentRow(row));
            String marker;
            Style markerStyle = Style.EMPTY;
            if (breakpoint != null) {
                switch (breakpoint.state()) {
                    case VERIFIED -> { marker = ascii ? "B " : "● "; markerStyle = roleStyle(context, VERIFIED_BREAKPOINT_ROLE); }
                    case REJECTED -> { marker = ascii ? "! " : "× "; markerStyle = roleStyle(context, REJECTED_BREAKPOINT_ROLE); }
                    default -> { marker = ascii ? "o " : "◌ "; markerStyle = roleStyle(context, PENDING_BREAKPOINT_ROLE); }
                }
            } else if (diagnostic && row == caret) {
                marker = ascii ? QUICK_FIX_ASCII : QUICK_FIX;
                markerStyle = roleStyle(context, QUICK_FIX_ROLE);
            } else {
                marker = diagnostic ? "! " : editor.vcsChangedLine(row) ? "+ " : "  ";
            }
            boolean executing = editor.executionLine(document, document.documentRow(row));
            if (executing) marker = marker.substring(0, 1) + (ascii ? ">" : "▶");
            String digits = Integer.toString(document.documentRow(row) + 1);
            String padding = " ".repeat(width - 3 - digits.length());
            lines.add(Line.from(Span.styled(marker.substring(0, 1), markerStyle),
                    Span.styled(marker.substring(1), executing ? roleStyle(context, EXECUTION_LINE_ROLE) : markerStyle),
                    Span.styled(padding + digits, row == caret ? current : number),
                    Span.raw(" ")));
        }
        return Text.from(lines);
    }

    static Style roleStyle(RenderContext context, String role) {
        return context.resolveStyle(role, role).map(CssStyleResolver::toStyle).orElse(Style.EMPTY);
    }

    private static String fileName(String file) {
        String normalized = file.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private static String language(String file) {
        int dot = file.lastIndexOf('.');
        return dot < 0 ? "text" : file.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    boolean handleMouse(MouseEvent event) {
        synchronized (editor) {
            EditorDocument document = editor.document();
            if (document == null || dragDocument != null && dragDocument != document) {
                dragDocument = null;
                return false;
            }
            if (dragDocument != null) {
                if (event.isRelease()) {
                    dragDocument = null;
                    return true;
                }
                if (event.kind() == MouseEventKind.DRAG && event.isLeftButton()) {
                    scrollDragEdge(document, event.y());
                    TextPosition at = textPosition(document, event.x(), event.y());
                    document.pointerDrag(at.row(), at.column());
                    return true;
                }
                dragDocument = null;
            }
            if (event.isPress() && event.isLeftButton() && bodyArea.contains(event.x(), event.y())
                    && event.x() < textBody.x()) {
                int row = Math.clamp(document.state().scrollRow() + event.y() - bodyArea.y(), 0,
                        document.state().lineCount() - 1);
                editor.toggleBreakpoint(document.documentRow(row));
                return true;
            }
            if (event.isPress() && event.isLeftButton() && textBody.contains(event.x(), event.y())) {
                TextPosition at = textPosition(document, event.x(), event.y());
                document.pointerPress(at.row(), at.column());
                dragDocument = document;
                editor.requestFocus();
                return true;
            }
            if (event.isScroll() && bodyArea.contains(event.x(), event.y())) {
                if (event.kind() == MouseEventKind.SCROLL_UP) {
                    document.scrollBy(-WHEEL_STEP, textBody.height());
                    return true;
                }
                if (event.kind() == MouseEventKind.SCROLL_DOWN) {
                    document.scrollBy(WHEEL_STEP, textBody.height());
                    return true;
                }
            }
            return false;
        }
    }

    private void scrollDragEdge(EditorDocument document, int y) {
        int rows = dragScrollRows(y, textBody);
        if (rows != 0) {
            document.scrollBy(rows, textBody.height());
        }
    }

    /**
     * The document rows to scroll for a drag event at {@code y}: one row up above the text area,
     * one row down below it, and none inside it. {@link EditorDocument#scrollBy} clamps at both ends.
     */
    static int dragScrollRows(int y, Rect body) {
        if (body.isEmpty()) {
            return 0;
        }
        if (y < body.y()) {
            return -1;
        }
        return y >= body.bottom() ? 1 : 0;
    }

    void cancelPointer() {
        dragDocument = null;
    }

    void openFind(EditorDocument document) {
        find.open(document);
    }

    void closeFind() {
        find.close();
    }

    boolean findOpenOn(EditorDocument document) {
        return document != null && find.isOpenOn(document);
    }

    /** The view row and character column under a screen cell, clamped to the visible text area. */
    private TextPosition textPosition(EditorDocument document, int x, int y) {
        TextAreaState state = document.state();
        int screenRow = Math.clamp(y - textBody.y(), 0, Math.max(0, textBody.height() - 1));
        int row = Math.clamp(state.scrollRow() + screenRow, 0, state.lineCount() - 1);
        int cell = Math.clamp(x - textBody.x(), 0, Math.max(0, textBody.width() - 1));
        return new TextPosition(row, TextCellMap.charColumnAt(state.getLine(row), state.scrollCol(), cell));
    }

    private record TextPosition(int row, int column) {
    }

    @Override public EventResult handleKeyEvent(KeyEvent event, boolean focused) {
        synchronized (editor) {
            if (editor.closeConfirmationPending()) {
                if (event.isCharIgnoreCase('y')) { editor.confirmClose(true); return EventResult.HANDLED; }
                if (event.isCharIgnoreCase('n') || event.isKey(KeyCode.ESCAPE)) { editor.confirmClose(false); return EventResult.HANDLED; }
            }
            if (editor.menuNavigationActive() || editor.overlayInputActive()) { return EventResult.UNHANDLED; }
            if (reserved(event)) { return EventResult.UNHANDLED; }
            EditorDocument document = editor.document();
            if (document == null) { return EventResult.UNHANDLED; }
            if (find.isOpenOn(document)) {
                if (event.isKey(KeyCode.ESCAPE)) { editor.closeFind(); } else { findRow.handleKeyEvent(event, focused); }
                return EventResult.HANDLED;
            }
            // Bind the current document on the input path too; events can arrive
            // before the first render or immediately after a tab switch.
            textArea.state(document.state());
            document.requestReveal();
            if (EditorSelectionKeys.handleClipboardChord(event, editor.keymap(), document, editor)) {
                return EventResult.HANDLED;
            }
            if (EditorSelectionKeys.selectsAll(event, editor.keymap())) {
                editor.selectAll();
                return EventResult.HANDLED;
            }
            if (EditorSelectionKeys.extendsSelection(event)) {
                extendSelection(document, event, focused);
                editor.changed();
                return EventResult.HANDLED;
            }
            int rowBefore = document.row();
            int columnBefore = document.column();
            boolean hadSelection = document.hasSelection();
            boolean removesSelection = hadSelection && EditorSelectionKeys.removesOnly(event);
            boolean replacesSelection = hadSelection && EditorSelectionKeys.replacesSelection(event);
            EventResult[] resultHolder = { EventResult.UNHANDLED };
            document.mutate(() -> {
                if (removesSelection || replacesSelection) {
                    document.deleteSelectionWithinEdit();
                    textArea.state(document.state());
                }
                // The widget edits TextAreaState directly, so wrap the event itself
                // to keep undo history and diagnostic locations in sync.
                resultHolder[0] = removesSelection ? EventResult.HANDLED : textArea.handleKeyEvent(event, focused);
            });
            boolean handled = resultHolder[0].isHandled();
            if (rowBefore != document.row() || columnBefore != document.column()) {
                document.dropPointerSelection();
            }
            if (handled) { editor.changed(); }
            return handled ? EventResult.HANDLED : EventResult.UNHANDLED;
        }
    }

    private void extendSelection(EditorDocument document, KeyEvent event, boolean focused) {
        if (event.isKey(KeyCode.PAGE_UP) || event.isKey(KeyCode.PAGE_DOWN)) {
            document.extendSelectionByPage(event.isKey(KeyCode.PAGE_DOWN), Math.max(1, textBody.height()));
        } else {
            document.extendSelection(() -> textArea.handleKeyEvent(event, focused));
        }
    }

    private static boolean reserved(KeyEvent event) {
        if (event.hasAlt()) { return true; }
        if (event.hasCtrl() && "szg yf bwopd".replace(" ", "").indexOf(Character.toLowerCase(event.character())) >= 0) { return true; }
        return event.isKey(KeyCode.F1) || event.isKey(KeyCode.F2) || event.isKey(KeyCode.F4) || event.isKey(KeyCode.F8)
                || event.isKey(KeyCode.F10) || event.isKey(KeyCode.F12);
    }
}
