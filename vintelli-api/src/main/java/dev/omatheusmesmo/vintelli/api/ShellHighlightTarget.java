package dev.omatheusmesmo.vintelli.api;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A file and line the caller wants the TUI shell to highlight. Plain Java
 * types only, so surfaces such as the MCP server can push it through the
 * {@link ShellHighlightPort} without a TamboUI dependency.
 *
 * <p>The path must be non-blank, at most {@link #MAX_FILE_LENGTH} characters,
 * relative to the project root and without {@code ..} segments; absolute
 * paths are rejected. Every control character (the tab included: it is not a
 * legal path byte on every file system) is replaced with a space, so the
 * value is always safe to paint into a terminal. The line is displayed as
 * given and is not verified against the file length.
 */
public record ShellHighlightTarget(String file, int line) {

    public static final int MAX_FILE_LENGTH = 4096;

    public ShellHighlightTarget {
        file = sanitize(file);
        if (file.isBlank()) {
            throw new IllegalArgumentException("file must be a non-blank path");
        }
        if (file.length() > MAX_FILE_LENGTH) {
            throw new IllegalArgumentException("file must be at most " + MAX_FILE_LENGTH + " characters");
        }
        if (line < 1) {
            throw new IllegalArgumentException("line must be a 1-based line number");
        }
        if (escapesProjectRoot(file)) {
            throw new IllegalArgumentException("file must be a relative path inside the project root");
        }
    }

    private static boolean escapesProjectRoot(String file) {
        try {
            Path path = Path.of(file).normalize();
            return path.isAbsolute() || path.getRoot() != null
                    || path.toString().isEmpty() || path.startsWith("..");
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("file must be a valid path", e);
        }
    }

    private static String sanitize(String text) {
        Objects.requireNonNull(text, "file");
        StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            out.append(Character.isISOControl(character) ? ' ' : character);
        }
        return out.toString();
    }
}
