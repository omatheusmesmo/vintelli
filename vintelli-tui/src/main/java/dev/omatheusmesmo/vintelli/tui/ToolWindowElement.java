package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.widgets.paragraph.Paragraph;

/**
 * Toolkit wrapper around one {@link ToolWindow}: carries the stable element
 * id ({@code tool-<id>}) that Pilot tests and the stylesheet address, draws
 * fault tolerant (a broken window never takes the shell down and shows the
 * failure instead). Bottom windows use a fixed height; side windows fill the
 * space assigned by their stripe.
 */
final class ToolWindowElement extends StyledElement<ToolWindowElement> {

    private static final int TOOL_WINDOW_HEIGHT = ToolWindowHost.TOOL_WINDOW_HEIGHT;
    private static final String RENDER_FAILED_TEXT = "tool window render failed: ";

    /** What the element asks of its owner: the focus mark of the window, its area and the events it gets. */
    interface Host {

        boolean isActive(ToolWindow window);

        void rendered(ToolWindow window, Rect area);

        EventResult handle(Event event);
    }

    private final ToolWindow window;
    private final Host host;
    private final int height;

    ToolWindowElement(ToolWindow window, Host host) {
        this(window, TOOL_WINDOW_HEIGHT, host);
    }

    ToolWindowElement(ToolWindow window, int height, Host host) {
        this.window = window;
        this.host = host;
        this.height = height;
        id("tool-" + window.id());
        addClass("tool-window");
        focusable(host.isActive(window));
        if (height < 0) {
            fill();
        } else {
            length(height);
        }
    }

    ToolWindow window() {
        return window;
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
        host.rendered(window, area);
        try {
            window.render(frame, area);
        } catch (RuntimeException failure) {
            frame.renderWidget(failureParagraph(context, failure), area);
        }
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
        int width = availableWidth >= 0 ? availableWidth : 1;
        return new Size(width, height < 0 ? Math.max(0, availableHeight) : height);
    }

    @Override
    public EventResult handleKeyEvent(KeyEvent event, boolean focused) {
        return focused ? host.handle(event) : EventResult.UNHANDLED;
    }

    @Override
    public EventResult handleMouseEvent(MouseEvent event) {
        return host.handle(event);
    }

    private static Paragraph failureParagraph(RenderContext context, RuntimeException failure) {
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        return Paragraph.builder()
                .text(Text.from(RENDER_FAILED_TEXT + message))
                .style(context.currentStyle())
                .build();
    }
}
