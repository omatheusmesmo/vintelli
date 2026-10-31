package dev.omatheusmesmo.vintelli.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.api.search.ProjectSearch.FileMatch;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch.SearchTask;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch.TextMatch;

class LocalProjectSearchTest {
    @TempDir
    Path root;

    @Test
    void ranksExactNamesBeforePrefixesAndSubsequencesOnVirtualThreads() throws Exception {
        write("src/Main.java", "class Main {}");
        write("src/Main.java.backup", "");
        write("src/MyApplicationIndex.java", "");
        AtomicReference<List<FileMatch>> found = new AtomicReference<>(List.of());
        AtomicBoolean virtual = new AtomicBoolean();
        await(search().files("Main.java", matches -> {
            found.set(matches);
            virtual.set(Thread.currentThread().isVirtual());
        }));
        assertEquals(Path.of("src/Main.java"), found.get().getFirst().file());
        assertTrue(found.get().getFirst().score() > found.get().getLast().score());
        assertTrue(virtual.get());
        assertTrue(LocalProjectSearch.fuzzyScore("src/MyApplicationIndex.java", "mai") >= 0);
        assertEquals(-1, LocalProjectSearch.fuzzyScore("src/Main.java", "zzz"));
    }

    @Test
    void listsEmptyDirectoriesWhilePruningIgnoredDirectories() throws Exception {
        write(".gitignore", "ignored/\n");
        Files.createDirectories(root.resolve("empty"));
        Files.createDirectories(root.resolve("ignored"));
        Files.createDirectories(root.resolve("parent/child"));
        assertEquals(List.of(Path.of("empty"), Path.of("parent"), Path.of("parent/child")),
                search().listDirectories());
    }

    @Test
    void honorsNestedIgnoreNegationAndPrunesIgnoredDirectoriesWithoutAGitRepository() throws Exception {
        write(".gitignore", "target/\n*.log\n!keep.log\n");
        write("src/.gitignore", "*.tmp\n!keep.tmp\n");
        write("target/hidden.txt", "secret");
        write(".git/config", "secret");
        write("hidden.log", "secret");
        write("keep.log", "visible");
        write("src/hidden.tmp", "secret");
        write("src/keep.tmp", "visible");
        write("src/main.java", "visible");
        assertEquals(List.of(Path.of(".gitignore"), Path.of("keep.log"), Path.of("src/.gitignore"),
                Path.of("src/keep.tmp"), Path.of("src/main.java")), search().listFiles());
    }

    @Test
    void streamsGroupedLiteralAndRegexMatchesWithZeroBasedPositions() throws Exception {
        write("a.txt", "one needle\nneedle. needle!\n");
        write("b.txt", "needle7\n");
        LocalProjectSearch search = search();
        AtomicReference<List<TextMatch>> matches = new AtomicReference<>(List.of());
        AtomicInteger batches = new AtomicInteger();
        await(search.text("needle.", false, batch -> {
            matches.set(batch);
            batches.incrementAndGet();
        }));
        assertEquals(List.of(new TextMatch(Path.of("a.txt"), 1, 0, "needle. needle!")), matches.get());
        await(search.text("needle[!7]", true, matches::set));
        assertEquals(List.of(new TextMatch(Path.of("a.txt"), 1, 8, "needle. needle!"),
                new TextMatch(Path.of("b.txt"), 0, 0, "needle7")), matches.get());
        assertTrue(batches.get() >= 2);
    }

    @Test
    void invalidRegexCompletesWithAnErrorAndBinaryFilesAreSkipped() throws Exception {
        write("binary.dat", "needle\0secret");
        Files.write(root.resolve("invalid.dat"), new byte[] { (byte) 0xFF });
        AtomicReference<List<TextMatch>> matches = new AtomicReference<>(List.of());
        LocalProjectSearch search = search();
        await(search.text("needle", false, matches::set));
        assertTrue(matches.get().isEmpty());
        assertThrows(CompletionException.class,
                () -> search.text("[", true, matches::set).completion().toCompletableFuture().join());
    }

    @Test
    void cancellationStopsDeliveryBeforeReturningAndANewQueryStillWorks() throws Exception {
        for (int index = 0; index < 100; index++) {
            write("file-" + index + ".txt", "needle");
        }
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
        LocalProjectSearch search = search();
        SearchTask task = search.files("file", matches -> {
            delivered.incrementAndGet();
            if (!matches.isEmpty()) {
                callbackEntered.countDown();
                try {
                    assertTrue(releaseCallback.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
        Thread cancellation = Thread.ofVirtual().start(task::cancel);
        releaseCallback.countDown();
        cancellation.join(5000);
        assertFalse(cancellation.isAlive());
        int countAfterCancel = delivered.get();
        await(task);
        assertTrue(task.cancelled());
        assertEquals(countAfterCancel, delivered.get());
        AtomicReference<List<FileMatch>> next = new AtomicReference<>(List.of());
        await(search.files("file-1.txt", next::set));
        assertEquals(Path.of("file-1.txt"), next.get().getFirst().file());
    }

    @Test
    void cancellationInterruptsCatastrophicRegexBacktracking() throws Exception {
        write("slow.txt", "a".repeat(300_000) + "!");
        LocalProjectSearch search = search();
        SearchTask task = search.text("(a+)+$", true, ignored -> { });
        Thread.sleep(25);
        task.cancel();
        task.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(task.cancelled());
    }

    @Test
    void neverSearchesOutsideFilesOrSymlinkedDirectories() throws Exception {
        Path outside = Files.createTempDirectory("vintelli-search-outside-");
        try {
            Files.writeString(outside.resolve("secret.txt"), "needle");
            Files.createSymbolicLink(root.resolve("escape"), outside);
            Files.createSymbolicLink(root.resolve("secret.txt"), outside.resolve("secret.txt"));
            assertTrue(search().listFiles().isEmpty());
        } finally {
            Files.deleteIfExists(outside.resolve("secret.txt"));
            Files.deleteIfExists(outside);
        }
    }

    private LocalProjectSearch search() {
        return new LocalProjectSearch(new LocalProjectRoot(root));
    }

    private void write(String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static void await(SearchTask task) throws Exception {
        task.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
