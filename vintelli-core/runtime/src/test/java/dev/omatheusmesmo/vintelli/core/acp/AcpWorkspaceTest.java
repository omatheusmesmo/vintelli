package dev.omatheusmesmo.vintelli.core.acp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AcpWorkspaceTest {

    @TempDir
    Path tempDir;

    @Test
    void readsAFileInsideTheRootByRelativeOrAbsolutePath() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Files.writeString(root.resolve("notes.txt"), "alpha\nbeta\ngamma\ndelta");
        AcpWorkspace workspace = new AcpWorkspace(root);

        assertEquals("alpha\nbeta\ngamma\ndelta", workspace.read("notes.txt", null, null));
        assertEquals("alpha\nbeta\ngamma\ndelta", workspace.read(root.resolve("notes.txt").toString(), null, null));
    }

    @Test
    void honoursTheOneBasedLineWindow() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Files.writeString(root.resolve("notes.txt"), "alpha\nbeta\ngamma\ndelta");
        AcpWorkspace workspace = new AcpWorkspace(root);

        assertEquals("beta\ngamma", workspace.read("notes.txt", 2, 2));
        assertEquals("delta", workspace.read("notes.txt", 4, 10));
    }

    @Test
    void refusesParentTraversalAndAbsolutePathsOutsideTheRoot() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Files.writeString(tempDir.resolve("secret.txt"), "outside");
        AcpWorkspace workspace = new AcpWorkspace(root);

        assertThrows(AcpAccessDeniedException.class, () -> workspace.read("../secret.txt", null, null));
        assertThrows(AcpAccessDeniedException.class,
                () -> workspace.read(tempDir.resolve("secret.txt").toString(), null, null));
        assertThrows(AcpAccessDeniedException.class, () -> workspace.write("../planted.txt", "x"));
        assertThrows(AcpAccessDeniedException.class,
                () -> workspace.write(tempDir.resolve("planted.txt").toString(), "x"));
    }

    @Test
    void refusesAnExistingSymbolicLinkThatEscapesTheRoot() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path outside = Files.writeString(tempDir.resolve("secret.txt"), "outside");
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), outside);
        } catch (UnsupportedOperationException | IOException unavailable) {
            return;
        }
        AcpWorkspace workspace = new AcpWorkspace(root);

        assertThrows(AcpAccessDeniedException.class, () -> workspace.read("link.txt", null, null));
    }

    @Test
    void writesCreateMissingParentDirectoriesInsideTheRoot() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        AcpWorkspace workspace = new AcpWorkspace(root);

        workspace.write("nested/out.txt", "written");

        assertEquals("written", Files.readString(root.resolve("nested/out.txt")));
    }
}
