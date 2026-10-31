package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.omatheusmesmo.vintelli.tui.menu.MenuEntry;

import java.util.List;

/**
 * SPI for one tool window in the TUI shell, such as Run, Terminal or Problems.
 * Implementations are CDI beans of the modules that render the shell, so the
 * shell collects every exposed {@code ToolWindow} and builds its tab bar from
 * the result: adding a tool window never requires a shell change.
 *
 * <p>The SPI lives here and not in {@code vintelli-api} because the render
 * and key handling signatures use TamboUI types; the shell state read model
 * that leaves the TUI layer is the TamboUI-free
 * {@link dev.omatheusmesmo.vintelli.api.ShellStateSnapshot}.
 *
 * <p>Tab order is deterministic: the registry sorts by ascending
 * {@link #ordinal()} and ties break by the natural order of {@link #id()}.
 *
 * <p>The shortcut is the single character that selects the tab directly.
 * Registry validation rejects duplicates and the shell reserved keys
 * {@code j}, {@code k} and {@code q}. While the active window reports
 * {@link #capturesInput()} as true, the shell passes every key to it except
 * the keys that must stay reachable everywhere: escape ({@link KeyCode#ESCAPE})
 * hands focus back to the shell navigation, and the reserved keymap chords
 * (the {@code SearchEverywhere}, {@code KeymapHelp} and
 * {@code Activate<Name>ToolWindow} actions, chord triggers only, never bare
 * printable characters) route to the shell actions instead of the window, so
 * switching stays possible without bare digit keys no matter which input has
 * focus. {@code quit} is deliberately not reserved: a capturing terminal
 * window keeps Ctrl+C for its own signal. The shell keeps the focus until the
 * tab is reselected, so the capturing window receives no other keys in
 * between.
 *
 * <p>Lifecycle, focus, status bar and menu bar hooks will be added later as
 * {@code default} methods, so new implementations keep compiling while the
 * interface grows.
 */
public interface ToolWindow {

    /**
     * Dock anchor for the tool window: left, right, or bottom stripe.
     * The tab bar is always rendered on the bottom stripe; this anchor
     * controls where the tool window's content panel appears when active.
     */
    enum Anchor {
        LEFT,
        RIGHT,
        BOTTOM
    }

    default List<MenuEntry> menuEntries() {
        return List.of();
    }

    /**
     * Stable identifier of the tool window, unique across the registry.
     */
    String id();

    /**
     * Human readable title shown in the tab bar and as the content block title.
     */
    String title();

    /**
     * Character that selects this tool window directly, for example {@code '1'}.
     */
    char shortcut();

    /**
     * Declarative tab position; lower values come first. Ties break by {@link #id()}.
     */
    int ordinal();

    /**
     * Dock anchor where this tool window's content panel is rendered.
     * Defaults to {@link Anchor#BOTTOM} for backward compatibility.
     */
    default Anchor anchor() {
        return Anchor.BOTTOM;
    }

    /**
     * Renders the tool window into the content area the shell assigns to the
     * selected tab.
     */
    void render(Frame frame, Rect area);

    /**
     * Handles a key event while this tool window is active. Returns {@code true}
     * to consume the event and keep the shell from applying its own shortcuts,
     * or {@code false} to let the shell fall back to its defaults.
     */
    boolean handleKey(KeyEvent event);

    /**
     * Claims the whole keyboard while this tool window is active: the shell
     * routes every key to {@link #handleKey(KeyEvent)} except escape, which
     * returns focus to the shell navigation, and except the reserved keymap
     * chords listed in the interface contract. Choose this only for windows
     * that need full text entry, such as terminal or chat input.
     */
    default boolean capturesInput() {
        return false;
    }
}
