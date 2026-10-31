package dev.omatheusmesmo.vintelli.tui.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import dev.omatheusmesmo.vintelli.api.search.ProjectSearch;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch.FileMatch;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch.SearchTask;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch.TextMatch;

class ProjectSearchModelTest {

    @Test
    void queryUpdatesCancelPriorWorkAndIgnoreItsLateSnapshots() throws Exception {
        FakeSearch search = new FakeSearch();
        ProjectSearchModel model = new ProjectSearchModel(search);
        model.open(ProjectSearchModel.Mode.FILES);
        model.type('m');
        assertTrue(search.tasks.getFirst().cancelledSignal.await(1, TimeUnit.SECONDS));
        model.type('a');
        search.fileCallbacks.get(1).accept(List.of(new FileMatch(Path.of("stale.java"), 1)));
        search.fileCallbacks.get(2).accept(List.of(new FileMatch(Path.of("Main.java"), 2)));
        assertEquals("ma", model.query());
        assertEquals(Path.of("Main.java"), model.selectedFile().file());
        model.close();
        assertFalse(model.isOpen());
        assertTrue(search.tasks.getLast().cancelledSignal.await(1, TimeUnit.SECONDS));
    }

    @Test
    void textModeSupportsRegexAndSelectionClampsToStreamedResults() {
        FakeSearch search = new FakeSearch();
        ProjectSearchModel model = new ProjectSearchModel(search);
        model.open(ProjectSearchModel.Mode.TEXT);
        model.type('[');
        model.toggleRegex();
        assertTrue(search.regex.getLast());
        List<TextMatch> results = List.of(new TextMatch(Path.of("a.txt"), 3, 2, "x [y]"),
                new TextMatch(Path.of("b.txt"), 1, 0, "[z]"));
        search.textCallbacks.getLast().accept(results);
        model.move(1);
        assertEquals(results.get(1), model.selectedText());
        search.textCallbacks.getLast().accept(List.of(results.getFirst()));
        assertEquals(0, model.selected());
    }

    @Test
    void completedEmptySearchAndInvalidRegexHaveDistinctStates() {
        FakeSearch search = new FakeSearch();
        ProjectSearchModel model = new ProjectSearchModel(search);
        model.open(ProjectSearchModel.Mode.TEXT);
        FakeTask initial = search.tasks.getLast();
        initial.completion.complete(null);
        assertFalse(model.searching());
        assertNull(model.error());

        model.toggleRegex();
        FakeTask regex = search.tasks.getLast();
        assertTrue(model.searching());
        regex.completion.completeExceptionally(new java.util.regex.PatternSyntaxException("Unclosed character class", "[", 0));
        assertFalse(model.searching());
        assertEquals("Unclosed character class near index 0\n[\n^", model.error());
    }

    @Test
    void cancellationDoesNotDeadlockWhenCallbackOwnsTaskMonitor() throws Exception {
        FakeSearch search = new FakeSearch();
        ProjectSearchModel model = new ProjectSearchModel(search);
        model.open(ProjectSearchModel.Mode.FILES);
        CountDownLatch ownsTask = new CountDownLatch(1);
        CountDownLatch continueCallback = new CountDownLatch(1);
        FakeTask task = search.tasks.getFirst();
        Thread callback = Thread.ofPlatform().start(() -> {
            synchronized (task) {
                ownsTask.countDown();
                try {
                    continueCallback.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                search.fileCallbacks.getFirst().accept(List.of(new FileMatch(Path.of("old.java"), 1)));
            }
        });
        assertTrue(ownsTask.await(1, TimeUnit.SECONDS));
        synchronized (model) {
            continueCallback.countDown();
            model.type('n');
        }
        callback.join(1_000);
        assertFalse(callback.isAlive());
        assertTrue(task.cancelledSignal.await(1, TimeUnit.SECONDS));
        assertTrue(task.cancelled);
    }

    private static final class FakeSearch implements ProjectSearch {
        private final java.util.ArrayList<Consumer<List<FileMatch>>> fileCallbacks = new java.util.ArrayList<>();
        private final java.util.ArrayList<Consumer<List<TextMatch>>> textCallbacks = new java.util.ArrayList<>();
        private final java.util.ArrayList<Boolean> regex = new java.util.ArrayList<>();
        private final java.util.ArrayList<FakeTask> tasks = new java.util.ArrayList<>();

        @Override
        public SearchTask files(String query, Consumer<List<FileMatch>> results) {
            FakeTask task = new FakeTask();
            fileCallbacks.add(snapshot -> {
                synchronized (task) {
                    results.accept(snapshot);
                }
            });
            tasks.add(task);
            return task;
        }

        @Override
        public SearchTask text(String query, boolean regexMode, Consumer<List<TextMatch>> results) {
            regex.add(regexMode);
            FakeTask task = new FakeTask();
            textCallbacks.add(snapshot -> {
                synchronized (task) {
                    results.accept(snapshot);
                }
            });
            tasks.add(task);
            return task;
        }

        @Override public List<Path> listFiles() { return List.of(); }
    }

    private static final class FakeTask implements SearchTask {
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final CountDownLatch cancelledSignal = new CountDownLatch(1);
        private boolean cancelled;
        @Override public synchronized void cancel() {
            cancelled = true;
            cancelledSignal.countDown();
        }
        @Override public boolean cancelled() { return cancelled; }
        @Override public java.util.concurrent.CompletionStage<Void> completion() { return completion; }
    }
}
