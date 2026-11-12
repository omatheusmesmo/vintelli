package dev.omatheusmesmo.vintelli.tui.editor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Copy and paste of the built-in editor. The editor keeps its own clipboard, so
 * copy and paste keep working without any system tool. Copy also sends the text
 * to the terminal with OSC 52, which reaches the local clipboard over SSH when
 * the terminal allows it, and writes to the system clipboard when a tool exists.
 * Paste prefers the system clipboard and falls back to the editor clipboard.
 */
final class EditorClipboard {

    private static final String OSC52_PREFIX = "\u001b]52;c;";
    private static final String OSC52_END = "\u0007";

    private final SystemClipboard system;
    private final Consumer<String> terminal;
    private String internal = "";

    EditorClipboard(SystemClipboard system, Consumer<String> terminal) {
        this.system = system;
        this.terminal = terminal;
    }

    /**
     * Copies the text and returns the status line that states where it went.
     */
    String copy(String text) {
        internal = text;
        terminal.accept(OSC52_PREFIX
                + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + OSC52_END);
        if (!system.available()) {
            return "Copied to the editor clipboard and the terminal (OSC 52): no system clipboard found";
        }
        if (!system.write(text)) {
            return "Copied to the editor clipboard and the terminal (OSC 52): the system clipboard tool failed";
        }
        return "Copied to the system clipboard";
    }

    /**
     * The text to paste and where it came from, so the status line can state the fallback.
     */
    Optional<Paste> paste() {
        if (system.available()) {
            Optional<String> read = system.read();
            if (read.isPresent()) {
                return Optional.of(new Paste(read.get(), "the system clipboard"));
            }
        }
        if (internal.isEmpty()) {
            return Optional.empty();
        }
        String origin = system.available()
                ? "the editor clipboard: the system clipboard tool failed"
                : "the editor clipboard only: no system clipboard found";
        return Optional.of(new Paste(internal, origin));
    }

    record Paste(String text, String origin) {
    }
}
