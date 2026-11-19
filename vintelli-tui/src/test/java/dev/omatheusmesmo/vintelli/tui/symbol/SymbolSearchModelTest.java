package dev.omatheusmesmo.vintelli.tui.symbol;

import static dev.omatheusmesmo.vintelli.tui.symbol.SymbolFixtures.awaitSymbols;
import static dev.omatheusmesmo.vintelli.tui.symbol.SymbolFixtures.names;
import static dev.omatheusmesmo.vintelli.tui.symbol.SymbolFixtures.symbol;
import static dev.omatheusmesmo.vintelli.tui.symbol.SymbolFixtures.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.WorkspaceSymbol;

class SymbolSearchModelTest {

    @Test
    void typingPausesBeforeAskingSoOnlyTheSettledQueryReachesTheServer() throws Exception {
        RecordingSource source = new RecordingSource(List.of(type("AbstractController", "com.example")));
        SymbolSearchModel model = new SymbolSearchModel(source, Duration.ofMillis(250));
        model.open(SymbolSearchModel.Scope.ALL);

        model.type('A');
        model.type('b');
        model.type('C');
        awaitSymbols(model);

        assertEquals(List.of("AbC"), source.queries());
    }

    @Test
    void aLateAnswerToAnOlderQueryNeverReplacesTheRowsOfTheNewerOne() throws Exception {
        CountDownLatch oldQueryAsked = new CountDownLatch(1);
        CompletableFuture<List<WorkspaceSymbol>> oldAnswer = new CompletableFuture<>();
        SymbolSearchModel model = new SymbolSearchModel(query -> {
            if ("A".equals(query)) {
                oldQueryAsked.countDown();
                return CapabilityResult.ok(oldAnswer.join());
            }
            return CapabilityResult.ok(List.of(type("Fresh", "com.example")));
        }, Duration.ZERO);
        model.open(SymbolSearchModel.Scope.ALL);

        model.type('A');
        assertTrue(oldQueryAsked.await(5, TimeUnit.SECONDS));
        model.type('b');
        awaitSymbols(model);
        oldAnswer.complete(List.of(type("Stale", "com.example")));
        Thread.sleep(200);

        assertEquals(List.of("Fresh"), names(model));
    }

    @Test
    void anEmptyQueryIsNeverSentEvenAfterADeletedOne() throws Exception {
        RecordingSource source = new RecordingSource(List.of(type("Any", "")));
        SymbolSearchModel model = new SymbolSearchModel(source, Duration.ofMillis(100));
        model.open(SymbolSearchModel.Scope.ALL);

        model.type('x');
        model.backspace();
        Thread.sleep(300);

        assertTrue(source.queries().isEmpty(), source.queries().toString());
        assertFalse(model.view().searching());
    }

    @Test
    void goToClassKeepsOnlyClassesInterfacesEnumsAndStructs() throws Exception {
        RecordingSource source = new RecordingSource(List.of(type("Widget", "ui"),
                symbol("paint", 6, "Widget"),
                symbol("Shape", SymbolKinds.INTERFACE, "ui"), symbol("Color", SymbolKinds.ENUM, "ui")));
        SymbolSearchModel model = new SymbolSearchModel(source, Duration.ZERO);
        model.open(SymbolSearchModel.Scope.TYPES);

        model.type('W');
        awaitSymbols(model);

        assertEquals(List.of("Widget", "Shape", "Color"), names(model));
    }

    @Test
    void goToSymbolKeepsEveryKind() throws Exception {
        RecordingSource source = new RecordingSource(List.of(type("Widget", "ui"), symbol("paint", 6, "Widget")));
        SymbolSearchModel model = new SymbolSearchModel(source, Duration.ZERO);
        model.open(SymbolSearchModel.Scope.ALL);

        model.type('w');
        awaitSymbols(model);

        assertEquals(List.of("Widget", "paint"), names(model));
    }

    @Test
    void aFailedAnswerIsKeptAsTheErrorWithoutRows() throws Exception {
        SymbolSearchModel model = new SymbolSearchModel(query -> CapabilityResult.failed(
                new CapabilityFailure("unavailable", "the language server is not running")), Duration.ZERO);
        model.open(SymbolSearchModel.Scope.TYPES);

        model.type('W');
        long deadline = System.nanoTime() + SymbolFixtures.WAIT.toNanos();
        while (model.view().searching() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertEquals("the language server is not running", model.view().error());
        assertTrue(model.view().symbols().isEmpty());
        assertNull(model.selectedSymbol());
    }

    @Test
    void closingTheOverlayDropsAnAnswerThatArrivesAfterwards() throws Exception {
        CountDownLatch asked = new CountDownLatch(1);
        CompletableFuture<List<WorkspaceSymbol>> answer = new CompletableFuture<>();
        SymbolSearchModel model = new SymbolSearchModel(query -> {
            asked.countDown();
            return CapabilityResult.ok(answer.join());
        }, Duration.ZERO);
        model.open(SymbolSearchModel.Scope.ALL);

        model.type('W');
        assertTrue(asked.await(5, TimeUnit.SECONDS));
        model.close();
        answer.complete(List.of(type("Widget", "ui")));
        Thread.sleep(200);

        assertFalse(model.isOpen());
        assertTrue(model.view().symbols().isEmpty());
    }

    private static final class RecordingSource implements SymbolSearchModel.Source {

        private final List<WorkspaceSymbol> answer;
        private final List<String> queries = new CopyOnWriteArrayList<>();

        RecordingSource(List<WorkspaceSymbol> answer) {
            this.answer = answer;
        }

        @Override
        public CapabilityResult<List<WorkspaceSymbol>> symbols(String query) {
            queries.add(query);
            return CapabilityResult.ok(answer);
        }

        List<String> queries() {
            return queries;
        }
    }
}
