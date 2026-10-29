package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.omatheusmesmo.vintelli.tui.ToolWindow;
import dev.omatheusmesmo.vintelli.tui.ToolWindowRegistry;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * Lazily built keymap of the running TUI session. The shell resolves the
 * keymap only on its first key or render, and the TUI entry point resolves
 * once before the terminal is attached, so a malformed user keymap file or an
 * unknown {@code vintelli.keymap} value fails the TUI startup with a one-line
 * message instead of aborting a headless or MCP-only process.
 */
@Unremovable
@Singleton
public final class KeymapRegistry {

    private final ToolWindowRegistry toolWindowRegistry;
    private volatile Keymap cached;

    @Inject
    public KeymapRegistry(ToolWindowRegistry toolWindowRegistry) {
        this.toolWindowRegistry = toolWindowRegistry;
    }

    /**
     * The resolved keymap. Deterministic memoization: the TUI entry point calls
     * this first, the shell reuses whatever was built.
     */
    public Keymap get() {
        Keymap keymap = cached;
        if (keymap == null) {
            List<ToolWindow> windows = toolWindowRegistry.toolWindows();
            cached = Keymap.defaultKeymap(windows);
            keymap = cached;
        }
        return keymap;
    }
}
