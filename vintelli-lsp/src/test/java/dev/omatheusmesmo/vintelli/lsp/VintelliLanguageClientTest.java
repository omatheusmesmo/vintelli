package dev.omatheusmesmo.vintelli.lsp;

import java.time.Duration;
import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
