package dev.omatheusmesmo.vintelli.tui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

class NavigationHistoryTest {

    private static final Path A = Path.of("/project/A.java");
    private static final Path B = Path.of("/project/B.java");
    private static final Predicate<Path> ALL_FILES = file -> true;

    @Test
    void backReturnsThePlaceTheLastJumpLeftAndForwardGoesBackToTheJumpTarget() {
        NavigationHistory history = new NavigationHistory();
        NavigationHistory.Place left = new NavigationHistory.Place(A, 10, 4);
        NavigationHistory.Place target = new NavigationHistory.Place(B, 1, 0);

        history.jumped(left, target);

        assertEquals(Optional.of(left), history.back(target, ALL_FILES));
        assertEquals(Optional.of(target), history.forward(left, ALL_FILES));
    }

    @Test
    void movesWithinFiveLinesAreNotRecordedButLongerOnesAre() {
        NavigationHistory history = new NavigationHistory();

        history.jumped(new NavigationHistory.Place(A, 10, 0), new NavigationHistory.Place(A, 15, 0));
        assertEquals(Optional.empty(), history.back(new NavigationHistory.Place(A, 15, 0), ALL_FILES));

        history.jumped(new NavigationHistory.Place(A, 10, 0), new NavigationHistory.Place(A, 16, 0));
        assertTrue(history.back(new NavigationHistory.Place(A, 16, 0), ALL_FILES).isPresent());
    }

    @Test
    void aNewJumpAfterGoingBackDropsTheForwardPlaces() {
        NavigationHistory history = new NavigationHistory();
        NavigationHistory.Place origin = new NavigationHistory.Place(A, 10, 0);
        NavigationHistory.Place definition = new NavigationHistory.Place(B, 1, 0);
        NavigationHistory.Place other = new NavigationHistory.Place(B, 50, 0);
        history.jumped(origin, definition);
        history.back(definition, ALL_FILES);

        history.jumped(origin, other);

        assertEquals(Optional.empty(), history.forward(origin, ALL_FILES));
    }

    @Test
    void theHistoryKeepsTheNewestTwentyFivePlaces() {
        NavigationHistory history = new NavigationHistory();
        for (int line = 0; line < 30; line++) {
            history.jumped(new NavigationHistory.Place(A, line * 10, 0),
                    new NavigationHistory.Place(A, line * 10 + 100, 0));
        }

        List<Integer> walked = new ArrayList<>();
        NavigationHistory.Place current = new NavigationHistory.Place(A, 1000, 0);
        Optional<NavigationHistory.Place> previous;
        while ((previous = history.back(current, ALL_FILES)).isPresent()) {
            walked.add(previous.get().line());
            current = previous.get();
        }

        assertEquals(NavigationHistory.CAPACITY, walked.size());
        assertEquals(290, walked.getFirst());
        assertEquals(50, walked.getLast());
    }

    @Test
    void consecutivePlacesOnTheSameLineCollapseIntoOne() {
        NavigationHistory history = new NavigationHistory();
        NavigationHistory.Place origin = new NavigationHistory.Place(A, 10, 0);
        NavigationHistory.Place target = new NavigationHistory.Place(B, 1, 0);

        history.jumped(origin, target);
        history.jumped(new NavigationHistory.Place(A, 10, 3), new NavigationHistory.Place(B, 1, 0));

        assertEquals(Optional.of(origin), history.back(target, ALL_FILES));
        assertEquals(Optional.empty(), history.back(origin, ALL_FILES));
    }

    @Test
    void aPlaceInADeletedFileIsSkippedWhileTheOthersStillWalk() {
        NavigationHistory history = new NavigationHistory();
        Path deleted = Path.of("/project/Deleted.java");
        history.jumped(new NavigationHistory.Place(deleted, 1, 0), new NavigationHistory.Place(A, 20, 0));
        history.jumped(new NavigationHistory.Place(A, 20, 0), new NavigationHistory.Place(A, 40, 0));
        Predicate<Path> exists = file -> !file.equals(deleted);

        Optional<NavigationHistory.Place> first = history.back(new NavigationHistory.Place(A, 40, 0), exists);
        Optional<NavigationHistory.Place> second = history.back(first.orElseThrow(), exists);

        assertEquals(20, first.orElseThrow().line());
        assertFalse(second.isPresent());
    }

    @Test
    void theLinesOfPlacesInAFollowTheEditsOfThatFile() {
        NavigationHistory history = new NavigationHistory();
        NavigationHistory.Place origin = new NavigationHistory.Place(A, 10, 0);
        NavigationHistory.Place target = new NavigationHistory.Place(B, 1, 0);
        history.jumped(origin, target);

        history.shift(A, line -> line + 2);

        assertEquals(Optional.of(new NavigationHistory.Place(A, 12, 0)), history.back(target, ALL_FILES));
    }
}
