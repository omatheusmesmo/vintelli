package dev.omatheusmesmo.vintelli.tui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class NativeSystemClipboardTest {

    private static final Duration BOUND = Duration.ofSeconds(8);

    @TempDir
    Path tools;

    @Test
    void readReturnsTheOutputOfThePlatformTool() throws IOException {
        tool("wl-paste", "printf 'hello\\nworld'");

        NativeSystemClipboard clipboard = new NativeSystemClipboard(tools.toString());

        assertEquals(Optional.of("hello\nworld"), clipboard.read());
    }

    @Test
    void aReadToolThatNeverAnswersTimesOutInsteadOfBlockingTheCaller() throws IOException {
        tool("wl-paste", "exec sleep 30");

        NativeSystemClipboard clipboard = new NativeSystemClipboard(tools.toString());

        assertTimeoutPreemptively(BOUND, () -> assertEquals(Optional.empty(), clipboard.read()));
    }

    @Test
    void aWriteToolThatNeverReadsItsInputTimesOutInsteadOfBlockingTheCaller() throws IOException {
        tool("wl-copy", "exec sleep 30");

        NativeSystemClipboard clipboard = new NativeSystemClipboard(tools.toString());
        String large = "x".repeat(4_000_000);

        assertTimeoutPreemptively(BOUND, () -> assertFalse(clipboard.write(large)));
    }

    @Test
    void writeHandsTheTextToTheStandardInputOfTheTool() throws IOException {
        Path received = tools.resolve("received.txt");
        tool("wl-copy", "cat > '" + received + "'");

        NativeSystemClipboard clipboard = new NativeSystemClipboard(tools.toString());

        assertTrue(clipboard.write("copied é\n中"));
        assertEquals("copied é\n中", Files.readString(received));
    }

    private void tool(String name, String body) throws IOException {
        Path script = tools.resolve(name);
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        assertTrue(script.toFile().setExecutable(true));
    }
}
