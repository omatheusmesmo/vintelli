package dev.omatheusmesmo.vintelli.vcs;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TemporaryGitRepositoryResourceTest {

    private static final String WRITER_SCRIPT = "i=0; while :; do : > \"$1/.git/lingering-$i\"; "
            + "i=$((i + 1)); sleep 0.01; done";
    private static final Duration ENTRY_TIMEOUT = Duration.ofSeconds(10);
    private static final String WRITABLE = "rwxr-xr-x";
    private static final String READ_ONLY = "r-xr-xr-x";

    @Test
    void stopDeletesAPlainRepositoryWithoutFailing() {
        TemporaryGitRepositoryResource resource = new TemporaryGitRepositoryResource();
        resource.start();
        Path root = TemporaryGitRepositoryResource.repositoryRoot();

        assertDoesNotThrow(resource::stop);

        assertFalse(Files.exists(root), "the repository must be deleted");
        assertNull(TemporaryGitRepositoryResource.repositoryRoot(), "the resource must forget the repository");
    }

    @Test
    void stopStopsTheProcessThatIsStillWritingIntoTheRepository() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "the writer is a POSIX shell loop");
        TemporaryGitRepositoryResource resource = new TemporaryGitRepositoryResource();
        resource.start();
        Path root = TemporaryGitRepositoryResource.repositoryRoot();
        Process writer = new ProcessBuilder("sh", "-c", WRITER_SCRIPT, "sh", root.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            awaitEntry(root.resolve(".git").resolve("lingering-0"));

            resource.stop();

            assertFalse(writer.isAlive(), "the process still writing into the repository must be stopped first");
            assertFalse(Files.exists(root), "the repository must be deleted once its writer is gone");
        } finally {
            writer.destroyForcibly();
            deleteRecursively(root);
        }
    }

    @Test
    void stopFailsLoudlyWithTheRealCauseWhenTheRepositoryCannotBeDeleted() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "the permission check is POSIX only");
        TemporaryGitRepositoryResource resource = new TemporaryGitRepositoryResource();
        resource.start();
        Path root = TemporaryGitRepositoryResource.repositoryRoot();
        Path heads = root.resolve(".git").resolve("refs").resolve("heads");
        Files.setPosixFilePermissions(heads, PosixFilePermissions.fromString(READ_ONLY));
        try {
            assumeFalse(Files.isWritable(heads), "the directory must refuse deletions for this check to mean anything");

            IllegalStateException failure = assertThrows(IllegalStateException.class, resource::stop);

            assertInstanceOf(AccessDeniedException.class, failure.getCause(), "the real cause is kept");
            assertTrue(failure.getMessage().contains(root.toString()), "the message names the repository");
            assertNull(TemporaryGitRepositoryResource.repositoryRoot(), "the resource must forget the repository");
        } finally {
            Files.setPosixFilePermissions(heads, PosixFilePermissions.fromString(WRITABLE));
            deleteRecursively(root);
        }
    }

    private static void awaitEntry(Path entry) throws InterruptedException {
        long deadline = System.nanoTime() + ENTRY_TIMEOUT.toNanos();
        while (!Files.exists(entry)) {
            assertTrue(System.nanoTime() < deadline, "the writer never created " + entry);
            Thread.sleep(5);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }
}
