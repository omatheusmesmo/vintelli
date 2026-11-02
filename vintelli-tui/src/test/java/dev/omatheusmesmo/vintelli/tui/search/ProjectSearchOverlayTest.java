package dev.omatheusmesmo.vintelli.tui.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
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

    @Test
    void resultsWithAFailedCompletionRenderAnIncompleteNoticeAfterTheMatches() throws Exception {
        FakeSearch search = new FakeSearch();
        ProjectSearchModel model = new ProjectSearchModel(search);
        ProjectSearchOverlay overlay = new ProjectSearchOverlay(model);
        model.open(ProjectSearchModel.Mode.FILES);
        search.fileCallbacks.getLast().accept(List.of(new FileMatch(Path.of("src/First.java"), 1)));
        search.lastTask.completion().toCompletableFuture()
                .completeExceptionally(new IOException("Access denied"));

        Rect area = new Rect(0, 0, 80, 24);
        Buffer buffer = Buffer.empty(area);
        overlay.render(Frame.forTesting(buffer), area);

        String rendered = contentText(buffer);
        assertTrue(rendered.contains("src/First.java"), rendered);
        assertTrue(rendered.contains("Results may be incomplete"), rendered);
        assertTrue(rendered.contains("Access denied"), rendered);
    }

    private static String bufferText(Buffer buffer) {
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < buffer.height(); y++) {
            for (int x = 0; x < buffer.width(); x++) {
                text.append(buffer.get(x, y).symbol());
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static String contentText(Buffer buffer) {
        return bufferText(buffer).replaceAll("[┌┐└┘─│]", " ").replaceAll("\\s+", " ");
    }

    private static final class FakeSearch implements ProjectSearch {
        private final List<Consumer<List<FileMatch>>> fileCallbacks = new ArrayList<>();
        private final List<Consumer<List<TextMatch>>> textCallbacks = new ArrayList<>();
        private SearchTask lastTask;

        @Override
        public SearchTask files(String query, Consumer<List<FileMatch>> results) {
            fileCallbacks.add(results);
            lastTask = new FakeTask();
            return lastTask;
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
