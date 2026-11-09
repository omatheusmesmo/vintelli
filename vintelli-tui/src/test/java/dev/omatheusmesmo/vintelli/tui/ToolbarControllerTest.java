package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import org.junit.jupiter.api.Test;

/**
 * The toolbar click routing: each button dispatches the named action its keyboard
 * route dispatches, the Debug button dispatches nothing, and only the menu
 * buttons take a click while the menu popup is open.
 */
class ToolbarControllerTest {

    private static final MainToolbar.Glyphs GLYPHS = MainToolbar.Glyphs.UNICODE;

    @Test
    void searchDispatchesTheFindInFilesActionThatAltFBinds() {
        RecordingHost host = new RecordingHost(false);
        assertTrue(controller(host, null).click(segment(MainToolbar.Kind.SEARCH)));
        assertEquals(List.of(KeyActions.FIND_IN_FILES), host.dispatched);
    }

    @Test
    void moreDispatchesTheMenuActionThatF10Binds() {
        RecordingHost host = new RecordingHost(false);
        assertTrue(controller(host, null).click(segment(MainToolbar.Kind.MORE)));
        assertEquals(List.of(KeyActions.MENU), host.dispatched);
    }

    @Test
    void settingsDispatchesTheSettingsActionThatThePaletteListsAsOpenSettings() {
        RecordingHost host = new RecordingHost(false);
        assertTrue(controller(host, null).click(segment(MainToolbar.Kind.SETTINGS)));
        assertEquals(List.of(KeyActions.SHELL_SETTINGS), host.dispatched);
    }

    @Test
    void debugIsDisabledAndTakesTheClickWithoutDispatching() {
        RecordingHost host = new RecordingHost(false);
        assertTrue(controller(host, null).click(segment(MainToolbar.Kind.DEBUG)));
        assertEquals(List.of(), host.dispatched);
        assertEquals(0, host.showMenuCalls);
        assertFalse(host.refreshed);
    }

    @Test
    void configurationSelectsTheNextConfigurationOnlyWhenARunWindowExists() {
        RecordingHost host = new RecordingHost(false);
        assertTrue(controller(host, null).click(segment(MainToolbar.Kind.CONFIGURATION)));
        assertEquals(List.of(), host.dispatched, "no run window, no selection");
        assertTrue(controller(host, new RunToolWindow(ToolbarControllerTest::themes))
                .click(segment(MainToolbar.Kind.CONFIGURATION)));
        assertEquals(List.of(KeyActions.RUN_SELECT_NEXT), host.dispatched);
    }

    @Test
    void runAndStopNeverDispatchWithoutARunWindow() {
        RecordingHost host = new RecordingHost(false);
        ToolbarController controller = controller(host, null);
        controller.click(segment(MainToolbar.Kind.RUN));
        controller.click(segment(MainToolbar.Kind.RUN_STOP));
        assertEquals(List.of(), host.dispatched);
        assertEquals(0, host.focusCalls);
    }

    @Test
    void runFocusesTheRunWindowAndDispatchesExecute() {
        RecordingHost host = new RecordingHost(false);
        assertTrue(controller(host, new RunToolWindow(ToolbarControllerTest::themes))
                .click(segment(MainToolbar.Kind.RUN)));
        assertEquals(1, host.focusCalls);
        assertEquals(List.of(KeyActions.RUN_EXECUTE), host.dispatched);
    }

    @Test
    void whileTheMenuIsOpenOnlyTheMenuButtonAndLabelsTakeAClick() {
        RecordingHost host = new RecordingHost(true);
        ToolbarController controller = controller(host, null);
        for (MainToolbar.Kind kind : List.of(MainToolbar.Kind.SEARCH, MainToolbar.Kind.MORE,
                MainToolbar.Kind.SETTINGS, MainToolbar.Kind.DEBUG, MainToolbar.Kind.CONFIGURATION)) {
            assertFalse(controller.click(segment(kind)), kind + " must fall through to the open menu");
        }
        assertEquals(List.of(), host.dispatched);
        assertTrue(controller.click(segment(MainToolbar.Kind.MENU_BUTTON)));
        assertEquals(1, host.closeCalls);
    }

    @Test
    void theModelReportsTheRunSlotStateAndTheMenuHighlight() {
        RecordingHost host = new RecordingHost(false);
        MainToolbar.Model closed = controller(host, null).model();
        assertNull(closed.run(), "no run window hides the configuration and the slot");
        assertFalse(closed.running());
        assertEquals(-1, closed.activeMenu());
        host.menuOpen = true;
        host.menuIndex = 2;
        MainToolbar.Model open = controller(host, null).model();
        assertTrue(open.menuOpen());
        assertEquals(2, open.activeMenu());
    }

    private static ToolbarController controller(RecordingHost host, RunToolWindow run) {
        return new ToolbarController("vintelli", run, () -> "main", host);
    }

    private static MainToolbar.Segment segment(MainToolbar.Kind kind) {
        String text = switch (kind) {
            case SEARCH -> GLYPHS.search();
            case MORE -> GLYPHS.more();
            case SETTINGS -> GLYPHS.settings();
            case DEBUG -> GLYPHS.debug();
            case CONFIGURATION -> GLYPHS.dropdown();
            case RUN -> GLYPHS.play();
            case RUN_STOP -> GLYPHS.stop();
            default -> "";
        };
        return new MainToolbar.Segment(kind, 0, text, -1);
    }

    private static final class RecordingHost implements ToolbarController.Host {
        private final List<String> dispatched = new ArrayList<>();
        private boolean menuOpen;
        private int menuIndex;
        private int showMenuCalls;
        private int closeCalls;
        private int focusCalls;
        private boolean refreshed;

        private RecordingHost(boolean menuOpen) {
            this.menuOpen = menuOpen;
        }

        @Override
        public boolean menuOpen() {
            return menuOpen;
        }

        @Override
        public int menuIndex() {
            return menuIndex;
        }

        @Override
        public void showMenu(int index) {
            showMenuCalls++;
        }

        @Override
        public void closeMenu() {
            closeCalls++;
        }

        @Override
        public void refreshBranch() {
            refreshed = true;
        }

        @Override
        public void focusRunWindow() {
            focusCalls++;
        }

        @Override
        public void dispatch(String action) {
            dispatched.add(action);
        }
    }

    private static ShellThemes themes() {
        return new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
    }
}
