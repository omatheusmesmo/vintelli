package dev.omatheusmesmo.vintelli.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The one text frame of the terminal surface is {@code resize COLUMNSxROWS};
 * anything else a client sends must parse to nothing, so a forged frame can
 * never reach the session.
 */
class ResizeMessageTest {

    @Test
    void wellFormedFrameIsParsed() {
        WebTerminalEndpoint.ResizeMessage size = WebTerminalEndpoint.ResizeMessage.parse(" resize 120x40 ")
                .orElseThrow();
        assertEquals(120, size.columns());
        assertEquals(40, size.rows());
    }

    @Test
    void forgedFramesAreRejected() {
        for (String forged : new String[] {null, "", "resize", "resize ", "resize 80", "resize axb", "resize 80x",
                "resize -5x10", "resize 0x24", "resize 80x24x1", "resize 99999999999x24", "reboot 80x24",
                "RESIZE 80x24"}) {
            assertTrue(WebTerminalEndpoint.ResizeMessage.parse(forged).isEmpty(), () -> "accepted: " + forged);
        }
    }
}
