package dev.omatheusmesmo.vintelli.tui.menu;

import java.util.Objects;

/** One named action in a menu. Availability is refreshed whenever the menu opens. */
public record MenuEntry(String menu, String actionId, String label, String unavailableReason) {
    public MenuEntry {
        Objects.requireNonNull(menu, "menu");
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(label, "label");
        unavailableReason = unavailableReason == null ? "" : unavailableReason;
        if (menu.isBlank() || actionId.isBlank() || label.isBlank()) {
            throw new IllegalArgumentException("Menu, action id and label must not be blank");
        }
    }

    public MenuEntry(String menu, String actionId, String label) {
        this(menu, actionId, label, "");
    }

    public boolean enabled() {
        return unavailableReason.isBlank();
    }
}
