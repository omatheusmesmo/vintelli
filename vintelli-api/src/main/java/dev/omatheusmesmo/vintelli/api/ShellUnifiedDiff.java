package dev.omatheusmesmo.vintelli.api;

import java.util.Objects;

/**
 * A unified diff the caller wants the TUI shell to render in its editor
 * panel. Plain Java types only, so surfaces such as the MCP server can push
 * it through the {@link ShellDiffViewPort} without a TamboUI dependency.
 *
 * <p>The title and the diff text are capped ({@link #MAX_TITLE_LENGTH} and
 * {@link #MAX_DIFF_LENGTH} characters) and control characters other than the
 * newline and the tab are replaced with a space, so the values are always
 * safe to paint into a terminal.
 */
public record ShellUnifiedDiff(String title, String unifiedDiff) {

    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_DIFF_LENGTH = 256 * 1024;

    public ShellUnifiedDiff {
        title = sanitize(title, "title");
        unifiedDiff = sanitize(unifiedDiff, "unifiedDiff");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must be a non-blank label");
        }
        if (unifiedDiff.isBlank()) {
            throw new IllegalArgumentException("unifiedDiff must be a non-blank unified diff");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("title must be at most " + MAX_TITLE_LENGTH + " characters");
        }
        if (unifiedDiff.length() > MAX_DIFF_LENGTH) {
            throw new IllegalArgumentException("unifiedDiff must be at most " + MAX_DIFF_LENGTH + " characters");
        }
    }

    private static String sanitize(String text, String field) {
        Objects.requireNonNull(text, field);
        StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            out.append(character == '\n' || character == '\t' || !Character.isISOControl(character) ? character : ' ');
        }
        return out.toString();
    }
}
