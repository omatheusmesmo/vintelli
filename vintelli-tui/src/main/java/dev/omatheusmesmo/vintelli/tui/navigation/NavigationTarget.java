package dev.omatheusmesmo.vintelli.tui.navigation;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One place a navigation request resolved to, with what the list rows show of it: the file name, its directory
 * relative to the project, the one based {@code line:column} and the source line, trimmed and capped. The match range
 * is the part of {@code preview} that is the symbol, or {@code -1} when the symbol is not on the previewed text.
 *
 * @param line zero based line, as the navigation capability reports it
 * @param column zero based UTF-16 column, as the navigation capability reports it
 */
public record NavigationTarget(Path file, String name, String directory, int line, int column, String preview,
        int matchStart, int matchLength) {

    public NavigationTarget {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(preview, "preview");
    }

    /** The position as the rows show it, one based. */
    public String position() {
        return (line + 1) + ":" + (column + 1);
    }
}
