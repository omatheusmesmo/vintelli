package dev.omatheusmesmo.vintelli.tui.terminal;

import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalBackgroundTest {

    private static final String DARK_FRAME_SET = "\u001b]11;#2b2d30\u0007";
    private static final String DARCULA_FRAME_SET = "\u001b]11;#3c3f41\u0007";
    private static final String RESTORE = "\u001b]111\u0007";
    private static final Map<String, String> FOOT = Map.of("TERM", "foot");

    private final StringBuilder written = new StringBuilder();

    private ShellThemes darkThemes() {
        return new ShellThemes("dark", (Path) null);
    }

    @Test
    void setsTheFrameToneAndRestoresTheDefaultOnClose() {
        ShellThemes themes = darkThemes();
        try (TerminalBackground background = TerminalBackground.open(written::append, themes)) {
            assertEquals(DARK_FRAME_SET, written.toString());
        }
        assertEquals(DARK_FRAME_SET + RESTORE, written.toString());
    }

    @Test
    void restoresOnceEvenWhenClosedTwice() {
        TerminalBackground background = TerminalBackground.open(written::append, darkThemes());
        background.close();
        background.close();
        assertEquals(DARK_FRAME_SET + RESTORE, written.toString());
    }

    @Test
    void aThemeSwitchMovesTheDefaultToTheNewFrameTone() {
        ShellThemes themes = darkThemes();
        try (TerminalBackground background = TerminalBackground.open(written::append, themes)) {
            themes.switchTheme("darcula");
            assertEquals(DARK_FRAME_SET + DARCULA_FRAME_SET, written.toString());
        }
        assertEquals(DARK_FRAME_SET + DARCULA_FRAME_SET + RESTORE, written.toString());
    }

    @Test
    void restoreRunsWhenTheTuiFailsInsideTheBlock() {
        ShellThemes themes = darkThemes();
        assertThrows(IllegalStateException.class, () -> {
            try (TerminalBackground background = TerminalBackground.open(written::append, themes)) {
                throw new IllegalStateException("render failed");
            }
        });
        assertEquals(DARK_FRAME_SET + RESTORE, written.toString());
    }

    @Test
    void aSwitchAfterCloseWritesNothing() {
        ShellThemes themes = darkThemes();
        TerminalBackground background = TerminalBackground.open(written::append, themes);
        background.close();
        themes.switchTheme("darcula");
        assertEquals(DARK_FRAME_SET + RESTORE, written.toString());
    }

    @Test
    void aTerminalThatRejectsTheSequenceDoesNotStopTheTui() {
        TerminalBackground.Output rejecting = sequence -> {
            throw new IOException("terminal closed");
        };
        ShellThemes themes = darkThemes();
        assertDoesNotThrow(() -> {
            try (TerminalBackground background = TerminalBackground.open(rejecting, themes)) {
                themes.switchTheme("darcula");
            }
        });
    }

    @Test
    void nothingIsWrittenWhenTheSettingIsOff() {
        Optional<TerminalBackground> background = TerminalBackground.attach(written::append, darkThemes(), false, FOOT, true);
        assertTrue(background.isEmpty());
        assertEquals("", written.toString());
    }

    @Test
    void nothingIsWrittenWithoutAnAttachedTerminal() {
        Optional<TerminalBackground> background = TerminalBackground.attach(written::append, darkThemes(), true, FOOT, false);
        assertTrue(background.isEmpty());
        assertEquals("", written.toString());
    }

    @Test
    void nothingIsWrittenForAnUntrustedTerminal() {
        Optional<TerminalBackground> background = TerminalBackground.attach(written::append, darkThemes(), true,
                Map.of("TERM", "vt100"), true);
        assertTrue(background.isEmpty());
        assertEquals("", written.toString());
    }

    @Test
    void nothingIsWrittenOverSsh() {
        Optional<TerminalBackground> background = TerminalBackground.attach(written::append, darkThemes(), true,
                Map.of("TERM", "foot", "SSH_CONNECTION", "10.0.0.1 40000 10.0.0.2 22"), true);
        assertFalse(background.isPresent());
        assertEquals("", written.toString());
    }

    @Test
    void aTrustedTerminalIsRecognisedByItsName() {
        Optional<TerminalBackground> background = TerminalBackground.attach(written::append, darkThemes(), true, FOOT, true);
        assertTrue(background.isPresent());
        background.get().close();
        assertEquals(DARK_FRAME_SET + RESTORE, written.toString());
    }

    @Test
    void aTrustedTerminalIsRecognisedByItsProgramVariable() {
        Optional<TerminalBackground> background = TerminalBackground.attach(written::append, darkThemes(), true,
                Map.of("TERM", "xterm-256color", "TERM_PROGRAM", "WezTerm"), true);
        assertTrue(background.isPresent());
        background.get().close();
    }
}
