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
import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;

class ProjectSearchControllerTest {

    private static final OverlayRegion WIDE_SCREEN = new OverlayRegion(0, 0, 80, 24);

    private final FakeSearch search = new FakeSearch();
    private final ProjectSearchModel model = new ProjectSearchModel(search);
    private final ProjectSearchController controller = new ProjectSearchController(model);

    @Test
    void enterConfirmsTheSelectedFileAndClosesTheSearch() {
        model.open(ProjectSearchModel.Mode.FILES);
        search.fileCallbacks.getLast().accept(List.of(
                new FileMatch(Path.of("src/First.java"), 1),
                new FileMatch(Path.of("src/Second.java"), 2)));

        controller.press(OverlayKey.DOWN, 0);

        assertEquals(Optional.of(new ProjectSearchController.Target(Path.of("src/Second.java"), 0)),
                controller.press(OverlayKey.ENTER, 0));
        assertFalse(model.isOpen());
    }

    @Test
    void enterOnATextMatchConfirmsItsZeroBasedLine() {
        model.open(ProjectSearchModel.Mode.TEXT);
        TextMatch match = new TextMatch(Path.of("src/Main.java"), 17, 4, "needle");
        search.textCallbacks.getLast().accept(List.of(match));

        assertEquals(Optional.of(new ProjectSearchController.Target(match.file(), match.line())),
                controller.press(OverlayKey.ENTER, 0));
    }

    @Test
    void escapeClosesWithoutConfirming() {
        model.open(ProjectSearchModel.Mode.FILES);

        assertEquals(Optional.empty(), controller.press(OverlayKey.ESCAPE, 0));
        assertFalse(model.isOpen());
    }

    @Test
    void theWindowFollowsTheSelectionPastTheVisibleRows() {
        model.open(ProjectSearchModel.Mode.FILES);
        search.fileCallbacks.getLast().accept(files(10));
        for (int index = 0; index < 5; index++) {
            controller.press(OverlayKey.DOWN, 0);
        }

        ProjectSearchController.Placement placement = controller.place(new OverlayRegion(0, 0, 80, 8)).orElseThrow();

        assertEquals(3, placement.visibleRows());
        assertEquals(3, placement.first());
    }

    @Test
    void aSearchThatIsClosedOrTooSmallIsNotPlaced() {
        assertTrue(controller.place(WIDE_SCREEN).isEmpty(), "a closed search has no panel");

        model.open(ProjectSearchModel.Mode.FILES);
        assertTrue(controller.place(new OverlayRegion(0, 0, 7, 10)).isEmpty());
    }

    @Test
    void aSecondClickOnTheSameResultOpensItWithinTheDoubleClickWindow() {
        model.open(ProjectSearchModel.Mode.FILES);
        search.fileCallbacks.getLast().accept(files(2));
        ProjectSearchController.Placement placement = controller.place(WIDE_SCREEN).orElseThrow();
        int x = placement.inner().x() + 2;
        int secondRow = placement.inner().y() + 2;

        assertEquals(Optional.empty(), controller.pointer(x, secondRow, true, 0, 1_000L));
        assertEquals(Optional.of(new ProjectSearchController.Target(Path.of("src/File1.java"), 0)),
                controller.pointer(x, secondRow, true, 0, 1_001L));
        assertFalse(model.isOpen());
    }

    @Test
    void aClickOutsideThePanelClosesTheSearch() {
        model.open(ProjectSearchModel.Mode.FILES);
        controller.place(WIDE_SCREEN);

        controller.pointer(0, 0, true, 0, 0L);

        assertFalse(model.isOpen());
    }

    private static List<FileMatch> files(int count) {
        List<FileMatch> files = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            files.add(new FileMatch(Path.of("src/File" + index + ".java"), index));
        }
        return files;
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
