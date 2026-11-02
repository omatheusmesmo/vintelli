package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Behavior assertions shared by app-level Pilot interaction contracts. */
final class ToolWindowInteractionAssertions {

    private ToolWindowInteractionAssertions() {
    }

    static void assertActivationParity(
            String contract,
            Observation expected,
            SessionFactory keyboardSession,
            Consumer<Session> keyboardInput,
            SessionFactory pointerSession,
            Consumer<Session> pointerInput) throws Exception {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(expected, "expected");
        assertFalse(expected.selectedItem().isBlank(), contract + " must expect a concrete selection");
        assertFalse(expected.namedAction().isBlank(), contract + " must expect a named action");
        assertFalse(expected.focusedWindow().isBlank(), contract + " must expect an explicit focused window");
        assertFalse(expected.renderedSelection().isBlank(), contract + " must expect a rendered selection");

        Observation keyboard = observe(keyboardSession, keyboardInput, contract + " keyboard");
        Observation pointer = observe(pointerSession, pointerInput, contract + " pointer");

        assertEquals(expected.values(), keyboard.values(), contract + " keyboard behavior\n" + keyboard.diagnostic());
        assertEquals(expected.values(), pointer.values(), contract + " pointer behavior\n" + pointer.diagnostic());
    }

    private static Observation observe(SessionFactory sessionFactory,
            Consumer<Session> input, String label)
            throws Exception {
        try (Session session = sessionFactory.open()) {
            input.accept(session);
            session.fixture().pilot().pause();
            Observation actual = session.observation().get();
            Objects.requireNonNull(actual, label + " observation");
            return actual;
        }
    }

    record Observation(String selectedItem, String namedAction, String focusedWindow,
            int scrollOffset, String renderedSelection, String diagnostic) {
        Observation(String selectedItem, String namedAction, String focusedWindow,
                int scrollOffset, String renderedSelection) {
            this(selectedItem, namedAction, focusedWindow, scrollOffset, renderedSelection, "");
        }

        Observation {
            Objects.requireNonNull(selectedItem, "selectedItem");
            Objects.requireNonNull(namedAction, "namedAction");
            Objects.requireNonNull(focusedWindow, "focusedWindow");
            Objects.requireNonNull(renderedSelection, "renderedSelection");
            Objects.requireNonNull(diagnostic, "diagnostic");
        }

        java.util.List<Object> values() {
            return java.util.List.of(selectedItem, namedAction, focusedWindow, scrollOffset, renderedSelection);
        }
    }

    @FunctionalInterface
    interface SessionFactory {
        Session open() throws Exception;
    }

    record Session(ToolWindowPilotFixture fixture, Supplier<Observation> observation, Object context)
            implements AutoCloseable {
        Session {
            Objects.requireNonNull(fixture, "fixture");
            Objects.requireNonNull(observation, "observation");
        }

        Session(ToolWindowPilotFixture fixture, Supplier<Observation> observation) {
            this(fixture, observation, null);
        }

        <T> T context(Class<T> type) {
            return type.cast(context);
        }

        @Override
        public void close() throws Exception {
            try {
                fixture.close();
            } finally {
                if (context instanceof AutoCloseable closeable) {
                    closeable.close();
                }
            }
        }
    }
}
