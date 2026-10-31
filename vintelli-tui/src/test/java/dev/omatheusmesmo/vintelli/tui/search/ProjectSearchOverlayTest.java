package dev.omatheusmesmo.vintelli.tui.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import dev.omatheusmesmo.vintelli.api.search.ProjectSearch;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch.FileMatch;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch.SearchTask;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch.TextMatch;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

class ProjectSearchOverlayTest {

    @Test
    void confirmsSelectedFileAndClosesOverlay() {
        FakeSearch search = new FakeSearch();
        ProjectSearchModel model = new ProjectSearchModel(search);
        ProjectSearchOverlay overlay = new ProjectSearchOverlay(model);
        model.open(ProjectSearchModel.Mode.FILES);
        search.fileCallbacks.getLast().accept(List.of(
                new FileMatch(Path.of("src/First.java"), 1),
                new FileMatch(Path.of("src/Second.java"), 2)));

        assertTrue(overlay.handleKey(KeyEvent.ofKey(KeyCode.DOWN)).isEmpty());
        assertEquals(Optional.of(new ProjectSearchOverlay.Target(Path.of("src/Second.java"), 0)),
                overlay.handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
        assertFalse(model.isOpen());
    }

    @Test
    void confirmsTextMatchAtItsZeroBasedLineAndEscapeCloses() {
        FakeSearch search = new FakeSearch();
        ProjectSearchModel model = new ProjectSearchModel(search);
        ProjectSearchOverlay overlay = new ProjectSearchOverlay(model);
        model.open(ProjectSearchModel.Mode.TEXT);
        TextMatch match = new TextMatch(Path.of("src/Main.java"), 17, 4, "needle");
        search.textCallbacks.getLast().accept(List.of(match));

        assertEquals(Optional.of(new ProjectSearchOverlay.Target(match.file(), match.line())),
                overlay.handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
        assertFalse(model.isOpen());

        model.open(ProjectSearchModel.Mode.FILES);
        assertTrue(overlay.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE)).isEmpty());
        assertFalse(model.isOpen());
    }

    private static final class FakeSearch implements ProjectSearch {
        private final List<Consumer<List<FileMatch>>> fileCallbacks = new ArrayList<>();
        private final List<Consumer<List<TextMatch>>> textCallbacks = new ArrayList<>();

        @Override
        public SearchTask files(String query, Consumer<List<FileMatch>> results) {
            fileCallbacks.add(results);
            return new FakeTask();
        }

        @Override
        public SearchTask text(String query, boolean regex, Consumer<List<TextMatch>> results) {
            textCallbacks.add(results);
            return new FakeTask();
        }

        @Override
        public List<Path> listFiles() {
            return List.of();
        }
    }

    private static final class FakeTask implements SearchTask {
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        @Override public void cancel() { }
        @Override public boolean cancelled() { return false; }
        @Override public CompletionStage<Void> completion() { return completion; }
    }
}
