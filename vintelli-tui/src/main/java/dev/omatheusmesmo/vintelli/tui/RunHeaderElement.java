package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;

/** The run lifecycle header is its own effect target and pointer surface. */
final class RunHeaderElement extends StyledElement<RunHeaderElement> {

    static final String ID = "run-motion-header";

    private final RunToolWindow run;
    private final ToolWindowElement.Host host;

    RunHeaderElement(RunToolWindow run, ToolWindowElement.Host host) {
        this.run = run;
        this.host = host;
        id(ID);
        length(1);
        focusable(host.isActive(run));
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
        host.rendered(run, area);
        run.renderHeader(frame, area);
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
        return new Size(Math.max(0, availableWidth), run.runState() == RunMotionState.IDLE ? 0 : 1);
    }

    @Override
    public EventResult handleKeyEvent(KeyEvent event, boolean focused) {
        return focused ? host.handle(event) : EventResult.UNHANDLED;
    }

    @Override
    public EventResult handleMouseEvent(MouseEvent event) {
        return host.handle(event);
    }
}
