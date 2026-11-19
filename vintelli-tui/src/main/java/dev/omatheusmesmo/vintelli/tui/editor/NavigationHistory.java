package dev.omatheusmesmo.vintelli.tui.editor;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

/**
 * The caret places Back and Forward walk, as IntelliJ keeps them. A jump records the place it left when it changes
 * file or moves the caret more than {@value #SMALL_MOVE_LINES} lines, and drops the forward places, so a new jump after
 * going back starts a new branch. Each direction keeps at most {@value #CAPACITY} places, the oldest leaving first, and
 * a place equal to the one on top of its stack (same file and line) is not stacked twice.
 */
final class NavigationHistory {

    /** A caret place: the file and the zero based line and column. */
    record Place(Path file, int line, int column) {

        boolean sameLine(Place other) {
            return other != null && file.equals(other.file) && line == other.line;
        }
    }

    static final int CAPACITY = 25;
    static final int SMALL_MOVE_LINES = 5;

    private final Deque<Place> back = new ArrayDeque<>();
    private final Deque<Place> forward = new ArrayDeque<>();

    /** Records {@code from} as the place a jump to {@code to} left, unless the jump stayed close to it. */
    void jumped(Place from, Place to) {
        if (from == null || to == null || !farApart(from, to)) {
            return;
        }
        forward.clear();
        push(back, from);
    }

    /**
     * Pops the newest back place whose file is still available, stacking {@code current} as a forward place. Places of
     * unavailable files are dropped on the way.
     */
    Optional<Place> back(Place current, Predicate<Path> available) {
        return walk(back, forward, current, available);
    }

    /** Pops the newest forward place whose file is still available, stacking {@code current} as a back place. */
    Optional<Place> forward(Place current, Predicate<Path> available) {
        return walk(forward, back, current, available);
    }

    /** Moves the lines of the places in {@code file} after an edit of that file. */
    void shift(Path file, IntUnaryOperator lines) {
        shift(back, file, lines);
        shift(forward, file, lines);
    }

    private static Optional<Place> walk(Deque<Place> from, Deque<Place> to, Place current, Predicate<Path> available) {
        while (!from.isEmpty()) {
            Place target = from.pop();
            if (!available.test(target.file()) || target.sameLine(current)) {
                continue;
            }
            if (current != null) {
                push(to, current);
            }
            return Optional.of(target);
        }
        return Optional.empty();
    }

    private static boolean farApart(Place from, Place to) {
        return !from.file().equals(to.file()) || Math.abs(from.line() - to.line()) > SMALL_MOVE_LINES;
    }

    private static void push(Deque<Place> stack, Place place) {
        if (place.sameLine(stack.peekFirst())) {
            return;
        }
        stack.addFirst(place);
        while (stack.size() > CAPACITY) {
            stack.removeLast();
        }
    }

    private static void shift(Deque<Place> stack, Path file, IntUnaryOperator lines) {
        List<Place> shifted = stack.stream()
                .map(place -> place.file().equals(file)
                        ? new Place(file, lines.applyAsInt(place.line()), place.column()) : place)
                .toList();
        stack.clear();
        stack.addAll(shifted);
    }
}
