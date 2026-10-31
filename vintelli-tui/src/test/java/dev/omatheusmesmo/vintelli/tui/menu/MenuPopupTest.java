package dev.omatheusmesmo.vintelli.tui.menu;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuPopupTest {
    @Test
    void navigatesBetweenMenusAndReturnsNamedActionThenCloses() {
        MenuPopup popup = popup(List.of(new MenuEntry("File", "SaveAll", "Save All"),
                new MenuEntry("View", "ActivateRunToolWindow", "Run")));
        popup.open(0);
        popup.handleKey(KeyEvent.ofKey(KeyCode.RIGHT));
        assertEquals(1, popup.menuIndex());
        assertEquals(Optional.of("ActivateRunToolWindow"), popup.handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
        assertFalse(popup.isOpen());
        popup.open(0);
        popup.handleKey(KeyEvent.ofKey(KeyCode.LEFT));
        assertEquals(1, popup.menuIndex());
        popup.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertFalse(popup.isOpen());
    }

    @Test
    void unavailableCapabilityCannotDispatchAndAvailabilityIsRefreshed() {
        AtomicBoolean available = new AtomicBoolean(false);
        String reason = "No build tool is configured for this project; install one and configure the project SDK";
        MenuPopup popup = new MenuPopup(List.of("Run"), () -> List.of(
                new MenuEntry("Run", "Run", "Run project", available.get() ? "" : reason)), id -> "F5");
        popup.open(0);
        assertEquals(reason, popup.selectedReason());
        assertEquals(Optional.empty(), popup.handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
        assertTrue(popup.isOpen());
        available.set(true);
        assertEquals(Optional.of("Run"), popup.handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
    }

    @Test
    void unavailableReasonIsWrappedAcrossRenderedRowsWithoutLosingText() {
        String reason = "No build tool is configured for this project; install one and configure the project SDK";
        MenuPopup popup = new MenuPopup(List.of("Run"), () -> List.of(
                new MenuEntry("Run", "Run", "Run project", reason)), id -> "F5");
        popup.open(0);
        Rect area = new Rect(0, 0, 80, 20);
        Buffer buffer = Buffer.empty(area);
        popup.render(Frame.forTesting(buffer), area);

        String rendered = contentText(buffer);
        assertTrue(rendered.contains(reason), rendered);
    }

    @Test
    void clampsSelectionAndHandlesEmptyMenuWithoutDispatch() {
        MenuPopup popup = popup(List.of(new MenuEntry("File", "SaveAll", "Save All")));
        popup.open(0);
        for (int i = 0; i < 20; i++) {
            popup.handleKey(KeyEvent.ofKey(KeyCode.DOWN));
        }
        assertEquals(Optional.of("SaveAll"), popup.handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
        popup.open(1);
        assertEquals(Optional.empty(), popup.handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
        popup.handleKey(KeyEvent.ofKey(KeyCode.F10));
        assertFalse(popup.isOpen());
    }

    @Test
    void emptyMenuRendersWithoutFailing() {
        MenuPopup popup = new MenuPopup(List.of("Run"), List::of, id -> "");
        popup.open(0);
        Rect area = new Rect(0, 0, 40, 10);
        Buffer buffer = Buffer.empty(area);

        popup.render(Frame.forTesting(buffer), area);

        assertTrue(bufferText(buffer).contains("No actions available"));
    }

    @Test
    void pageDownScrollsToTheEndOfALongUnavailableReason() {
        String reason = "The project does not have a configured build tool and the editor cannot resolve dependencies "
                + "until all required SDK settings are still missing";
        MenuPopup popup = new MenuPopup(List.of("Run"), () -> List.of(
                new MenuEntry("Run", "Run", "Run project", reason)), id -> "F5");
        popup.open(0);
        Rect area = new Rect(0, 0, 44, 7);
        Buffer buffer = Buffer.empty(area);
        popup.render(Frame.forTesting(buffer), area);
        assertFalse(contentText(buffer).contains("still missing"));

        popup.handleKey(KeyEvent.ofKey(KeyCode.PAGE_DOWN));
        popup.handleKey(KeyEvent.ofKey(KeyCode.PAGE_DOWN));
        Buffer scrolled = Buffer.empty(area);
        popup.render(Frame.forTesting(scrolled), area);

        assertTrue(contentText(scrolled).contains("still missing"), contentText(scrolled));
    }

    @Test
    void clickBeforeFirstRenderDoesNotDispatch() {
        MenuPopup popup = popup(List.of(new MenuEntry("File", "SaveAll", "Save All")));
        popup.open(0);

        assertEquals(Optional.empty(), popup.click(0, 0));
        assertTrue(popup.isOpen());
    }

    @Test
    void reopeningOrSwitchingMenuInvalidatesPreviousRenderHitbox() {
        MenuPopup popup = popup(List.of(new MenuEntry("File", "SaveAll", "Save All"),
                new MenuEntry("View", "Run", "Run")));
        popup.open(0);
        popup.render(Frame.forTesting(Buffer.empty(new Rect(0, 0, 80, 24))), new Rect(0, 0, 80, 24));
        popup.close();
        popup.open(0);
        assertEquals(Optional.empty(), popup.click(2, 2));
        assertTrue(popup.isOpen());

        popup.render(Frame.forTesting(Buffer.empty(new Rect(0, 0, 80, 24))), new Rect(0, 0, 80, 24));
        popup.handleKey(KeyEvent.ofKey(KeyCode.RIGHT));
        assertEquals(Optional.empty(), popup.click(2, 2));
        assertTrue(popup.isOpen());
    }

    private static MenuPopup popup(List<MenuEntry> entries) {
        return new MenuPopup(List.of("File", "View"), () -> entries, id -> "Ctrl+s");
    }

    private static String bufferText(Buffer buffer) {
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < buffer.height(); y++) {
            for (int x = 0; x < buffer.width(); x++) {
                text.append(buffer.get(x, y).symbol());
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static String contentText(Buffer buffer) {
        return bufferText(buffer).replaceAll("[┌┐└┘─│]", " ").replaceAll("\\s+", " ");
    }
}
