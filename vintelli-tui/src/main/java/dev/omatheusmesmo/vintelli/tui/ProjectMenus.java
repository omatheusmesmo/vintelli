package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.menu.MenuEntry;
import dev.omatheusmesmo.vintelli.tui.menu.MenuPopup;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.event.KeyEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The drop-down menus of the project pane header: the view menu, the New menu
 * and the options menu. Each one is a single {@link MenuPopup}, so the arrows,
 * Enter, Escape and the pointer behave like the shell menus, and the entries
 * carry the action ids that the header dispatches.
 */
final class ProjectMenus {

    static final String VIEW = "View";
    static final String NEW = "New";
    static final String OPTIONS = "Options";

    private final Map<String, MenuPopup> popups = new LinkedHashMap<>();
    private MenuPopup open;
    private int anchorX;

    ProjectMenus(Supplier<List<MenuEntry>> entries, Function<String, String> shortcuts) {
        for (String menu : List.of(VIEW, NEW, OPTIONS)) {
            popups.put(menu, new MenuPopup(List.of(menu), entries, shortcuts));
        }
    }

    /** Opens the menu hanging from the header column {@code column}. */
    void open(String menu, int column) {
        close();
        open = popups.get(menu);
        open.open(0);
        anchorX = column;
    }

    boolean isOpen() {
        return open != null && open.isOpen();
    }

    void close() {
        if (open != null) {
            open.close();
            open = null;
        }
    }

    boolean contains(int x, int y) {
        return isOpen() && open.contains(x, y);
    }

    Optional<String> click(int x, int y) {
        Optional<String> action = open.click(x, y);
        settle();
        return action;
    }

    Optional<String> handleKey(KeyEvent key) {
        Optional<String> action = open.handleKey(key);
        settle();
        return action;
    }

    /** Draws the open menu on the project pane, below the header row of {@code area}. */
    void render(Frame frame, Rect area) {
        if (isOpen()) {
            open.render(frame, area, anchorX - area.x());
        }
    }

    private void settle() {
        if (open != null && !open.isOpen()) {
            open = null;
        }
    }
}
