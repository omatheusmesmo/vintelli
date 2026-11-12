package dev.omatheusmesmo.vintelli.tui.editor;

import java.util.Optional;

/**
 * Access to the operating system clipboard. The built-in editor depends on this
 * seam so that tests replace the native tools with a fake.
 */
public interface SystemClipboard {

    /**
     * Whether a clipboard tool was found for this platform.
     *
     * @return true when reads and writes can reach the system clipboard
     */
    boolean available();

    /**
     * Stores the text on the system clipboard.
     *
     * @param text the text to store, never null
     * @return true when the clipboard tool accepted the text
     */
    boolean write(String text);

    /**
     * Reads the system clipboard.
     *
     * @return the clipboard text, or empty when no tool could read it
     */
    Optional<String> read();
}
