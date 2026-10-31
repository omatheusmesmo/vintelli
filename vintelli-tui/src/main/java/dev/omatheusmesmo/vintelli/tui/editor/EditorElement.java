package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.toolkit.elements.Row;
import dev.tamboui.toolkit.elements.TabsElement;
import dev.tamboui.toolkit.elements.TextElement;
import dev.tamboui.toolkit.elements.TextAreaElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.tabs.TabsState;

import java.util.ArrayList;
import java.util.List;

/** Toolkit view for the built-in editor. Calls into document state are serialized by its owner. */
final class EditorElement implements Element {
    private final BuiltinEditor editor;
    private final TextAreaElement textArea = Toolkit.textArea();
    private final MenuAwareTextArea menuAwareTextArea;
    private final TabsState tabState = new TabsState();
    private int lastRenderedTab = -1;
    private Rect renderedArea = Rect.ZERO;

    EditorElement(BuiltinEditor editor) {
        this.editor = editor;
        textArea.id(null).focusable(false);
        menuAwareTextArea = new MenuAwareTextArea();
    }

    private final class MenuAwareTextArea extends StyledElement<MenuAwareTextArea> {
        private MenuAwareTextArea() {
            id("editor-text-area").focusable().fill();
        }

        @Override
        protected void renderContent(Frame frame, Rect area, RenderContext context) {
            textArea.render(frame, area, context);
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
                column.add(Toolkit.text("Open a file from Project or Go to File").fill());
            } else {
                ListView tabs = tabs();
                column.add(tabs.element);
                Row body = Toolkit.row().fill();
                int gutterWidth = Integer.toString(document.lineCount()).length() + 3;
                int bodyHeight = Math.max(0, area.height() - 2);
                document.state().ensureCursorVisible(bodyHeight, Math.max(0, area.width() - gutterWidth));
                body.add(Toolkit.richText(gutter(document, bodyHeight, gutterWidth))
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

    private ListView tabs() {
        var tabs = editor.tabs();
        Integer selected = tabState.selected();
        if (selected != null && selected != lastRenderedTab && selected >= 0 && selected < tabs.size()) {
            editor.selectTab(selected);
            tabs = editor.tabs();
        }
        int active = 0;
        for (int index = 0; index < tabs.size(); index++) {
            if (tabs.get(index).active()) {
                active = index;
                break;
            }
        }
        var labels = tabs.stream().map(tab -> (tab.active() ? "[" : " ") + tab.file().replace('\\', '/')
                + editor.vcsState(tab.file()).map(state -> " " + vcsMarker(state)).orElse("")
                + (tab.dirty() ? " *" : "") + (tab.active() ? "]" : " ")).toList();
        tabState.select(active);
        lastRenderedTab = active;
        TabsElement element = Toolkit.tabs(labels).id("editor-tabs").length(1).divider(" ").state(tabState);
        return new ListView(element);
    }

    private Text gutter(EditorDocument document, int height, int width) {
        List<Line> lines = new ArrayList<>();
        int first = document.state().scrollRow();
        int end = Math.min(document.state().lineCount(), first + height);
        for (int row = first; row < end; row++) {
            final int currentRow = row;
            boolean diagnostic = document.hasDiagnosticAt(currentRow);
            String marker = diagnostic ? "! " : editor.vcsChangedLine(currentRow) ? "+ " : "  ";
            String number = Integer.toString(document.documentRow(currentRow) + 1);
            lines.add(Line.from(marker + " ".repeat(width - 3 - number.length()) + number + " "));
        }
        return Text.from(lines);
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
        return dot < 0 ? "text" : file.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
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

    private record ListView(Element element) {}
}
