package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.layout.Rect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalSizeNoticeTest {

    @Test
    void theNoticeReplacesTheLayoutOnlyBelowFortyColumnsOrTenRows() {
        assertTrue(TerminalSizeNotice.tooSmall(new Rect(0, 0, 39, 10)));
        assertTrue(TerminalSizeNotice.tooSmall(new Rect(0, 0, 40, 9)));
        assertFalse(TerminalSizeNotice.tooSmall(new Rect(0, 0, 40, 10)));
        assertFalse(TerminalSizeNotice.tooSmall(new Rect(0, 0, 80, 24)));
    }
}
