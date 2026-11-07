package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.ShellEditorPalette;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
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
import dev.tamboui.widgets.input.TextAreaState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Toolkit view for the built-in editor. Calls into document state are serialized by its owner. */
final class EditorElement implements Element {
    private static final String UNDERLINE = "━";
    private static final String UNDERLINE_ASCII = "-";
    private static final String OVERFLOW = "»";
    private static final String OVERFLOW_ASCII = ">";
    private static final String DIRTY_MARK = "●";
    private static final String DIRTY_MARK_ASCII = "*";
    private static final String LINE_NUMBER_ROLE = "editor-line-number";
    private static final String CURRENT_LINE_NUMBER_ROLE = "editor-line-number-current";

    private final BuiltinEditor editor;
    private final TextAreaElement textArea = Toolkit.textArea();
    private final MenuAwareTextArea menuAwareTextArea;
    private final NavigationBarElement navigationBar = new NavigationBarElement();
    private final EditorTabBarElement tabBar = new EditorTabBarElement();
    private Rect renderedArea = Rect.ZERO;

    EditorElement(BuiltinEditor editor) {
        this.editor = editor;
        textArea.id(null).focusable(false);
        menuAwareTextArea = new MenuAwareTextArea();
    }

    void navigationBar(List<String> labels, int selected) {
        navigationBar.segments(labels, selected);
    }

    int navigationSegmentAt(int x, int y) {
        return navigationBar.segmentAt(x, y);
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
            String vcs = editor.vcsState(tab.file()).map(state -> " " + vcsMarker(state)).orElse("");
            String dirty = tab.dirty() ? " " + (ascii ? DIRTY_MARK_ASCII : DIRTY_MARK) : "";
            FileIcon icon = icons.iconFor(name);
            labels.add(new EditorTabBarElement.Label(icon.span(icons.mode()), name + vcs + dirty));
            if (tab.active()) {
                selected = index;
            }
        }
        tabBar.update(labels, selected, focused, new EditorTabBarElement.Glyphs(focusMark,
                ascii ? UNDERLINE_ASCII : UNDERLINE, ascii ? OVERFLOW_ASCII : OVERFLOW));
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
            textArea.render(frame, area, context);
            paintCaretRow(frame.buffer(), area);
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

    @Override public String id() { return "editor-content"; }
    @Override public boolean isFocusable() { return true; }
    @Override public Rect renderedArea() { return renderedArea; }
    @Override public Size preferredSize(int width, int height, RenderContext context) { return Size.of(width, height); }

    @Override public void render(Frame frame, Rect area, RenderContext context) {
        synchronized (editor) {
            renderedArea = area;
            EditorDocument document = editor.document();
            Column column = Toolkit.column().id("editor-document");
            if (document == null) {
                column.add(navigationBar);
                column.add(Toolkit.text("Open a file from Project or Go to File").fill());
            } else {
                column.add(navigationBar);
                Row body = Toolkit.row().fill();
                int gutterWidth = Integer.toString(document.lineCount()).length() + 3;
                int bodyHeight = Math.max(0, area.height() - (navigationBar.hasSegments() ? 1 : 0) - 1);
                document.state().ensureCursorVisible(bodyHeight, Math.max(0, area.width() - gutterWidth));
                body.add(Toolkit.richText(gutter(document, bodyHeight, gutterWidth, context))
                        .length(gutterWidth).id("editor-gutter"));
                document.highlighter().editorPalette(editor.palette());
                textArea.state(document.state()).highlighter(document.highlighter(), language(document.file().toString()))
                        .clip().fill();
                body.add(menuAwareTextArea);
                column.add(body);
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
        List<Line> lines = new ArrayList<>();
        int first = document.state().scrollRow();
        int end = Math.min(document.state().lineCount(), first + height);
        for (int row = first; row < end; row++) {
            boolean diagnostic = document.hasDiagnosticAt(row);
            String marker = diagnostic ? "! " : editor.vcsChangedLine(row) ? "+ " : "  ";
            String digits = Integer.toString(document.documentRow(row) + 1);
            String padding = " ".repeat(width - 3 - digits.length());
            lines.add(Line.from(Span.raw(marker), Span.styled(padding + digits, row == caret ? current : number),
                    Span.raw(" ")));
        }
        return Text.from(lines);
    }

    private static Style roleStyle(RenderContext context, String role) {
        return context.resolveStyle(role, role).map(CssStyleResolver::toStyle).orElse(Style.EMPTY);
    }

    private static String fileName(String file) {
        String normalized = file.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private static String vcsMarker(VersionControlCapability.State state) {
        return switch (state) {
            case ADDED -> "A";
            case DELETED -> "D";
            case MODIFIED -> "M";
            case UNTRACKED -> "?";
            case UNMERGED -> "!";
        };
    }

    private static String language(String file) {
        int dot = file.lastIndexOf('.');
        return dot < 0 ? "text" : file.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    @Override public EventResult handleKeyEvent(KeyEvent event, boolean focused) {
        synchronized (editor) {
            if (editor.closeConfirmationPending()) {
                if (event.isCharIgnoreCase('y')) { editor.confirmClose(true); return EventResult.HANDLED; }
                if (event.isCharIgnoreCase('n') || event.isKey(KeyCode.ESCAPE)) { editor.confirmClose(false); return EventResult.HANDLED; }
            }
            if (editor.menuNavigationActive()) { return EventResult.UNHANDLED; }
            if (reserved(event)) { return EventResult.UNHANDLED; }
            EditorDocument document = editor.document();
            if (document == null) { return EventResult.UNHANDLED; }
            // Bind the current document on the input path too; events can arrive
            // before the first render or immediately after a tab switch.
            textArea.state(document.state());
            EventResult[] resultHolder = { EventResult.UNHANDLED };
            document.mutate(() -> {
                // The widget edits TextAreaState directly, so wrap the event itself
                // to keep undo history and diagnostic locations in sync.
                resultHolder[0] = textArea.handleKeyEvent(event, focused);
            });
            boolean handled = resultHolder[0].isHandled();
            if (handled) { editor.changed(); }
            return handled ? EventResult.HANDLED : EventResult.UNHANDLED;
        }
    }

    private static boolean reserved(KeyEvent event) {
        if (event.hasAlt()) { return true; }
        if (event.hasCtrl() && "sz yf wop".replace(" ", "").indexOf(Character.toLowerCase(event.character())) >= 0) { return true; }
        return event.isKey(KeyCode.F1) || event.isKey(KeyCode.F8) || event.isKey(KeyCode.F10) || event.isKey(KeyCode.F12);
    }
}
