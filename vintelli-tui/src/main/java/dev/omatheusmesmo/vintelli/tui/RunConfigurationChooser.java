package dev.omatheusmesmo.vintelli.tui;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import dev.omatheusmesmo.vintelli.api.capability.RunCapability.RunConfiguration;
import dev.omatheusmesmo.vintelli.tui.menu.MenuEntry;
import dev.omatheusmesmo.vintelli.tui.menu.MenuPopup;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.focus.FocusManager;
import dev.tamboui.tui.event.KeyEvent;

/** Owns the configuration popup and its event-time focus handoff. */
final class RunConfigurationChooser {

    static final String ID = "run-configuration-picker";
    private static final String MENU = "Run configurations";

    private final MenuPopup popup;
    private FocusManager focusManager;
    private String previousFocus;

    RunConfigurationChooser(Supplier<List<RunConfiguration>> configurations) {
        popup = new MenuPopup(List.of(MENU), () -> configurations.get().stream()
                .map(configuration -> new MenuEntry(MENU, configuration.identifier(), configuration.identifier())).toList(), ignored -> "");
    }

    void open(String selected, FocusManager manager) {
        focusManager = manager;
        if (manager != null) {
            previousFocus = manager.focusedId();
            manager.setFocus(ID);
        }
        popup.open(0, selected);
    }

    boolean isOpen() {
        return popup.isOpen();
    }

    void close() {
        popup.close();
        restoreFocus();
    }

    Optional<String> handleKey(KeyEvent key) {
        Optional<String> selected = popup.handleKey(key);
        if (!popup.isOpen()) restoreFocus();
        return selected;
    }

    Optional<String> click(int x, int y) {
        Optional<String> selected = popup.click(x, y);
        if (!popup.isOpen()) restoreFocus();
        return selected;
    }

    void render(Frame frame, Rect area, int column) {
        popup.render(frame, area, column);
    }

    private void restoreFocus() {
        if (focusManager != null) focusManager.setFocus(previousFocus);
        focusManager = null;
        previousFocus = null;
    }
}
