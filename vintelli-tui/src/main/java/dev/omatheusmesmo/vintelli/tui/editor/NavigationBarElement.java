package dev.omatheusmesmo.vintelli.tui.editor;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.TabsElement;
import dev.tamboui.widgets.tabs.TabsState;

import java.util.List;

/** Clickable path and symbol segments above the built-in editor. */
final class NavigationBarElement extends StyledElement<NavigationBarElement> {

    private static final String DIVIDER = " › ";

    private final TabsState state = new TabsState();
    private List<String> labels = List.of();
    private Rect renderedArea = Rect.ZERO;

    NavigationBarElement() {
        id("editor-navigation-bar").focusable();
    }

    void segments(List<String> labels, int selected) {
        this.labels = List.copyOf(labels);
        if (!this.labels.isEmpty()) {
            state.select(Math.clamp(selected, 0, this.labels.size() - 1));
        }
    }

    boolean hasSegments() {
        return !labels.isEmpty();
    }

    int segmentAt(int x, int y) {
        if (labels.isEmpty() || y < renderedArea.y() || y >= renderedArea.y() + renderedArea.height()
                || x < renderedArea.x() || x >= renderedArea.x() + renderedArea.width()) {
            return -1;
        }
        int cursor = renderedArea.x();
        int dividerWidth = CharWidth.of(DIVIDER);
        for (int index = 0; index < labels.size(); index++) {
            int end = cursor + CharWidth.of(labels.get(index));
            if (x >= cursor && x < end) {
                return index;
            }
            cursor = end + dividerWidth;
        }
        return -1;
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
        renderedArea = area;
        if (labels.isEmpty()) {
            return;
        }
        TabsElement tabs = Toolkit.tabs(labels)
                .divider(DIVIDER)
                .padding("", "")
                .state(state);
        tabs.render(frame, area, context);
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
        return Size.of(availableWidth, labels.isEmpty() ? 0 : 1);
    }
}
