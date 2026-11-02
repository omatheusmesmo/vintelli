package dev.omatheusmesmo.vintelli.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogFileLocationTest {

    @Test
    void linuxUsesTheXdgStateHome() {
        Path state = Path.of("/var/state").toAbsolutePath();
        Path file = LogFileLocation.resolve("Linux", "/home/ana", Map.of("XDG_STATE_HOME", state.toString()));
        assertEquals(state.resolve("vintelli").resolve("vintelli.log"), file);
    }

    @Test
    void linuxFallsBackToTheLocalStateDirectory() {
        Path file = LogFileLocation.resolve("Linux", "/home/ana", Map.of());
        assertEquals(Path.of("/home/ana", ".local", "state", "vintelli", "vintelli.log"), file);
    }

    @Test
    void linuxIgnoresARelativeOrBlankXdgStateHome() {
        Path expected = Path.of("/home/ana", ".local", "state", "vintelli", "vintelli.log");
        assertEquals(expected, LogFileLocation.resolve("Linux", "/home/ana", Map.of("XDG_STATE_HOME", "relative/dir")));
        assertEquals(expected, LogFileLocation.resolve("Linux", "/home/ana", Map.of("XDG_STATE_HOME", " ")));
    }

    @Test
    void macUsesTheLibraryLogsDirectory() {
        Path file = LogFileLocation.resolve("Mac OS X", "/Users/ana", Map.of("XDG_STATE_HOME", "/ignored"));
        assertEquals(Path.of("/Users/ana", "Library", "Logs", "vintelli", "vintelli.log"), file);
    }

    @Test
    void windowsUsesLocalAppData() {
        Path file = LogFileLocation.resolve("Windows 11", "C:\\Users\\ana", Map.of("LOCALAPPDATA", "D:\\Local"));
        assertEquals(Path.of("D:\\Local", "vintelli", "logs", "vintelli.log"), file);
    }

    @Test
    void windowsWithoutLocalAppDataFallsBackToTheProfile() {
        Path file = LogFileLocation.resolve("Windows 11", "C:\\Users\\ana", Map.of());
        assertEquals(Path.of("C:\\Users\\ana", "AppData", "Local", "vintelli", "logs", "vintelli.log"), file);
    }

    @Test
    void prepareCreatesTheMissingDirectories(@TempDir Path root) {
        Path file = root.resolve("a").resolve("b").resolve("vintelli.log");
        assertEquals(Optional.of(file), LogFileLocation.prepare(file));
        assertTrue(Files.isDirectory(file.getParent()));
    }

    @Test
    void prepareReturnsEmptyWhenTheDirectoryCannotBeCreated(@TempDir Path root) throws IOException {
        Path blocker = Files.createFile(root.resolve("not-a-directory"));
        assertEquals(Optional.empty(), LogFileLocation.prepare(blocker.resolve("vintelli.log")));
    }
}
