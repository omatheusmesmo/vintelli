package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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

    @Test
    void aRenameThatFailsOnceIsRetriedAndTheSelectionIsSaved() throws Exception {
        Path config = directory.resolve("config");
        Path project = Files.createDirectory(directory.resolve("project"));
        List<Boolean> attempts = new ArrayList<>();
        RunSelectionStore store = new RunSelectionStore(config, project, (source, target, atomic) -> {
            attempts.add(atomic);
            if (attempts.size() == 1) {
                throw new AccessDeniedException(target.toString());
            }
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        });
        store.save("maven:compile");
        assertEquals("maven:compile", new RunSelectionStore(config, project).load().orElseThrow());
        assertEquals(2, attempts.size(), "the failed rename is attempted once more and then succeeds");
        assertEquals(List.of(), temporaryFilesUnder(config), "the temporary file is removed after the retry");
    }

    @Test
    void aRenameThatKeepsFailingIsReportedAfterTheBoundedAttemptsWithoutLeavingATemporaryFile() throws Exception {
        Path config = directory.resolve("config");
        Path project = Files.createDirectory(directory.resolve("project"));
        List<Boolean> attempts = new ArrayList<>();
        RunSelectionStore store = new RunSelectionStore(config, project, (source, target, atomic) -> {
            attempts.add(atomic);
            throw new AccessDeniedException(target.toString());
        });
        assertThrows(AccessDeniedException.class, () -> store.save("maven:compile"));
        assertEquals(4, attempts.size(), "the rename is attempted a bounded number of times");
        assertTrue(store.load().isEmpty());
        assertEquals(List.of(), temporaryFilesUnder(config), "no temporary file is left behind");
    }

    private static List<Path> temporaryFilesUnder(Path root) throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".tmp")).toList();
        }
    }
}
