package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;

import java.util.function.Supplier;

/**
 * First row block of the editor pane. Without an open file it is the plain
 * {@link PaneHeader}; with open files it is the editor tab bar, which replaces
 * the header row and the tab strip together. Its height follows the state of
 * the editor at every layout pass, so the pane body always gets the rows the
 * current header takes.
 */
final class EditorHeader implements Element {

    private final String paneId;
    private final PaneHeader paneHeader;
    private final BuiltinEditor editor;
    private final Supplier<String> focusMark;

    EditorHeader(String paneId, PaneHeader paneHeader, BuiltinEditor editor, Supplier<String> focusMark) {
        this.paneId = paneId;
        this.paneHeader = paneHeader;
        this.editor = editor;
        this.focusMark = focusMark;
    }

    @Override
    public void render(Frame frame, Rect area, RenderContext context) {
        if (showsTabs()) {
            boolean focused = context.isFocused(paneId) || context.isFocused(BuiltinEditor.TEXT_AREA_ID);
            editor.renderTabBar(frame, area, context, focused, focusMark.get());
        } else {
            paneHeader.render(frame, area, context);
        }
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
        return showsTabs() ? Size.of(availableWidth, BuiltinEditor.TAB_BAR_ROWS)
                : paneHeader.preferredSize(availableWidth, availableHeight, context);
    }

    private boolean showsTabs() {
        return editor != null && editor.hasDocument();
    }
}
