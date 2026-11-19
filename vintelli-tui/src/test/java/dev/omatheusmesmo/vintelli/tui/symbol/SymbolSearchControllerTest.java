package dev.omatheusmesmo.vintelli.tui.symbol;

import static dev.omatheusmesmo.vintelli.tui.symbol.SymbolFixtures.awaitSymbols;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.WorkspaceSymbol;
import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;

class SymbolSearchControllerTest {

    @Test
    void enterConfirmsTheSelectedSymbolAtItsLineAndClosesTheOverlay() throws Exception {
        SymbolSearchModel model = typed(List.of(SymbolFixtures.type("Widget", "ui")));
        SymbolSearchController controller = new SymbolSearchController(model);

        Optional<SymbolSearchController.Target> target = controller.press(OverlayKey.ENTER, 0);

        assertEquals(Optional.of(new SymbolSearchController.Target(Path.of("Widget.java"), 3)), target);
        assertFalse(model.isOpen());
    }

    @Test
    void aSymbolOutsideTheProjectFilesIsListedButEnterKeepsTheOverlayOpen() throws Exception {
        SymbolSearchModel model = typed(List.of(SymbolFixtures.library("ArrayList", "java.util")));
        SymbolSearchController controller = new SymbolSearchController(model);

        assertEquals(Optional.empty(), controller.press(OverlayKey.ENTER, 0));
        assertTrue(model.isOpen());
        assertEquals(List.of("ArrayList"), SymbolFixtures.names(model));
    }

    @Test
    void theArrowKeysMoveTheSelectionBeforeEnterConfirmsIt() throws Exception {
        SymbolSearchModel model = typed(List.of(SymbolFixtures.type("Widget", "ui"),
                SymbolFixtures.type("WidgetFactory", "ui")));
        SymbolSearchController controller = new SymbolSearchController(model);

        controller.press(OverlayKey.DOWN, 0);
        Optional<SymbolSearchController.Target> target = controller.press(OverlayKey.ENTER, 0);

        assertEquals(Optional.of(new SymbolSearchController.Target(Path.of("WidgetFactory.java"), 3)), target);
    }

    @Test
    void escapeClosesTheOverlayWithoutConfirmingAnything() throws Exception {
        SymbolSearchModel model = typed(List.of(SymbolFixtures.type("Widget", "ui")));
        SymbolSearchController controller = new SymbolSearchController(model);

        assertEquals(Optional.empty(), controller.press(OverlayKey.ESCAPE, 0));
        assertFalse(model.isOpen());
    }

    @Test
    void theVisibleRowsFollowTheSelectionWhenTheAnswerIsLongerThanThePanel() throws Exception {
        List<WorkspaceSymbol> many = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            many.add(SymbolFixtures.type("Type" + index, "pkg"));
        }
        SymbolSearchModel model = typed(many);
        SymbolSearchController controller = new SymbolSearchController(model);
        for (int index = 0; index < 20; index++) {
            controller.press(OverlayKey.DOWN, 0);
        }

        SymbolSearchController.Placement placed = controller.place(new OverlayRegion(0, 0, 100, 30)).orElseThrow();

        assertTrue(placed.first() <= 20 && 20 < placed.first() + placed.visibleRows(),
                "row 20 is in the visible window starting at " + placed.first());
    }

    private static SymbolSearchModel typed(List<WorkspaceSymbol> symbols) throws InterruptedException {
        SymbolSearchModel model = new SymbolSearchModel(query -> CapabilityResult.ok(symbols), Duration.ZERO);
        model.open(SymbolSearchModel.Scope.ALL);
        model.type('W');
        awaitSymbols(model);
        return model;
    }
}
