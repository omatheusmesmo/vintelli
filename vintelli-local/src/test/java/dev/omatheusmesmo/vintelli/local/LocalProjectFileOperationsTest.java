package dev.omatheusmesmo.vintelli.local;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalProjectFileOperationsTest {
    @TempDir
    Path project;

    @TempDir
    Path outside;

    @Test
    void createsRenamesAndDeletesInsideTheProject() throws Exception {
        var operations = new LocalProjectFileOperations(new LocalProjectRoot(project));
        assertFalse(operations.createDirectory(Path.of(""), "src").isFailure());
        assertFalse(operations.createFile(Path.of("src"), "Example.java").isFailure());
        assertFalse(operations.rename(Path.of("src/Example.java"), "Renamed.java").isFailure());
        assertTrue(Files.exists(project.resolve("src/Renamed.java")));
        assertFalse(operations.delete(Path.of("src")).isFailure());
        assertFalse(Files.exists(project.resolve("src")));
    }

    @Test
    void rejectsEscapesAndDoesNotReplaceExistingFiles() throws Exception {
        var operations = new LocalProjectFileOperations(new LocalProjectRoot(project));
        Files.writeString(project.resolve("source"), "source");
        Files.writeString(project.resolve("target"), "target");
        assertTrue(operations.createFile(Path.of(""), "../outside").isFailure());
        assertTrue(operations.createDirectory(Path.of(".."), "outside").isFailure());
        assertTrue(operations.rename(Path.of("source"), "../outside").isFailure());
        assertTrue(operations.rename(Path.of("source"), "target").isFailure());
        assertTrue(Files.readString(project.resolve("target")).equals("target"));
        assertTrue(operations.delete(Path.of("..")).isFailure());
    }

    @Test
    void neverFollowsASymlinkOutsideTheProject() throws Exception {
        Path link = project.resolve("external");
        Files.writeString(outside.resolve("keep"), "safe");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic links are unavailable: " + unavailable);
        }
        var operations = new LocalProjectFileOperations(new LocalProjectRoot(project));
        assertTrue(operations.createFile(Path.of("external"), "new").isFailure());
        assertFalse(operations.delete(Path.of("external")).isFailure());
        assertTrue(Files.exists(outside.resolve("keep")));
    }
}
