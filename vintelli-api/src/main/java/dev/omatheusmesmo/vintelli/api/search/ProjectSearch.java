package dev.omatheusmesmo.vintelli.api.search;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Project-relative search results. Callbacks run on a worker, never the render thread. */
public interface ProjectSearch {

    /** Streams cumulative snapshots ordered by descending fuzzy score, then path. */
    SearchTask files(String query, Consumer<List<FileMatch>> results);

    /** Streams cumulative snapshots ordered by path and position; positions are zero based. */
    SearchTask text(String query, boolean regex, Consumer<List<TextMatch>> results);

    /** Blocking discovery for background project-tree loading, honoring the same ignore rules. */
    List<Path> listFiles();

    /** Project-relative directories for the tree, including empty directories. */
    default List<Path> listDirectories() {
        return List.of();
    }

    record FileMatch(Path file, int score) {
    }

    record TextMatch(Path file, int line, int column, String text) {
    }

    interface SearchTask {
        /** Stops delivery before returning and interrupts the worker. */
        void cancel();

        boolean cancelled();

        /** Completes after scanning ends, exceptionally on a query or filesystem failure. */
        CompletionStage<Void> completion();
    }
}
