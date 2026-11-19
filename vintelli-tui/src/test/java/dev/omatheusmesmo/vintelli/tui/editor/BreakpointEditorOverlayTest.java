package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreakpointEditorOverlayTest {

    private static final DebugCapability.SourceBreakpoint PLAIN = new DebugCapability.SourceBreakpoint("/p/Main.java", 3);

    private static KeyEvent key(KeyCode code) {
        return KeyEvent.ofKey(code);
    }

    private static void type(BreakpointEditorOverlay overlay, String value) {
        for (char character : value.toCharArray()) {
            overlay.handleKey(KeyEvent.ofChar(character));
        }
    }

    private static BreakpointEditorOverlay opened(DebugCapability.SourceBreakpoint point) {
        BreakpointEditorOverlay overlay = new BreakpointEditorOverlay();
        overlay.open(point);
        return overlay;
    }

    @Test
    void conditionLogMessageAndHitCountAreEditedInTheirOwnFieldsAndConfirmedTogether() {
        BreakpointEditorOverlay overlay = opened(PLAIN);
        type(overlay, "count > 1");
        overlay.handleKey(key(KeyCode.DOWN));
        type(overlay, "count={count}");
        overlay.handleKey(key(KeyCode.DOWN));
        type(overlay, ">= 3");
        overlay.handleKey(key(KeyCode.DOWN));
        type(overlay, " ");

        Optional<BreakpointEditorOverlay.Edit> edit = overlay.handleKey(key(KeyCode.ENTER));

        assertEquals(Optional.of(new BreakpointEditorOverlay.Edit("count > 1", "count={count}", ">= 3", false)), edit);
        assertFalse(overlay.isOpen());
    }

    @Test
    void openingShowsTheCurrentValuesAndALogpointStartsWithSuspendOff() {
        DebugCapability.SourceBreakpoint logpoint = new DebugCapability.SourceBreakpoint("/p/Main.java", 3,
                DebugCapability.BreakpointState.VERIFIED, "", null, null, "n > 1", "n={n}", "2");
        BreakpointEditorOverlay overlay = opened(logpoint);

        assertEquals(Optional.of(new BreakpointEditorOverlay.Edit("n > 1", "n={n}", "2", false)),
                overlay.handleKey(key(KeyCode.ENTER)));
    }

    @Test
    void tabAndUpWrapAroundTheFourFields() {
        BreakpointEditorOverlay overlay = opened(PLAIN);
        overlay.handleKey(key(KeyCode.UP));
        type(overlay, " ");
        overlay.handleKey(key(KeyCode.TAB));
        type(overlay, "hit");
        overlay.handleKey(key(KeyCode.TAB));
        overlay.handleKey(key(KeyCode.TAB));

        overlay.handleKey(key(KeyCode.ENTER));

        assertTrue(overlay.isOpen(), "the toggle flipped Suspend off, so a log message is still required");
        assertEquals(BreakpointEditorOverlay.NEEDS_LOG_MESSAGE, overlay.status());
    }

    @Test
    void enterKeepsThePanelOpenWithTheReasonWhenTheCombinationIsInvalid() {
        BreakpointEditorOverlay overlay = opened(PLAIN);
        overlay.handleKey(key(KeyCode.DOWN));
        type(overlay, "x={x}");

        assertTrue(overlay.handleKey(key(KeyCode.ENTER)).isEmpty());
        assertTrue(overlay.isOpen());
        assertEquals(BreakpointEditorOverlay.NEEDS_NO_SUSPEND, overlay.status());

        overlay.handleKey(key(KeyCode.DOWN));
        type(overlay, "often");
        overlay.handleKey(key(KeyCode.DOWN));
        type(overlay, " ");
        assertTrue(overlay.handleKey(key(KeyCode.ENTER)).isEmpty());
        assertTrue(overlay.status().contains("hit count"), overlay.status());
    }

    @Test
    void backspaceEditsTheFocusedFieldAndEscapeCancelsWithoutAnEdit() {
        BreakpointEditorOverlay overlay = opened(PLAIN);
        type(overlay, "ab");
        overlay.handleKey(key(KeyCode.BACKSPACE));
        assertEquals(Optional.of(new BreakpointEditorOverlay.Edit("a", "", "", true)),
                overlay.handleKey(key(KeyCode.ENTER)));

        BreakpointEditorOverlay cancelled = opened(PLAIN);
        type(cancelled, "ab");
        assertTrue(cancelled.handleKey(key(KeyCode.ESCAPE)).isEmpty());
        assertFalse(cancelled.isOpen());
    }

    @Test
    void keysAreIgnoredWhileClosed() {
        BreakpointEditorOverlay overlay = new BreakpointEditorOverlay();

        assertTrue(overlay.handleKey(KeyEvent.ofChar('x')).isEmpty());
        assertTrue(overlay.handleKey(key(KeyCode.ENTER)).isEmpty());
        assertFalse(overlay.isOpen());
    }
}
