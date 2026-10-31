package dev.omatheusmesmo.vintelli.tui;

import java.util.List;
import java.util.function.Supplier;

import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidget;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Constraint;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.elements.TextElement;

/** TamboUI element that resolves live widgets and fits them to the rendered area. */
final class StatusBarElement implements Element {

    private final Supplier<List<StatusBarWidget>> widgets;

    StatusBarElement(Supplier<List<StatusBarWidget>> widgets) {
        this.widgets = widgets;
    }

    @Override
    public void render(Frame frame, Rect area, RenderContext context) {
        TextElement text = Toolkit.text(StatusBarLayout.render(widgets.get(), area.width()))
                .id("status-bar")
                .length(1);
        text.render(frame, area, context);
    }

    @Override
    public Constraint constraint() {
        return Constraint.length(1);
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
        return Size.of(availableWidth, 1);
    }

    @Override
    public String id() {
        return "status-bar";
    }
}
