package dev.omatheusmesmo.vintelli.jsonrpc;

import static dev.omatheusmesmo.vintelli.jsonrpc.TestSupport.await;
import static dev.omatheusmesmo.vintelli.jsonrpc.TestSupport.awaitCondition;
import static dev.omatheusmesmo.vintelli.jsonrpc.TestSupport.awaitFailure;
import static dev.omatheusmesmo.vintelli.jsonrpc.TestSupport.assertNoUnexpectedErrors;
import static dev.omatheusmesmo.vintelli.jsonrpc.TestSupport.params;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JsonRpcEchoTransportTest {

    private static final int INTERLEAVED_REQUEST_COUNT = 8;
    private static final int ORDERED_NOTIFICATION_COUNT = 100;

    static Stream<JsonRpcFraming> framings() {
        return Stream.of(ContentLengthFraming.STANDARD, LineDelimitedFraming.STANDARD);
    }

    private WirePair wire;
    private JsonRpcEndpoint server;
    private JsonRpcEndpoint client;
    private ConcurrentLinkedQueue<Throwable> clientErrors;
    private ConcurrentLinkedQueue<Throwable> serverErrors;

    @BeforeEach
    void openWire() {
        wire = new WirePair();
        clientErrors = new ConcurrentLinkedQueue<>();
        serverErrors = new ConcurrentLinkedQueue<>();
    }

    @AfterEach
    void closeEndpointsAndWire() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        wire.close();
    }

    private void start(JsonRpcFraming framing, JsonRpcEndpoint.RequestListener serverListener,
            JsonRpcEndpoint.NotificationListener serverNotifications,
            JsonRpcEndpoint.RequestListener clientListener,
            JsonRpcEndpoint.NotificationListener clientNotifications) {
        server = new JsonRpcEndpoint(wire.serverSide(framing), serverListener, serverNotifications,
                serverErrors::add);
        client = new JsonRpcEndpoint(wire.clientSide(framing), clientListener, clientNotifications,
                clientErrors::add);
    }

    private void assertCleanTransport() {
        assertNoUnexpectedErrors(List.copyOf(clientErrors));
        assertNoUnexpectedErrors(List.copyOf(serverErrors));
    }

    @ParameterizedTest
    @MethodSource("framings")
    void requestResponseRoundTrip(JsonRpcFraming framing) throws Exception {
        start(framing, new EchoListener(), null, null, null);
        JsonRpcEndpoint.PendingRequest pending = client.sendRequest("echo", params("word", "hello"));
        assertEquals("hello", asMap(await(pending)).get("word"));
        assertCleanTransport();
    }

    @ParameterizedTest
    @MethodSource("framings")
    void nonAsciiResultsRoundTrip(JsonRpcFraming framing) throws Exception {
        start(framing, new EchoListener(), null, null, null);
        JsonRpcEndpoint.PendingRequest pending = client.sendRequest("echo",
                params("text", "caf\u00e9 \u4f60\u597d \ud83d\ude00"));
        Map<String, Object> echoed = asMap(await(pending));
        assertEquals("caf\u00e9 \u4f60\u597d \ud83d\ude00", echoed.get("text"));
        assertCleanTransport();
    }

    @Test
    void nonAsciiNotificationRoundTripOnTheLineFraming() throws Exception {
        RecordingNotifications notifications = new RecordingNotifications();
        start(LineDelimitedFraming.STANDARD, new EchoListener(), notifications, null, null);
        client.sendNotification("session/update", params("text", "caf\u00e9 \u4f60\u597d \ud83d\ude00"));
        notifications.awaitCount(1);
        assertEquals("caf\u00e9 \u4f60\u597d \ud83d\ude00",
                asMap(notifications.received().get(0).params()).get("text"));
        assertCleanTransport();
    }

    @ParameterizedTest
    @MethodSource("framings")
    void notificationDelivery(JsonRpcFraming framing) throws Exception {
        RecordingNotifications notifications = new RecordingNotifications();
        start(framing, new EchoListener(), notifications, null, null);
        client.sendNotification("status/didFocus", params("path", "/src/A.java"));
        notifications.awaitCount(1);
        assertEquals("status/didFocus", notifications.received().get(0).method());
        assertCleanTransport();
    }

    @ParameterizedTest
    @MethodSource("framings")
    void serverInitiatedRequest(JsonRpcFraming framing) throws Exception {
        start(framing, new EchoListener(), null, request -> request.params(), null);
        JsonRpcEndpoint.PendingRequest serverPending =
                server.sendRequest("client/observe", params("topic", "turn"));
        assertEquals(params("topic", "turn"), await(serverPending));
        assertCleanTransport();
    }

    @ParameterizedTest
    @MethodSource("framings")
    void serverInitiatedNotification(JsonRpcFraming framing) throws Exception {
        RecordingNotifications notifications = new RecordingNotifications();
        start(framing, new EchoListener(), null, null, notifications);
        server.sendNotification("agent/update", params("kind", "stream"));
        notifications.awaitCount(1);
        assertEquals("agent/update", notifications.received().get(0).method());
        assertCleanTransport();
    }

    /**
     * Requests must run concurrently: request 0 waits for request 1 to arrive in the
     * peer, which a sequential handler implementation can never satisfy.
     */
    @ParameterizedTest
    @MethodSource("framings")
    void requestHandlersRunConcurrently(JsonRpcFraming framing) throws Exception {
        CountDownLatch secondHandlerArrived = new CountDownLatch(1);
        start(framing, new GateListener(secondHandlerArrived), null, null, null);
        JsonRpcEndpoint.PendingRequest blocked = client.sendRequest("gate",
                params("n", Long.valueOf(0)));
        JsonRpcEndpoint.PendingRequest second = client.sendRequest("gate",
                params("n", Long.valueOf(1)));
        await(second);
        assertEquals(Long.valueOf(0), asMap(await(blocked)).get("n"));
        assertCleanTransport();
    }

    @ParameterizedTest
    @MethodSource("framings")
    void notificationsStayInArrivalOrderAndAResponseDoesNotOvertakeThem(JsonRpcFraming framing)
            throws Exception {
        RecordingNotifications notifications = new JitterRecordingNotifications();
        start(framing, new EchoListener(), notifications, null, null);
        for (int index = 0; index < ORDERED_NOTIFICATION_COUNT; index++) {
            client.sendNotification("session/update", params("n", Long.valueOf(index)));
        }
        JsonRpcEndpoint.PendingRequest closing = client.sendRequest("echo",
                params("n", Long.valueOf(-1)));
        await(closing);
        assertEquals(ORDERED_NOTIFICATION_COUNT, notifications.received().size(),
                "the response must not overtake notification handling");
        for (int index = 0; index < ORDERED_NOTIFICATION_COUNT; index++) {
            long seen = (Long) asMap(notifications.received().get(index).params()).get("n");
            assertEquals(index, seen, "notification " + index + " was reordered");
        }
        assertCleanTransport();
    }

    @Test
    void peerCancellationTargetsOnlyTheInboundRequestOfTheSameIdWindow() throws Exception {
        SlowEchoListener clientListener = new SlowEchoListener();
        start(ContentLengthFraming.STANDARD, new EchoListener(), null, clientListener, null);
        JsonRpcEndpoint.PendingRequest own = client.sendRequest("echo", params("word", "local"));
        JsonRpcEndpoint.PendingRequest peer = server.sendRequest("slow", params("work", "peer"));
        awaitCondition(() -> Boolean.valueOf(clientListener.isHandling(peer.id()))
                && Boolean.valueOf(!peer.response().isDone()));
        server.cancelRequest(peer.id());
        awaitCondition(() -> Boolean.valueOf(clientListener.wasCancelled()));
        awaitFailure(peer, JsonRpcCancelledException.class);
        assertEquals("local", asMap(await(own)).get("word"));
        assertCleanTransport();
    }

    @ParameterizedTest
    @MethodSource("framings")
    void peerCancellationIsAnsweredWithRequestCancelled(JsonRpcFraming framing) throws Exception {
        SlowEchoListener slowListener = new SlowEchoListener();
        start(framing, slowListener, null, null, null);
        JsonRpcEndpoint.PendingRequest pending = client.sendRequest("slow", params("work", "x"));
        awaitCondition(() -> Boolean.valueOf(slowListener.isHandling(pending.id())));
        client.sendNotification(JsonRpcMessageCodec.CANCEL_REQUEST_METHOD,
                params("id", pending.id().asValue()));
        JsonRpcRemoteException failure = awaitFailure(pending, JsonRpcRemoteException.class);
        assertEquals(JsonRpcError.REQUEST_CANCELLED, failure.error().code());
        assertTrue(slowListener.wasCancelled(), "the handler must be told to stop its work");
        assertCleanTransport();
    }

    @ParameterizedTest
    @MethodSource("framings")
    void localCancellationFailsPendingAndStaysQuietAfterTheLateAnswer(JsonRpcFraming framing)
            throws Exception {
        SlowEchoListener slowListener = new SlowEchoListener();
        start(framing, slowListener, null, null, null);
        JsonRpcEndpoint.PendingRequest pending = client.sendRequest("slow", params("work", "x"));
        awaitCondition(() -> Boolean.valueOf(slowListener.isHandling(pending.id())));
        client.cancelRequest(pending.id());
        awaitFailure(pending, JsonRpcCancelledException.class);
        awaitCondition(() -> Boolean.valueOf(slowListener.wasCancelled()),
                "the server side must have received the cancellation notification");
        awaitCondition(() -> Boolean.valueOf(!slowListener.isHandling(pending.id())));
        assertCleanTransport();
    }

    @Test
    void peerCleanCloseFailsPendingRequestsWithConnectionClosed() throws Exception {
        client = new JsonRpcEndpoint(wire.clientSide(LineDelimitedFraming.STANDARD),
                new EchoListener(), null, clientErrors::add);
        JsonRpcEndpoint.PendingRequest pending = client.sendRequest("echo",
                params("word", "hello"));
        wire.stopServerWriter();
        awaitFailure(pending, JsonRpcConnectionClosedException.class);
    }

    @Test
    void localCloseFailsPendingRequestsEvenWhenTheReaderStaysBlocked() throws Exception {
        CountDownLatch released = new CountDownLatch(1);
        try (BlockingStream blockedReader = new BlockingStream(released)) {
            JsonRpcConnection blocked = new JsonRpcConnection(LineDelimitedFraming.STANDARD,
                    blockedReader.reader(), new ByteArrayOutputStream());
            JsonRpcEndpoint endpoint = new JsonRpcEndpoint(blocked, request -> request.params(),
                    null, clientErrors::add);
            JsonRpcEndpoint.PendingRequest pending = endpoint.sendRequest("echo",
                    params("n", Long.valueOf(-1)));
            endpoint.close();
            awaitFailure(pending, JsonRpcConnectionClosedException.class);
        }
    }

    @ParameterizedTest
    @MethodSource("framings")
    void handlerFailureMapsToInternalErrorWithoutLeakingLocalDetail(JsonRpcFraming framing)
            throws Exception {
        start(framing, new EchoListener(), null, null, null);
        JsonRpcEndpoint.PendingRequest pending = client.sendRequest("boom", null);
        JsonRpcRemoteException failure = awaitFailure(pending, JsonRpcRemoteException.class);
        assertEquals(JsonRpcError.INTERNAL_ERROR, failure.error().code());
        assertEquals(JsonRpcEndpoint.INTERNAL_ERROR_MESSAGE, failure.error().message());
        awaitCondition(() -> Boolean.valueOf(!serverErrors.isEmpty()),
                "the internal error must be reported locally too");
        assertTrue(serverErrors.stream().anyMatch(error -> error instanceof IllegalStateException));
        serverErrors.clear();
        clientErrors.clear();
        assertCleanTransport();
    }

    @ParameterizedTest
    @MethodSource("framings")
    void unserializableResultStillAnswersThePeer(JsonRpcFraming framing) throws Exception {
        start(framing, new EchoListener(), null, null, null);
        JsonRpcEndpoint.PendingRequest pending = client.sendRequest("unserializable", null);
        JsonRpcRemoteException failure = awaitFailure(pending, JsonRpcRemoteException.class);
        assertEquals(JsonRpcError.INTERNAL_ERROR, failure.error().code());
        assertEquals(JsonRpcEndpoint.INTERNAL_ERROR_MESSAGE, failure.error().message());
        awaitCondition(() -> Boolean.valueOf(!serverErrors.isEmpty()),
                "an unserializable result must be reported locally too");
        assertTrue(serverErrors.stream().anyMatch(error -> error instanceof IllegalArgumentException));
        serverErrors.clear();
        clientErrors.clear();
        assertCleanTransport();
    }

    @ParameterizedTest
    @MethodSource("framings")
    void handlerTypedErrorIsPreserved(JsonRpcFraming framing) throws Exception {
        start(framing, new EchoListener(), null, null, null);
        JsonRpcEndpoint.PendingRequest pending = client.sendRequest("invalid-params", null);
        JsonRpcRemoteException failure = awaitFailure(pending, JsonRpcRemoteException.class);
        assertEquals(JsonRpcError.INVALID_PARAMS, failure.error().code());
        assertEquals("bad input", failure.error().message());
        assertCleanTransport();
    }

    @ParameterizedTest
    @MethodSource("framings")
    void malformedPayloadIsDroppedAndServiceContinues(JsonRpcFraming framing) throws Exception {
        start(framing, new EchoListener(), null, null, null);
        writeRaw(framing, "{oops this is not json}".getBytes(StandardCharsets.UTF_8));
        JsonRpcEndpoint.PendingRequest pending =
                client.sendRequest("echo", params("word", "still-working"));
        assertEquals("still-working", asMap(await(pending)).get("word"));
        awaitCondition(() -> Boolean.valueOf(!clientErrors.isEmpty()));
        assertTrue(clientErrors.stream()
                .allMatch(error -> error instanceof JsonRpcProtocolException));
        assertCleanTransport();
    }

    @Test
    void malformedInboundRequestWithRecoverableIdIsAnsweredWithInvalidRequest() throws Exception {
        start(ContentLengthFraming.STANDARD, new EchoListener(), null, new EchoListener(), null);
        byte[] malformed = "{\"jsonrpc\":\"2.0\",\"method\":\"\",\"id\":51}"
                .getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        ContentLengthFraming.STANDARD.writeFrame(framed, malformed);
        wire.writeRawFromServer(framed.toByteArray());
        awaitCondition(() -> {
            String answer = new String(wire.tappedServerInput(), StandardCharsets.UTF_8);
            return answer.contains("\"code\":-32600") && answer.contains("\"id\":51")
                    && answer.contains("\"error\"");
        });
        assertCleanTransport();
    }

    @Test
    void peerErrorResponseWithNullIdIsSurfacedThroughTheErrorListener() throws Exception {
        start(ContentLengthFraming.STANDARD, new EchoListener(), null, null, null);
        byte[] raw = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"broken\"}}"
                .getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        ContentLengthFraming.STANDARD.writeFrame(framed, raw);
        wire.writeRawFromServer(framed.toByteArray());
        awaitCondition(() -> Boolean.valueOf(!clientErrors.isEmpty()));
        assertTrue(clientErrors.stream()
                .allMatch(error -> error instanceof JsonRpcProtocolException),
                "only protocol level surfacing expected: " + clientErrors);
        assertCleanTransport();
    }

    private void writeRaw(JsonRpcFraming framing, byte[] payload) throws IOException {
        if (framing instanceof ContentLengthFraming) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            framing.writeFrame(out, payload);
            wire.writeRawFromServer(out.toByteArray());
        } else {
            byte[] terminated = new byte[payload.length + 1];
            System.arraycopy(payload, 0, terminated, 0, payload.length);
            terminated[payload.length] = '\n';
            wire.writeRawFromServer(terminated);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static class EchoListener implements JsonRpcEndpoint.RequestListener {

        @Override
        public Object onRequest(JsonRpcRequest request) {
            return switch (request.method()) {
                case "echo" -> request.params();
                case "boom" -> throw new IllegalStateException("boom request always fails");
                case "unserializable" -> new Object();
                case "invalid-params" -> throw new JsonRpcRequestException(
                        JsonRpcError.of(JsonRpcError.INVALID_PARAMS, "bad input"));
                default -> throw new JsonRpcRequestException(
                        JsonRpcError.of(JsonRpcError.METHOD_NOT_FOUND, "unknown: " + request.method()));
            };
        }
    }

    private static final class GateListener implements JsonRpcEndpoint.RequestListener {

        private final CountDownLatch secondHandlerArrived;

        GateListener(CountDownLatch secondHandlerArrived) {
            this.secondHandlerArrived = secondHandlerArrived;
        }

        @Override
        public Object onRequest(JsonRpcRequest request) {
            long number = (Long) asMap(request.params()).get("n");
            if (number == 1) {
                secondHandlerArrived.countDown();
                return request.params();
            }
            try {
                if (!secondHandlerArrived.await(5L, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "no second handler arrived: handlers must run concurrently");
                }
            } catch (InterruptedException interruptedWait) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted inside the gate", interruptedWait);
            }
            return request.params();
        }
    }

    private static final class SlowEchoListener implements JsonRpcEndpoint.RequestListener {

        private final Map<JsonRpcId, CountDownLatch> active = new ConcurrentHashMap<>();
        private final List<JsonRpcId> preCancelled = new CopyOnWriteArrayList<>();
        private final AtomicBoolean cancelledSight = new AtomicBoolean(false);

        @Override
        public Object onRequest(JsonRpcRequest request) {
            if (!"slow".equals(request.method())) {
                return request.params();
            }
            if (preCancelled.contains(request.id())) {
                throw cancellation(request.id());
            }
            CountDownLatch cancelledWhileServing = new CountDownLatch(1);
            active.put(request.id(), cancelledWhileServing);
            try {
                cancelledWhileServing.await(5L, TimeUnit.SECONDS);
                if (cancelledWhileServing.getCount() == 0) {
                    throw cancellation(request.id());
                }
                return request.params();
            } catch (InterruptedException interruptedWait) {
                Thread.currentThread().interrupt();
                throw cancellation(request.id());
            } finally {
                active.remove(request.id());
            }
        }

        @Override
        public void onCancellation(JsonRpcId cancelledRequestId) {
            cancelledSight.set(true);
            CountDownLatch serving = active.get(cancelledRequestId);
            if (serving != null) {
                serving.countDown();
            } else {
                preCancelled.add(cancelledRequestId);
            }
        }

        boolean isHandling(JsonRpcId id) {
            return active.containsKey(id) || preCancelled.contains(id);
        }

        boolean wasCancelled() {
            return cancelledSight.get();
        }

        private static JsonRpcRequestException cancellation(JsonRpcId id) {
            return new JsonRpcRequestException(JsonRpcError.of(
                    JsonRpcError.REQUEST_CANCELLED, "cancelled while serving " + id));
        }
    }

    private static final class JitterRecordingNotifications extends RecordingNotifications {

        @Override
        public void onNotification(JsonRpcNotification notification) {
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(2L));
            } catch (InterruptedException interruptedJitter) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted during jitter", interruptedJitter);
            }
            super.onNotification(notification);
        }
    }

    private static final class BlockingStream implements AutoCloseable {

        private final CountDownLatch released;

        BlockingStream(CountDownLatch released) {
            this.released = released;
        }

        /**
         * Reads block until the test releases them, and closing never unblocks a
         * read: the test asserts that {@code close} fails pending requests on its
         * own, which a real pipe reader cannot be interrupted out of.
         */
        InputStream reader() {
            return new InputStream() {

                @Override
                public int read() {
                    try {
                        released.await();
                    } catch (InterruptedException interruptedWait) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                    return -1;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    try {
                        released.await();
                    } catch (InterruptedException interruptedWait) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                    return -1;
                }
            };
        }

        @Override
        public void close() {
            released.countDown();
        }
    }

    private static class RecordingNotifications implements JsonRpcEndpoint.NotificationListener {

        private final CopyOnWriteArrayList<JsonRpcNotification> received = new CopyOnWriteArrayList<>();

        @Override
        public void onNotification(JsonRpcNotification notification) {
            received.add(notification);
        }

        List<JsonRpcNotification> received() {
            return List.copyOf(received);
        }

        void awaitCount(int expected) throws InterruptedException, TimeoutException {
            long deadline = System.currentTimeMillis() + TestSupport.AWAIT_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                if (received.size() >= expected) {
                    return;
                }
                Thread.sleep(20L);
            }
            throw new TimeoutException("only " + received.size() + " of "
                    + expected + " notifications arrived");
        }
    }
}
