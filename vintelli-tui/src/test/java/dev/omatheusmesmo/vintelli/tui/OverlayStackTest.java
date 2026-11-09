package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.OverlayStack.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayStackTest {

    private static final class FakeLayer {

        private boolean open;
        private int closes;
        private final List<String> log;
        private final String name;

        private FakeLayer(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        void show() {
            open = true;
        }

        void close() {
            open = false;
            closes++;
            log.add(name + " closed");
        }
    }

    private final List<String> log = new ArrayList<>();
    private final OverlayStack stack = new OverlayStack();
    private final FakeLayer palette = new FakeLayer("palette", log);
    private final FakeLayer help = new FakeLayer("help", log);
    private final FakeLayer menu = new FakeLayer("menu", log);
    private final FakeLayer search = new FakeLayer("search", log);
    private final FakeLayer deletePrompt = new FakeLayer("delete", log);
    private final FakeLayer vcsPrompt = new FakeLayer("vcs", log);

    private OverlayStack registered() {
        stack.registerOverlay(Kind.COMMAND_PALETTE, () -> palette.open, palette::close);
        stack.registerOverlay(Kind.KEYMAP_HELP, () -> help.open, help::close);
        stack.registerOverlay(Kind.MENU, () -> menu.open, menu::close);
        stack.registerOverlay(Kind.PROJECT_SEARCH, () -> search.open, search::close);
        stack.registerPrompt(Kind.PROJECT_DELETE, () -> deletePrompt.open);
        stack.registerPrompt(Kind.VCS_PROMPT, () -> vcsPrompt.open);
        return stack;
    }

    @Test
    void openingAnOverlayClosesTheOthersAndBecomesTheTopOverlay() {
        registered();
        stack.open(Kind.COMMAND_PALETTE, palette::show);
        assertEquals(Optional.of(Kind.COMMAND_PALETTE), stack.topOverlay());

        stack.open(Kind.MENU, menu::show);

        assertTrue(menu.open, "the menu must open");
        assertFalse(palette.open, "opening the menu must close the palette");
        assertEquals(Optional.of(Kind.MENU), stack.topOverlay());
        assertEquals(List.of(Kind.MENU), stack.openOverlays());
    }

    @Test
    void theOpenerRunsOnlyAfterTheOtherOverlaysAreClosed() {
        registered();
        palette.show();
        List<Boolean> paletteOpenWhenOpenerRuns = new ArrayList<>();

        stack.open(Kind.KEYMAP_HELP, () -> paletteOpenWhenOpenerRuns.add(palette.open));

        assertEquals(List.of(false), paletteOpenWhenOpenerRuns);
    }

    @Test
    void theTopOverlayFallsBackToNothingWhenTheOpenOverlayClosesItself() {
        registered();
        stack.open(Kind.PROJECT_SEARCH, search::show);

        search.close();

        assertEquals(Optional.empty(), stack.topOverlay());
        assertFalse(stack.overlayOpen());
        assertEquals(List.of(), stack.openOverlays());
    }

    @Test
    void closeOverlaysClosesEveryOverlayAndNeverDismissesAPrompt() {
        registered();
        stack.open(Kind.COMMAND_PALETTE, palette::show);
        deletePrompt.show();
        vcsPrompt.show();

        stack.closeOverlays();

        assertFalse(palette.open, "the palette must close");
        assertFalse(stack.overlayOpen());
        assertTrue(deletePrompt.open, "a pending delete confirmation must survive closeOverlays");
        assertTrue(vcsPrompt.open, "a pending VCS prompt must survive closeOverlays");
        assertEquals(Optional.of(Kind.PROJECT_DELETE), stack.topPrompt());
    }

    @Test
    void closeOverlaysRunsEveryRegisteredCloseSoTheMenuSyncsEvenWhenItWasClosed() {
        registered();

        stack.closeOverlays();

        assertEquals(1, palette.closes);
        assertEquals(1, help.closes);
        assertEquals(1, menu.closes);
        assertEquals(1, search.closes);
        assertEquals(List.of("palette closed", "help closed", "menu closed", "search closed"), log);
    }

    @Test
    void promptsOutrankEveryOverlayInTheirDeclarationOrder() {
        registered();
        stack.open(Kind.COMMAND_PALETTE, palette::show);
        vcsPrompt.show();
        deletePrompt.show();

        assertEquals(Optional.of(Kind.PROJECT_DELETE), stack.topPrompt());
        assertEquals(Optional.of(Kind.COMMAND_PALETTE), stack.topOverlay(),
                "a prompt does not replace the overlay answer, the shell checks the prompt first");
    }

    @Test
    void theTopPromptIsEmptyUntilAPromptOpens() {
        registered();
        stack.open(Kind.KEYMAP_HELP, help::show);

        assertEquals(Optional.empty(), stack.topPrompt());
    }

    @Test
    void closeClosesOnlyTheNamedOverlay() {
        registered();
        stack.open(Kind.MENU, menu::show);
        int paletteClosesBefore = palette.closes;

        stack.close(Kind.MENU);

        assertFalse(menu.open);
        assertEquals(paletteClosesBefore, palette.closes, "closing one overlay must not run the others");
    }

    @Test
    void openingAnUnregisteredOverlayIsRefused() {
        stack.registerOverlay(Kind.COMMAND_PALETTE, () -> palette.open, palette::close);

        assertThrows(IllegalStateException.class, () -> stack.open(Kind.PROJECT_SEARCH, search::show));
        assertFalse(search.open);
    }

    @Test
    void aPromptCannotBeRegisteredAsAnOverlayAndAnOverlayCannotBeRegisteredAsAPrompt() {
        assertThrows(IllegalArgumentException.class,
                () -> stack.registerOverlay(Kind.PROJECT_DELETE, () -> false, () -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> stack.registerPrompt(Kind.MENU, () -> false));
    }
}
