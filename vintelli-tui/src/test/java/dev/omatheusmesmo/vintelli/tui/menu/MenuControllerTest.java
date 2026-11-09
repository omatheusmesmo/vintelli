package dev.omatheusmesmo.vintelli.tui.menu;

import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuControllerTest {

    private static final List<String> MENUS = List.of("File", "View");
    private static final OverlayRegion AREA = new OverlayRegion(0, 0, 40, 10);

    private final List<MenuEntry> entries = List.of(
            new MenuEntry("File", "SaveAll", "Save All"),
            new MenuEntry("File", "CloseAll", "Close All", "Nothing is open"),
            new MenuEntry("View", "Run", "Run project"));
    private final MenuController menu = new MenuController(MENUS, () -> entries, id -> "Ctrl+s");

    @Test
    void leftAndRightWrapAroundTheMenuBar() {
        menu.open(0);

        menu.press(OverlayKey.LEFT);
        assertEquals(1, menu.menuIndex());

        menu.press(OverlayKey.RIGHT);
        assertEquals(0, menu.menuIndex());

        menu.press(OverlayKey.RIGHT);
        assertEquals(1, menu.menuIndex());
    }

    @Test
    void enterConfirmsAnEnabledEntryAndClosesWhileADisabledOneKeepsTheMenuOpen() {
        menu.open(0);
        menu.press(OverlayKey.DOWN);
        assertEquals("Nothing is open", menu.selectedReason());

        assertEquals(Optional.empty(), menu.press(OverlayKey.ENTER));
        assertTrue(menu.isOpen(), "an unavailable entry must not dispatch or close the menu");

        menu.press(OverlayKey.UP);
        assertEquals(Optional.of("SaveAll"), menu.press(OverlayKey.ENTER));
        assertFalse(menu.isOpen());
    }

    @Test
    void escapeAndF10CloseTheMenu() {
        menu.open(0);
        menu.press(OverlayKey.ESCAPE);
        assertFalse(menu.isOpen());

        menu.open(0);
        menu.press(OverlayKey.F10);
        assertFalse(menu.isOpen());
    }

    @Test
    void placedRowsHighlightTheSelectionAndDimTheUnavailableEntry() {
        menu.open(0);

        List<MenuController.Row> rows = menu.place(AREA, 0).orElseThrow().rows();

        assertTrue(rows.get(0).highlighted());
        assertTrue(rows.stream().anyMatch(row -> row.entryIndex() == 1 && row.dimmed()));
        assertTrue(rows.stream().noneMatch(row -> row.entryIndex() == 0 && row.dimmed()));
    }

    @Test
    void aClickOnARowConfirmsItsEntryAfterThePlacement() {
        menu.open(0);
        MenuController.Placement placement = menu.place(AREA, 0).orElseThrow();

        assertEquals(Optional.of("SaveAll"), menu.click(placement.inner().x(), placement.inner().y()));
        assertFalse(menu.isOpen());
    }

    @Test
    void anAreaTooSmallToDrawClearsTheHitboxSoClicksDoNothing() {
        menu.open(0);
        menu.place(AREA, 0);

        assertTrue(menu.place(new OverlayRegion(0, 0, 1, 2), 0).isEmpty());

        assertEquals(Optional.empty(), menu.click(1, 2));
        assertTrue(menu.isOpen());
    }

    @Test
    void pageDownScrollsALongUnavailableReasonToItsLaterLines() {
        String reason = "The project does not have a configured build tool and the editor cannot resolve dependencies "
                + "until all required SDK settings are still missing";
        MenuController long1 = new MenuController(List.of("Run"), () -> List.of(
                new MenuEntry("Run", "Run", "Run project", reason)), id -> "F5");
        long1.open(0);
        OverlayRegion area = new OverlayRegion(0, 0, 44, 4);
        String firstRow = long1.place(area, 0).orElseThrow().rows().get(0).text();

        long1.press(OverlayKey.PAGE_DOWN);
        String scrolledRow = long1.place(area, 0).orElseThrow().rows().get(0).text();

        assertNotEquals(firstRow, scrolledRow);
    }

    @Test
    void theLabelOffsetAddsTheWidthOfEveryEarlierLabel() {
        assertEquals(4, MenuController.labelOffset(MENUS, 0, 4));
        assertEquals(10, MenuController.labelOffset(MENUS, 1, 4));
    }
}
