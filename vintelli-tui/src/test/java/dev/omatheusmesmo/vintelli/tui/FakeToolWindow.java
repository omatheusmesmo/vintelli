package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stand-in tool window for tests that register an implementation through the
 * registry and observe the shell reacting without any shell change. It
 * consumes every key it is given so tests can assert both routing orders.
 * Deliberately non final: a test can override {@link #render(Frame, Rect)} to
 * make the fault tolerant tool window rendering observable.
 */
class FakeToolWindow implements ToolWindow {
    private final String id;
    private final String title;
    private final char shortcut;
    private final int ordinal;
    private final String content;
    private final boolean captures;
    private final Anchor anchor;
    private final CopyOnWriteArrayList<String> handledKeys = new CopyOnWriteArrayList<>();

    FakeToolWindow(String id, String title, char shortcut, int ordinal, String content) {
        this(id, title, shortcut, ordinal, content, false, Anchor.BOTTOM);
    }

    FakeToolWindow(String id, String title, char shortcut, int ordinal, String content,
            boolean captures) {
        this(id, title, shortcut, ordinal, content, captures, Anchor.BOTTOM);
    }

    FakeToolWindow(String id, String title, char shortcut, int ordinal, String content,
            boolean captures, Anchor anchor) {
        this.id = id;
        this.title = title;
        this.shortcut = shortcut;
        this.ordinal = ordinal;
        this.content = content;
        this.captures = captures;
        this.anchor = anchor;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public char shortcut() {
        return shortcut;
    }

    @Override
    public int ordinal() {
        return ordinal;
    }

    @Override
    public Anchor anchor() {
        return anchor;
    }

    @Override
    public boolean capturesInput() {
        return captures;
    }

    @Override
    public boolean handleKey(KeyEvent event) {
        handledKeys.add(event.isKey(KeyCode.ESCAPE) ? "escape" : event.string());
        return true;
    }

    List<String> handledKeys() {
        return List.copyOf(handledKeys);
    }

    @Override
    public void render(Frame frame, Rect area) {
        frame.renderWidget(Paragraph.builder()
                .text(Text.from(content))
                .build(), area);
    }
}
