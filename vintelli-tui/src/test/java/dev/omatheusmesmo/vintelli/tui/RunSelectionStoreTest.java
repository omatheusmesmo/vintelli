package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunSelectionStoreTest {

    @TempDir
    Path directory;

    @Test
    void selectionSurvivesAnotherStoreAndIsIsolatedByProject() throws Exception {
        Path config = directory.resolve("config");
        Path first = Files.createDirectory(directory.resolve("first"));
        Path second = Files.createDirectory(directory.resolve("second"));
        RunSelectionStore store = new RunSelectionStore(config, first);
        store.save("maven:quarkus:dev");
        assertEquals("maven:quarkus:dev", new RunSelectionStore(config, first.resolve(".")).load().orElseThrow());
        assertTrue(new RunSelectionStore(config, second).load().isEmpty());
        try (var contents = Files.list(first)) {
            assertEquals(0, contents.count(), "metadata stays outside the checkout");
        }
    }

    @Test
    void disabledStoreDoesNotWriteIntoTheWorkingDirectory() throws Exception {
        RunSelectionStore store = new RunSelectionStore(null, directory);
        store.save("maven:verify");
        assertTrue(store.load().isEmpty());
        try (var contents = Files.list(directory)) {
            assertEquals(0, contents.count());
        }
    }
}
