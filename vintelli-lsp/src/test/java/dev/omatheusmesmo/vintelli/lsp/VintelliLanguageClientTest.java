package dev.omatheusmesmo.vintelli.lsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The publication counter is what keeps a diagnostics wait from missing a push
 * that races the notification which provokes it: the count is read before the
 * notification is sent and the wait is satisfied only by a later publication
 * of the same file.
 */
class VintelliLanguageClientTest {

    private static final String FIRST = "file:///workspace/First.java";
    private static final String SECOND = "file:///workspace/Second.java";
    private static final Duration SHORT = Duration.ofMillis(200);

    @TempDir
    Path workspace;

    @Test
    void publicationsAndWaitsRecognizeBothSpellingsOfASymlinkedDocument() throws Exception {
        Path actual = Files.createDirectories(workspace.resolve("real sources Ω"));
        Path linked = symlink(workspace.resolve("linked sources Ω"), actual);
        Path document = Files.writeString(actual.resolve("Example.java"), "class Example {}");
        String canonicalUri = document.toRealPath().toUri().toString();
        String aliasUri = linked.resolve("Example.java").toUri().toString();
        VintelliLanguageClient client = new VintelliLanguageClient();
        int before = client.publicationCount(aliasUri);

        client.publishDiagnostics(publication(canonicalUri, "canonical publication"));

        assertTrue(client.awaitPublicationAfter(aliasUri, before, SHORT));
        assertEquals(1, client.publicationCount(aliasUri));
        assertEquals("canonical publication", client.knownDiagnostics(aliasUri).getFirst().getMessage().getLeft());

        client.publishDiagnostics(publication(aliasUri, "alias publication"));

        assertEquals(2, client.publicationCount(canonicalUri));
        assertEquals("alias publication", client.knownDiagnostics(canonicalUri).getFirst().getMessage().getLeft());
    }

    @Test
    void aSymlinkToAnIndividualFileSharesItsDiagnostics() throws Exception {
        Path document = Files.writeString(workspace.resolve("Actual.java"), "class Actual {}");
        Path linked = symlink(workspace.resolve("Alias.java"), document);
        VintelliLanguageClient client = new VintelliLanguageClient();

        client.publishDiagnostics(publication(document.toRealPath().toUri().toString(), "file link"));

        assertEquals(1, client.knownDiagnostics(linked.toUri().toString()).size());
        assertEquals("file link", client.knownDiagnostics(linked.toUri().toString()).getFirst().getMessage().getLeft());
    }

    @Test
    void anUncreatedDocumentResolvesThroughItsExistingParent() throws Exception {
        Path actual = Files.createDirectories(workspace.resolve("actual"));
        Path linked = symlink(workspace.resolve("linked"), actual);
        String canonicalUri = actual.toRealPath().resolve("new/package/Future.java").toUri().toString();
        String aliasUri = linked.resolve("new/package/Future.java").toUri().toString();
        VintelliLanguageClient client = new VintelliLanguageClient();

        client.publishDiagnostics(publication(canonicalUri, "not created yet"));

        assertEquals(1, client.publicationCount(aliasUri));
        assertEquals("not created yet", client.knownDiagnostics(aliasUri).getFirst().getMessage().getLeft());
    }

    @Test
    void removingTheDocumentKeepsItsPublishedDiagnosticsReachable() throws Exception {
        Path actual = Files.createDirectories(workspace.resolve("actual"));
        Path linked = symlink(workspace.resolve("linked"), actual);
        Path document = Files.writeString(actual.resolve("Removed.java"), "class Removed {}");
        String canonicalUri = document.toRealPath().toUri().toString();
        String aliasUri = linked.resolve("Removed.java").toUri().toString();
        VintelliLanguageClient client = new VintelliLanguageClient();
        client.publishDiagnostics(publication(canonicalUri, "published before deletion"));

        Files.delete(document);

        assertTrue(client.awaitPublicationAfter(aliasUri, 0, SHORT));
        assertEquals("published before deletion", client.knownDiagnostics(aliasUri).getFirst().getMessage().getLeft());
        client.publishDiagnostics(new PublishDiagnosticsParams(canonicalUri, List.of()));
        assertEquals(2, client.publicationCount(aliasUri));
        assertTrue(client.knownDiagnostics(aliasUri).isEmpty());
    }

    @Test
    void nonFileMalformedAndUnresolvableUrisRetainTheirOriginalLookup() throws Exception {
        Path notADirectory = Files.writeString(workspace.resolve("file.txt"), "not a directory");
        List<String> uris = List.of("untitled:Example.java", "file://[invalid",
                notADirectory.resolve("Child.java").toUri().toString());
        VintelliLanguageClient client = new VintelliLanguageClient();

        for (String uri : uris) {
            client.publishDiagnostics(publication(uri, uri));

            assertTrue(client.awaitPublicationAfter(uri, 0, SHORT), uri);
            assertEquals(uri, client.knownDiagnostics(uri).getFirst().getMessage().getLeft());
        }
    }

    @Test
    void aPublicationThatArrivesBeforeTheWaitStartsStillSatisfiesIt() throws Exception {
        VintelliLanguageClient client = new VintelliLanguageClient();
        int before = client.publicationCount(FIRST);

        client.publishDiagnostics(publication(FIRST, "pushed before the wait"));

        assertTrue(client.awaitPublicationAfter(FIRST, before, SHORT));
        assertEquals("pushed before the wait", client.knownDiagnostics(FIRST).get(0).getMessage().getLeft());
    }

    @Test
    void thePublicationOfAnotherFileDoesNotSatisfyTheWait() throws Exception {
        VintelliLanguageClient client = new VintelliLanguageClient();
        int before = client.publicationCount(FIRST);

        client.publishDiagnostics(publication(SECOND, "belongs to the other file"));

        assertFalse(client.awaitPublicationAfter(FIRST, before, SHORT));
        assertTrue(client.knownDiagnostics(FIRST).isEmpty());
    }

    @Test
    void anOlderPublicationDoesNotSatisfyAWaitForTheNextOne() throws Exception {
        VintelliLanguageClient client = new VintelliLanguageClient();
        client.publishDiagnostics(publication(FIRST, "stale"));
        int before = client.publicationCount(FIRST);

        assertFalse(client.awaitPublicationAfter(FIRST, before, SHORT));

        client.publishDiagnostics(publication(FIRST, "fresh"));
        assertTrue(client.awaitPublicationAfter(FIRST, before, SHORT));
        assertEquals("fresh", client.knownDiagnostics(FIRST).get(0).getMessage().getLeft());
    }

    @Test
    void aPublicationFromAnotherThreadWakesTheWaiter() throws Exception {
        VintelliLanguageClient client = new VintelliLanguageClient();
        int before = client.publicationCount(FIRST);
        Thread pusher = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            client.publishDiagnostics(publication(FIRST, "late push"));
        });
        pusher.setDaemon(true);
        pusher.start();

        assertTrue(client.awaitPublicationAfter(FIRST, before, Duration.ofSeconds(10)));
        pusher.join();
    }

    private static PublishDiagnosticsParams publication(String uri, String message) {
        Diagnostic diagnostic = new Diagnostic(new Range(), message);
        return new PublishDiagnosticsParams(uri, List.of(diagnostic));
    }

    private static Path symlink(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic links are unavailable: " + unavailable.getMessage());
            return link;
        }
    }
}
