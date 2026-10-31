package dev.omatheusmesmo.vintelli.api.statusbar;

import java.util.Objects;

/** Plain status bar data contributed by a tool window, provider, or integration. */
public record StatusBarWidget(String id, int order, String text, String compactText) {

    public StatusBarWidget {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A status bar widget needs an id");
        }
        text = Objects.requireNonNull(text, "text");
        compactText = Objects.requireNonNull(compactText, "compactText");
    }
}
