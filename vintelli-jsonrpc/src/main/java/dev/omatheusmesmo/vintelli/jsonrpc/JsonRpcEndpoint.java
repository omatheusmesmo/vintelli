package dev.omatheusmesmo.vintelli.jsonrpc;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Full duplex JSON-RPC peer over a {@link JsonRpcConnection}, running on virtual
 * threads. One virtual thread reads frames; inbound notifications and response
 * completions run in strict arrival order on a serial dispatcher, so a response for
 * an earlier request never overtakes a notification received before it and session
 * updates stay ordered. Inbound request handlers run concurrently on their own
 * virtual threads, so a handler may call {@code sendRequest(...).join()} without
 * deadlock; outbound responses are serialized internally by the connection.
 *
 * <p>Id spaces are per direction: an id this endpoint assigns to its own outgoing
 * requests does not stand for the ids the peer assigns to requests it sends here. A
 * peer {@code $/cancelRequest} therefore only affects inbound requests, and it is
 * answered with {@code REQUEST_CANCELLED} when the id names a request this endpoint
 * is currently serving; the request listener is always notified so it can stop its
 * work.</p>
 *
 * <p>Malformed payloads never stop the transport: the offending frame is dropped,
 * requests with a recoverable id are answered with {@code INVALID_REQUEST}, and the
 * connection keeps exchanging frames. Framing level faults (bad or oversized
 * headers) are unrecoverable: the read loop stops, all pending requests complete
 * exceptionally with {@link JsonRpcConnectionClosedException}, and the connection is
 * closed. {@link #close()} fails every pending request itself, and never relies on
 * the reader unblocking.</p>
 */
public final class JsonRpcEndpoint implements AutoCloseable {

    static final String INTERNAL_ERROR_MESSAGE = "internal error while serving the request";

    /**
     * Consumes inbound requests and produces the result value of the response. A
     * handler that cannot fulfill a request throws {@link JsonRpcRequestException} to
     * control the error response; any other exception is reported locally and
     * answered with the generic internal error, without leaking local exception text
     * to the peer.
     */
    @FunctionalInterface
    public interface RequestListener {

        Object onRequest(JsonRpcRequest request) throws JsonRpcRequestException;

        /**
         * Notified for every inbound {@code $/cancelRequest} the peer sends. When the
         * id names a request this endpoint is currently serving, the endpoint itself
         * answers it with {@code REQUEST_CANCELLED} and this notification lets the
         * handler stop its work; otherwise the id names no in-flight request.
         */
        default void onCancellation(JsonRpcId cancelledRequestId) {
        }
    }

    /**
     * Consumes inbound notifications other than the handled cancellation mechanism.
     */
    @FunctionalInterface
    public interface NotificationListener {

        void onNotification(JsonRpcNotification notification);
    }

    /** A request sent through this endpoint: its id and its completion handle. */
    public record PendingRequest(JsonRpcId id, CompletableFuture<Object> response) {

        /**
         * Completes with the response result. A failed completion rethrows as a
         * {@link java.util.concurrent.CompletionException} whose cause is one of
         * {@link JsonRpcRemoteException}, {@link JsonRpcCancelledException} or
         * {@link JsonRpcConnectionClosedException}, so callers blocking here see the
         * transport failure through the completion exception, not inline.
         */
        public Object join() {
            return response.join();
        }
    }

    private static final int READER_JOIN_MILLIS = 500;

    private final JsonRpcConnection connection;
    private final RequestListener requestListener;
    private final NotificationListener notificationListener;
    private final Consumer<Throwable> errorListener;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final SerialDispatcher dispatcher = new SerialDispatcher();
    private final Map<JsonRpcId, CompletableFuture<Object>> outgoing = new ConcurrentHashMap<>();
    private final Map<JsonRpcId, AtomicBoolean> inboundAnswered = new ConcurrentHashMap<>();
    private final Set<JsonRpcId> locallyCancelledIds = new CopyOnWriteArraySet<>();
    private final AtomicLong idSequence = new AtomicLong(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread readerThread;

    /**
     * Starts the endpoint. The error listener, which receives every dropped message
     * and fault the endpoint survived, may be null to discard those events. A null
     * request listener answers inbound requests with the method not found error, a
     * null notification listener discards inbound notifications, so a pure requester
     * or a pure responder is valid.
     */
    public JsonRpcEndpoint(JsonRpcConnection connection, RequestListener requestListener,
            NotificationListener notificationListener, Consumer<Throwable> errorListener) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.requestListener = requestListener;
        this.notificationListener = notificationListener;
        this.errorListener = errorListener != null ? errorListener : fault -> {
        };
        readerThread = Thread.ofVirtual().name("jsonrpc-endpoint-reader").start(this::readLoop);
    }

    /**
     * Sends a request and resolves the completion handle with the response result, or
     * exceptionally with {@link JsonRpcRemoteException} (peer answered with an error),
     * {@link JsonRpcCancelledException} (cancelled locally through
     * {@link #cancelRequest(JsonRpcId)}), or {@link JsonRpcConnectionClosedException}
     * (connection failed or was closed before the answer). Consumers that need a
     * timeout can apply {@code pending.response().orTimeout(duration)} on the
     * completion handle; the pending entry is removed from the registry as soon as
     * the handle completes either way.
     */
    public PendingRequest sendRequest(String method, Object params) {
        JsonRpcId id = JsonRpcId.of(idSequence.getAndIncrement());
        CompletableFuture<Object> response = new CompletableFuture<>();
        response.whenComplete((ignoredResult, ignoredFailure) -> {
            outgoing.remove(id, response);
            locallyCancelledIds.remove(id);
        });
        outgoing.put(id, response);
        try {
            connection.send(new JsonRpcRequest(id, method, params));
        } catch (RuntimeException encodeFailure) {
            response.completeExceptionally(new JsonRpcProtocolException(
                    "request " + method + " could not be encoded", encodeFailure));
        } catch (IOException sendFailure) {
            response.completeExceptionally(new JsonRpcConnectionClosedException(
                    "request " + method + " could not be sent", sendFailure));
        }
        return new PendingRequest(id, response);
    }

    /**
     * Sends a notification. A cancellation sent here also fails the matching pending
     * request locally, mirroring the peer side effect of {@code $/cancelRequest}; a
     * response that arrives later for that id is dropped quietly.
     */
    public void sendNotification(String method, Object params) throws IOException {
        connection.send(new JsonRpcNotification(method, params));
    }

    public void cancelRequest(JsonRpcId targetId) throws IOException {
        CompletableFuture<Object> pending = outgoing.remove(targetId);
        if (pending != null) {
            locallyCancelledIds.add(targetId);
            pending.completeExceptionally(new JsonRpcCancelledException());
        }
        connection.send(JsonRpcMessageCodec.cancellationNotification(targetId));
    }

    /**
     * Closes the endpoint: every pending request fails with
     * {@link JsonRpcConnectionClosedException} from this call, even when the reader is
     * blocked in a read that closing the stream cannot interrupt. The reader and the
     * serial dispatcher stop on their own afterwards.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        failAllOutgoing();
        connection.close();
        executor.shutdown();
        dispatcher.stop();
        try {
            readerThread.join(READER_JOIN_MILLIS);
        } catch (InterruptedException interruptedClose) {
            Thread.currentThread().interrupt();
        }
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                JsonRpcMessage message;
                try {
                    message = connection.receive();
                } catch (JsonRpcProtocolException malformedPayload) {
                    reportProtocolFault(malformedPayload);
                    continue;
                }
                if (message == null) {
                    break;
                }
                dispatch(message);
            }
        } catch (IOException streamFailure) {
            if (!closed.get()) {
                errorListener.accept(streamFailure);
            }
        } finally {
            if (closed.compareAndSet(false, true)) {
                connection.close();
            }
            failAllOutgoing();
            executor.shutdown();
            dispatcher.stop();
        }
    }

    private void dispatch(JsonRpcMessage message) {
        if (closed.get()) {
            return;
        }
        switch (message) {
            case JsonRpcResponse response -> dispatchAfterEarlierPayloads(response);
            case JsonRpcRequest request -> dispatchRequestAfterEarlierPayloads(request);
            case JsonRpcNotification notification -> dispatchNotification(notification);
        }
    }

    /**
     * Response completions ride the same ordered pipeline as notifications, so a
     * response for an earlier request is only delivered once every payload received
     * before it was processed.
     */
    private void dispatchAfterEarlierPayloads(JsonRpcResponse response) {
        dispatcher.enqueue(() -> resolveResponse(response));
    }

    /**
     * Request handling keeps inbound order: the handler is only handed to the
     * executor once every payload received before the request was processed, so a
     * server can rely on its notification side effects being visible. Requests
     * still run concurrently with each other once started.
     */
    private void dispatchRequestAfterEarlierPayloads(JsonRpcRequest request) {
        dispatcher.enqueue(() -> startRequestServing(request));
    }

    private void startRequestServing(JsonRpcRequest request) {
        try {
            executor.execute(() -> serveRequest(request));
        } catch (RejectedExecutionException afterClose) {
            dropAfterClose(request, afterClose);
        }
    }

    private void dispatchNotification(JsonRpcNotification notification) {
        if (JsonRpcMessageCodec.isCancellation(notification)) {
            JsonRpcId target = JsonRpcMessageCodec.cancellationTarget(notification);
            if (target == null) {
                errorListener.accept(new JsonRpcProtocolException(
                        "cancellation notification without a target id"));
                return;
            }
            dispatcher.enqueue(() -> handlePeerCancellation(target));
            return;
        }
        dispatcher.enqueue(() -> deliverNotification(notification));
    }

    private void deliverNotification(JsonRpcNotification notification) {
        if (notificationListener != null) {
            try {
                notificationListener.onNotification(notification);
            } catch (RuntimeException listenerFailure) {
                errorListener.accept(listenerFailure);
            }
        }
    }

    private void handlePeerCancellation(JsonRpcId targetId) {
        AtomicBoolean serving = inboundAnswered.remove(targetId);
        if (serving != null && serving.compareAndSet(false, true)) {
            answerAndReportFaults(JsonRpcResponse.failure(targetId, JsonRpcError.of(
                    JsonRpcError.REQUEST_CANCELLED, "request cancelled by the peer")));
        }
        if (requestListener != null) {
            try {
                requestListener.onCancellation(targetId);
            } catch (RuntimeException listenerFailure) {
                errorListener.accept(listenerFailure);
            }
        }
    }

    private void resolveResponse(JsonRpcResponse response) {
        if (response.id() == null) {
            errorListener.accept(new JsonRpcProtocolException(
                    "peer answered with an error response and id null: "
                            + (response.error() != null ? response.error().message() : "unknown")));
            return;
        }
        CompletableFuture<Object> pending = outgoing.remove(response.id());
        if (pending == null) {
            if (!locallyCancelledIds.contains(response.id())) {
                errorListener.accept(new JsonRpcProtocolException(
                        "no pending request for response id " + response.id()));
            }
            return;
        }
        if (response.error() != null) {
            pending.completeExceptionally(new JsonRpcRemoteException(response.error()));
        } else {
            pending.complete(response.result());
        }
    }

    private void serveRequest(JsonRpcRequest request) {
        JsonRpcId requestId = request.id();
        AtomicBoolean answered = new AtomicBoolean(false);
        inboundAnswered.put(requestId, answered);
        try {
            JsonRpcResponse response = computeResponse(request);
            if (answered.compareAndSet(false, true)) {
                answerAndReportFaults(response);
            }
        } finally {
            inboundAnswered.remove(requestId, answered);
        }
    }

    private JsonRpcResponse computeResponse(JsonRpcRequest request) {
        if (requestListener == null) {
            return JsonRpcResponse.failure(request.id(),
                    JsonRpcError.of(JsonRpcError.METHOD_NOT_FOUND, "requests are not accepted here"));
        }
        try {
            Object result = requestListener.onRequest(request);
            return JsonRpcResponse.success(request.id(), result);
        } catch (JsonRpcRequestException handlerFailure) {
            return JsonRpcResponse.failure(request.id(), handlerFailure.error());
        } catch (Throwable unexpectedFailure) {
            errorListener.accept(unexpectedFailure);
            return JsonRpcResponse.failure(request.id(),
                    JsonRpcError.of(JsonRpcError.INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE));
        }
    }

    /**
     * Sends a response once, first wins. An unencodable response result or an I/O
     * failure is reported locally; for an unencodable result the peer still receives
     * the generic internal error so it never blocks waiting.
     */
    private void answerAndReportFaults(JsonRpcResponse response) {
        try {
            connection.send(response);
            return;
        } catch (IOException sendFailure) {
            errorListener.accept(sendFailure);
        } catch (RuntimeException payloadFailure) {
            errorListener.accept(payloadFailure);
        }
        try {
            connection.send(JsonRpcResponse.failure(response.id(),
                    JsonRpcError.of(JsonRpcError.INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE)));
        } catch (IOException | RuntimeException fallbackFailure) {
            errorListener.accept(fallbackFailure);
        }
    }

    private void reportProtocolFault(JsonRpcProtocolException fault) {
        if (fault.recoverableId() != null) {
            answerAndReportFaults(JsonRpcResponse.failure(fault.recoverableId(),
                    JsonRpcError.of(JsonRpcError.INVALID_REQUEST, fault.getMessage())));
            return;
        }
        errorListener.accept(fault);
    }

    private void dropAfterClose(JsonRpcMessage message, RejectedExecutionException afterClose) {
        if (!closed.get()) {
            errorListener.accept(afterClose);
        }
    }

    private void failAllOutgoing() {
        JsonRpcConnectionClosedException shutdown =
                new JsonRpcConnectionClosedException("connection closed before a response arrived");
        for (CompletableFuture<Object> pending : outgoing.values()) {
            pending.completeExceptionally(shutdown);
        }
    }

    /**
     * Serial pipeline for inbound notifications, cancellation handling, response
     * resolution, and the start of request handling: one virtual thread executes the
     * queued work in arrival order, so ordering survives independent of thread
     * scheduling, and a {@link Boolean} marker stops the worker on close.
     */
    private final class SerialDispatcher {

        private final BlockingQueue<Object> work = new LinkedBlockingQueue<>();
        private final Thread worker = Thread.ofVirtual().name("jsonrpc-endpoint-dispatcher")
                .start(this::drain);

        void enqueue(Runnable task) {
            if (closed.get()) {
                return;
            }
            work.add(task);
        }

        void stop() {
            work.add(Boolean.TRUE);
        }

        private void drain() {
            try {
                while (true) {
                    Object task = work.take();
                    if (task instanceof Boolean) {
                        return;
                    }
                    run(((Runnable) task));
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }

        private void run(Runnable task) {
            try {
                task.run();
            } catch (RuntimeException dispatchFailure) {
                errorListener.accept(dispatchFailure);
            }
        }
    }
}
