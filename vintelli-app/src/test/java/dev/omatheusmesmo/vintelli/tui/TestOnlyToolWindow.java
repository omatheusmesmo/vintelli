package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Singleton;

/**
 * Tool window bean that exists only in the test classpath of the assembled
 * app: its presence proves that a newly added {@link ToolWindow} is
 * discovered through CDI and reaches the tab bar with no shell change.
 * Sorted after the four pilots by its own ordinal position.
 */
@Unremovable
@Singleton
public final class TestOnlyToolWindow implements ToolWindow {

    @Override
    public String id() {
        return "sample.extra";
    }

    @Override
    public String title() {
        return "Extra Sample";
    }

    @Override
    public char shortcut() {
        return 'x';
    }

    @Override
    public int ordinal() {
        return 1;
    }

    @Override
    public void render(Frame frame, Rect area) {
        frame.renderWidget(Paragraph.builder()
                .text(Text.from("test-only tool window"))
                .build(), area);
    }

    @Override
    public boolean handleKey(KeyEvent event) {
        return false;
    }
}
