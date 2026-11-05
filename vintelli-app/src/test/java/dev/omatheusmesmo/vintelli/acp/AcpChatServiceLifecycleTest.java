package dev.omatheusmesmo.vintelli.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@WithTestResource(AcpChatServiceLifecycleResource.class)
class AcpChatServiceLifecycleTest {

    @Inject
    AcpChatService chat;

    @Test
    void shutdownClosesEverySessionAndTransportIncludingAnInFlightPrompt() throws Exception {
        CountDownLatch promptStarted = new CountDownLatch(1);
        String idleSession = chat.openSession(update -> { }, request -> AcpChatPort.PermissionChoice.cancel());
        String activeSession = chat.openSession(update -> {
            if (update instanceof AcpChatPort.Message message && message.text().contains("cancel-requested")) {
                promptStarted.countDown();
            }
        }, request -> AcpChatPort.PermissionChoice.cancel());
        assertEquals(Stream.of(idleSession, activeSession).sorted().toList(), chat.activeSessionIds());
        try (ExecutorService promptWorker = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> pendingPrompt = promptWorker.submit(() -> chat.prompt(activeSession, "cancel-me"));
            assertTrue(promptStarted.await(5, TimeUnit.SECONDS), "the ACP prompt did not reach the twin");

            chat.shutdown();

            assertPromptReleased(pendingPrompt);
            assertThrows(IllegalArgumentException.class, () -> chat.prompt(idleSession, "after-shutdown"));
            assertThrows(IllegalStateException.class,
                    () -> chat.openSession(update -> { }, request -> AcpChatPort.PermissionChoice.cancel()));
            assertEquals(List.of(), chat.activeSessionIds());
        }

        String events = awaitLifecycleEvents();
        assertTrue(events.contains("session-close"), events);
        assertEquals(2, events.lines().filter("agent-exit"::equals).count(), events);
    }

    /**
     * Session close makes the agent answer the in-flight prompt as cancelled, and a transport
     * closed first fails it instead. Either outcome releases the caller; hanging does not.
     */
    private static void assertPromptReleased(Future<String> pendingPrompt) throws Exception {
        try {
            assertEquals("cancelled", pendingPrompt.get(5, TimeUnit.SECONDS),
                    "session close should end the in-flight prompt as cancelled");
        } catch (ExecutionException transportClosed) {
            assertTrue(transportClosed.getCause() instanceof RuntimeException, transportClosed.toString());
        }
    }

    private static String awaitLifecycleEvents() throws IOException, InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            String events = Files.readString(AcpChatServiceLifecycleResource.lifecycleLog());
            long stopped = events.lines().filter("agent-exit"::equals).count();
            if (stopped == 2) return events;
            Thread.sleep(25);
        }
        throw new TimeoutException("ACP twins did not exit after service shutdown: "
                + Files.readString(AcpChatServiceLifecycleResource.lifecycleLog()));
    }
}
