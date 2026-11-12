package dev.omatheusmesmo.vintelli.tui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Owns the modal order of the shell overlays. The command palette, the keymap
 * help, the menu popup and the project search are overlays: opening one closes
 * the others, and the most recently opened open overlay is the top overlay that
 * gets the keyboard and the pointer first. The confirmation prompts are
 * registered as prompts: they sit above every overlay and closing the overlays
 * never dismisses them, because their state stays with the island that owns it.
 * The stack holds no TamboUI type; the shell draws the layers and forwards the events.
 */
final class OverlayStack {

    enum Kind {
        COMMAND_PALETTE(false),
        KEYMAP_HELP(false),
        MENU(false),
        RUN_CONFIGURATION(false),
        PROJECT_SEARCH(false),
        GO_TO_LINE(false),
        PROJECT_DELETE(true),
        EDITOR_CLOSE(true),
        VCS_PROMPT(true);

        private final boolean prompt;

        Kind(boolean prompt) {
            this.prompt = prompt;
        }

        boolean prompt() {
            return prompt;
        }
    }

    private record Layer(BooleanSupplier open, Runnable close) {
    }

    private final Map<Kind, Layer> layers = new EnumMap<>(Kind.class);
    private final List<Kind> order = new ArrayList<>();

    void registerOverlay(Kind kind, BooleanSupplier open, Runnable close) {
        if (kind.prompt()) {
            throw new IllegalArgumentException(kind + " is a prompt, register it with registerPrompt");
        }
        layers.put(kind, new Layer(open, close));
        order.add(kind);
    }

    void registerPrompt(Kind kind, BooleanSupplier open) {
        if (!kind.prompt()) {
            throw new IllegalArgumentException(kind + " is an overlay, register it with registerOverlay");
        }
        layers.put(kind, new Layer(open, null));
    }

    /**
     * Closes every other overlay, runs the opener and makes {@code kind} the top
     * overlay. The opener is the only place that knows how the overlay is shown.
     */
    void open(Kind kind, Runnable opener) {
        requireOverlay(kind);
        for (Kind other : order) {
            if (other != kind) {
                layers.get(other).close().run();
            }
        }
        opener.run();
        order.remove(kind);
        order.add(kind);
    }

    void close(Kind kind) {
        requireOverlay(kind);
        layers.get(kind).close().run();
    }

    /** Closes every registered overlay; the prompts stay open. */
    void closeOverlays() {
        for (Kind kind : Kind.values()) {
            Layer layer = layers.get(kind);
            if (layer != null && !kind.prompt()) {
                layer.close().run();
            }
        }
    }

    boolean isOpen(Kind kind) {
        Layer layer = layers.get(kind);
        return layer != null && layer.open().getAsBoolean();
    }

    boolean overlayOpen() {
        return topOverlay().isPresent();
    }

    Optional<Kind> topOverlay() {
        for (int index = order.size() - 1; index >= 0; index--) {
            Kind kind = order.get(index);
            if (isOpen(kind)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /** The open overlays from the bottom to the top, the order they are drawn in. */
    List<Kind> openOverlays() {
        return order.stream().filter(this::isOpen).toList();
    }

    /** The first open prompt in declaration order, which is also the order the keys are checked in. */
    Optional<Kind> topPrompt() {
        for (Kind kind : Kind.values()) {
            if (kind.prompt() && isOpen(kind)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    private void requireOverlay(Kind kind) {
        if (kind.prompt() || !layers.containsKey(kind)) {
            throw new IllegalStateException("No overlay registered for " + kind);
        }
    }
}
