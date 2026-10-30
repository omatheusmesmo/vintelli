package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.widgets.paragraph.Paragraph;

/**
 * Toolkit wrapper around one {@link ToolWindow}: carries the stable element
 * id ({@code tool-<id>}) that Pilot tests and the stylesheet address, draws
 * fault tolerant (a broken window never takes the shell down and shows the
 * failure instead) and applies a fixed row height so the shell layout keeps
 * the tool window strip a known size.
 */
final class ToolWindowElement extends StyledElement<ToolWindowElement> {

    private static final int TOOL_WINDOW_HEIGHT = VintelliShell.TOOL_WINDOW_HEIGHT;
    private static final String RENDER_FAILED_TEXT = "tool window render failed: ";

    private final ToolWindow window;

    ToolWindowElement(ToolWindow window) {
        this.window = window;
        id("tool-" + window.id());
        addClass("tool-window");
        length(TOOL_WINDOW_HEIGHT);
    }

    ToolWindow window() {
        return window;
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
        try {
            window.render(frame, area);
        } catch (RuntimeException failure) {
            frame.renderWidget(failureParagraph(context, failure), area);
        }
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
        int width = availableWidth >= 0 ? availableWidth : 1;
        return new Size(width, TOOL_WINDOW_HEIGHT);
    }

    private static Paragraph failureParagraph(RenderContext context, RuntimeException failure) {
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        return Paragraph.builder()
                .text(Text.from(RENDER_FAILED_TEXT + message))
                .style(context.currentStyle())
                .build();
    }
}
