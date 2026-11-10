package dev.omatheusmesmo.vintelli.tui.icons;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaTypeKindsTest {

    @TempDir
    Path root;

    private final List<Runnable> queued = new ArrayList<>();
    private final AtomicInteger reads = new AtomicInteger();

    private JavaTypeKinds kinds() {
        return new JavaTypeKinds(file -> {
            reads.incrementAndGet();
            return Files.readString(file);
        }, queued::add);
    }

    private void runQueued() {
        List<Runnable> tasks = List.copyOf(queued);
        queued.clear();
        tasks.forEach(Runnable::run);
    }

    @Test
    void unknownFileAnswersAtOnceWithoutReadingOnTheCallingThread() throws IOException {
        Path file = write("Shape.java", "public interface Shape {}");
        JavaTypeKinds kinds = kinds();

        assertNull(kinds.kindOf(file));
        assertEquals(0, reads.get());
        assertEquals(1, queued.size());

        runQueued();
        assertEquals(JavaTypeKind.INTERFACE, kinds.kindOf(file));
        assertEquals(1, reads.get());
    }

    @Test
    void aFileIsReadOncePerModificationTime() throws IOException {
        Path file = write("Point.java", "record Point(int x) {}");
        JavaTypeKinds kinds = kinds();
        kinds.kindOf(file);
        runQueued();

        kinds.kindOf(file);
        kinds.revalidate(file);
        runQueued();

        assertEquals(JavaTypeKind.RECORD, kinds.kindOf(file));
        assertEquals(1, reads.get());
    }

    @Test
    void aChangedModificationTimeIsReadAgain() throws IOException {
        Path file = write("Level.java", "public class Level {}");
        JavaTypeKinds kinds = kinds();
        kinds.kindOf(file);
        runQueued();
        long version = kinds.version();

        Files.writeString(file, "public enum Level { A }");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plus(Duration.ofMinutes(1))));
        kinds.revalidate(file);
        assertEquals(JavaTypeKind.CLASS, kinds.kindOf(file));
        runQueued();

        assertEquals(JavaTypeKind.ENUM, kinds.kindOf(file));
        assertEquals(2, reads.get());
        assertTrue(kinds.version() > version);
    }

    @Test
    void versionAdvancesOnlyWhenAnAnswerChanges() throws IOException {
        Path file = write("Mark.java", "public @interface Mark {}");
        JavaTypeKinds kinds = kinds();
        long before = kinds.version();

        kinds.kindOf(file);
        runQueued();
        long afterScan = kinds.version();
        kinds.revalidate(file);
        runQueued();

        assertTrue(afterScan > before);
        assertEquals(afterScan, kinds.version());
    }

    @Test
    void unreadableFileKeepsTheUnknownKind() {
        Path missing = root.resolve("Gone.java");
        JavaTypeKinds kinds = kinds();

        kinds.kindOf(missing);
        runQueued();

        assertNull(kinds.kindOf(missing));
        assertEquals(0, reads.get());
    }

    @Test
    void readerFailureKeepsTheUnknownKind() throws IOException {
        Path file = write("Broken.java", "class Broken {}");
        JavaTypeKinds kinds = new JavaTypeKinds(ignored -> {
            throw new IOException("denied");
        }, queued::add);

        kinds.kindOf(file);
        runQueued();

        assertNull(kinds.kindOf(file));
    }

    @Test
    void onlyJavaSourcesAreScanned() throws IOException {
        Path readme = write("README.md", "class Fake {}");
        Path directory = Files.createDirectories(root.resolve("Folder.java"));
        JavaTypeKinds kinds = kinds();

        assertNull(kinds.kindOf(readme));
        kinds.revalidate(readme);
        assertTrue(queued.isEmpty());

        kinds.kindOf(directory);
        runQueued();
        assertNull(kinds.kindOf(directory));
        assertEquals(0, reads.get());
    }

    @Test
    void theProductionReaderAnswersOnAVirtualThread() throws Exception {
        Path file = write("Color.java", "public enum Color { RED }");
        JavaTypeKinds kinds = JavaTypeKinds.ofFiles();

        assertNull(kinds.kindOf(file));
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JavaTypeKind kind = null;
        while (kind == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
            kind = kinds.kindOf(file);
        }

        assertEquals(JavaTypeKind.ENUM, kind);
    }

    private Path write(String name, String content) throws IOException {
        Path file = root.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
