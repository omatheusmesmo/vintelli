package dev.omatheusmesmo.vintelli.debug;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.ContinuedEventArguments;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.TerminatedEventArguments;
import org.eclipse.lsp4j.debug.ThreadsResponse;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.AttachRequest;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugRequest;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugSessionSnapshot;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugThread;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.State;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability.RunCompletion;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability.RunHandle;
import dev.omatheusmesmo.vintelli.lsp.JavaDebugAdapterPort;
import io.quarkus.arc.Unremovable;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/** Optional DAP provider backed by the Java debug adapter started by jdt.ls. */
@Singleton
@Unremovable
public final class JavaDebugCapability implements DebugCapability {

    private static final String LOOPBACK = "127.0.0.1";
    private static final Duration ADAPTER_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration EVENT_POLL_INTERVAL = Duration.ofMillis(100);
    private static final int MAX_ACTIVE_SESSIONS = 8;
    private static final int MAX_RETAINED_SESSIONS = 32;
    private static final int OUTPUT_QUEUE_CAPACITY = 256;

    private final boolean enabled;
    private final JavaDebugAdapterPort adapter;
    private final Provider<CapabilityRegistry> registry;
    private final Duration adapterConnectTimeout;
    private final Duration sessionTimeout;
    private final Duration requestTimeout;
    private final Semaphore activeSessions = new Semaphore(MAX_ACTIVE_SESSIONS);
    private final ConcurrentHashMap<String, DapSession> sessions = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private boolean closing;

    @Inject
    public JavaDebugCapability(@ConfigProperty(name = "vintelli.debug.enabled", defaultValue = "false") boolean enabled,
            JavaDebugAdapterPort adapter, Provider<CapabilityRegistry> registry) {
        this(enabled, adapter, registry, ADAPTER_CONNECT_TIMEOUT, SESSION_TIMEOUT, REQUEST_TIMEOUT);
    }

    JavaDebugCapability(boolean enabled, JavaDebugAdapterPort adapter, Provider<CapabilityRegistry> registry,
            Duration adapterConnectTimeout, Duration sessionTimeout, Duration requestTimeout) {
        this.enabled = enabled;
        this.adapter = adapter;
        this.registry = registry;
        this.adapterConnectTimeout = adapterConnectTimeout;
        this.sessionTimeout = sessionTimeout;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public String identifier() {
        return "java-dap";
    }

    @Override
    public ProviderHealth health() {
        if (!enabled) {
            return ProviderHealth.unavailable("Java debugging is disabled; set vintelli.debug.enabled=true");
        }
        synchronized (lifecycleLock) {
            if (closing) {
                return ProviderHealth.unavailable("The Java debug provider is shutting down");
            }
        }
        return adapter.debugAdapterHealth();
    }

    @Override
    public String provenance() {
        return "jdt.ls Java debug bundle over DAP";
    }

    @Override
    public CapabilityResult<DebugSession> launch(DebugRequest request, Consumer<String> outputLines) {
        ProviderHealth providerHealth = health();
        if (!providerHealth.isAvailable()) {
            return unavailable(providerHealth.reason());
        }
        if (request == null) {
            return unavailable("A Java debug launch request is required");
        }
        CapabilitySelection<RunCapability> selected = registry.get().run();
        if (!(selected instanceof CapabilitySelection.Selected<RunCapability> available)) {
            return unavailable(((CapabilitySelection.Unavailable<RunCapability>) selected).reason());
        }
        RunCapability runs = available.provider();
        RunCapability.DebugSupport support = runs.debugSupport(request.runRequest().configurationName());
        if (!support.available()) {
            return unavailable(support.reason());
        }
        int targetPort;
        try {
            targetPort = freeLoopbackPort();
        } catch (IOException failure) {
            return unavailable("Cannot reserve a loopback debug port: " + failure.getMessage());
        }
        DapSession session = newSession(request.label(), true, outputLines);
        if (session == null) {
            return unavailable("Too many active Java debug sessions");
        }
        try {
            CapabilityResult<Integer> adapterPort = adapter.startDebugAdapter();
            if (adapterPort instanceof CapabilityResult.Failed<Integer> failed) {
                session.fail(failed.failure().reason());
                return unavailable(failed.failure().reason());
            }
            session.connect(((CapabilityResult.Ok<Integer>) adapterPort).value());
            session.initializeAdapter();
            if (session.isTerminal()) {
                throw new IOException("The Java debug provider shut down before launching the debuggee");
            }
            session.state(State.STARTING, "");
            CapabilityResult<RunHandle> started = runs.startDebug(request.runRequest(), targetPort,
                    line -> session.output(outputLines, line), completion -> session.runCompleted(completion));
            if (started instanceof CapabilityResult.Failed<RunHandle> failed) {
                session.fail(failed.failure().reason());
                return unavailable(failed.failure().reason());
            }
            session.setRunHandle(((CapabilityResult.Ok<RunHandle>) started).value());
            return configure(session, targetPort);
        } catch (Exception failure) {
            String reason = failureReason(failure);
            session.fail(reason);
            return unavailable(reason);
        }
    }

    @Override
    public CapabilityResult<DebugSession> attach(AttachRequest request, Consumer<String> outputLines) {
        ProviderHealth health = health();
        if (!health.isAvailable()) {
            return unavailable(health.reason());
        }
        if (request == null) {
            return unavailable("A Java debug attach request is required");
        }
        DapSession session = newSession(request.label(), false, outputLines);
        if (session == null) {
            return unavailable("Too many active Java debug sessions");
        }
        try {
            CapabilityResult<Integer> adapterPort = adapter.startDebugAdapter();
            if (adapterPort instanceof CapabilityResult.Failed<Integer> failed) {
                session.fail(failed.failure().reason());
                return unavailable(failed.failure().reason());
            }
            session.connect(((CapabilityResult.Ok<Integer>) adapterPort).value());
            session.initializeAdapter();
            return configure(session, request.port());
        } catch (Exception failure) {
            String reason = failureReason(failure);
            session.fail(reason);
            return unavailable(reason);
        }
    }

    @Override
    public List<DebugSessionSnapshot> snapshot() {
        return sessions.values().stream().map(DapSession::snapshot)
                .sorted((left, right) -> left.id().compareTo(right.id())).toList();
    }

    @PreDestroy
    void closeSessions() {
        List<DapSession> closingSessions;
        synchronized (lifecycleLock) {
            closing = true;
            closingSessions = List.copyOf(sessions.values());
        }
        closingSessions.forEach(DapSession::shutdown);
        sessions.clear();
    }

    private DapSession newSession(String label, boolean ownsRun, Consumer<String> outputLines) {
        synchronized (lifecycleLock) {
            if (closing) {
                return null;
            }
            trimSessions();
            if (!activeSessions.tryAcquire()) {
                return null;
            }
            DapSession session = new DapSession(label, ownsRun, outputLines);
            sessions.put(session.id, session);
            return session;
        }
    }

    private void trimSessions() {
        List<DapSession> finished = sessions.values().stream().filter(DapSession::isTerminal)
                .sorted(Comparator.comparingLong(session -> session.createdAt)).toList();
        int excess = sessions.size() - MAX_RETAINED_SESSIONS + 1;
        for (int index = 0; index < excess && index < finished.size(); index++) {
            sessions.remove(finished.get(index).id, finished.get(index));
        }
    }

    private CapabilityResult<DebugSession> configure(DapSession session, int targetPort) {
        try {
            if (!session.configure(targetPort)) {
                String reason = session.snapshot().reason();
                session.fail(reason.isBlank() ? "The debug session ended while attaching" : reason);
                return unavailable(session.snapshot().reason());
            }
            return CapabilityResult.ok(session);
        } catch (Exception failure) {
            String reason = failureReason(failure);
            session.fail(reason);
            return unavailable(reason);
        }
    }

    private static int freeLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName(LOOPBACK), 0));
            return socket.getLocalPort();
        }
    }

    private static String failureReason(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static CapabilityResult<DebugSession> unavailable(String reason) {
        return CapabilityResult.failed(new CapabilityFailure(reason, ""));
    }

    private final class DapSession implements DebugSession {

        private final String id = UUID.randomUUID().toString();
        private final String label;
        private final boolean ownsRun;
        private final Consumer<String> outputLines;
        private final AtomicReference<DebugSessionSnapshot> current;
        private final AtomicBoolean cleanupStarted = new AtomicBoolean();
        private final AtomicBoolean runCancellationRequested = new AtomicBoolean();
        private final CompletableFuture<Void> cleanupComplete = new CompletableFuture<>();
        private final long createdAt = System.nanoTime();
        private final ExecutorService listenerExecutor = Executors.newVirtualThreadPerTaskExecutor();
        private final ExecutorService cleanupExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        private final ThreadPoolExecutor outputExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(OUTPUT_QUEUE_CAPACITY), Thread.ofVirtual().factory(),
                new ThreadPoolExecutor.DiscardOldestPolicy());
        private final ExecutorService readerWatcher = Executors.newVirtualThreadPerTaskExecutor();
        private final DapClient callbacks = new DapClient();
        private volatile Socket socket;
        private volatile Launcher<IDebugProtocolServer> launcher;
        private volatile Future<Void> listening;
        private volatile IDebugProtocolServer server;
        private volatile RunHandle runHandle;
        private volatile boolean terminal;

        private DapSession(String label, boolean ownsRun, Consumer<String> outputLines) {
            this.label = label;
            this.ownsRun = ownsRun;
            this.outputLines = outputLines == null ? ignored -> { } : outputLines;
            current = new AtomicReference<>(new DebugSessionSnapshot(id, label, State.ATTACHING, List.of(), "", ""));
        }

        private void connect(int adapterPort) throws IOException {
            synchronized (this) {
                if (terminal) {
                    throw new IOException("The Java debug session was cancelled before connecting");
                }
                Socket connected = new Socket();
                socket = connected;
                connected.connect(new InetSocketAddress(InetAddress.getByName(LOOPBACK), adapterPort),
                        Math.toIntExact(adapterConnectTimeout.toMillis()));
                launcher = DSPLauncher.createClientLauncher(callbacks, connected.getInputStream(),
                        connected.getOutputStream(), listenerExecutor, endpoint -> endpoint);
                server = launcher.getRemoteProxy();
                listening = launcher.startListening();
                watchReader(listening);
            }
        }

        private void watchReader(Future<Void> reader) {
            readerWatcher.execute(() -> {
                try {
                    reader.get();
                    if (!cleanupStarted.get()) {
                        terminal(State.TERMINATED, "The DAP stream closed", "");
                        scheduleCleanup();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    if (!cleanupStarted.get()) {
                        terminal(State.FAILED, failureReason(failure), "");
                        scheduleCleanup();
                    }
                } catch (CancellationException cancelled) {
                    if (!cleanupStarted.get()) {
                        terminal(State.FAILED, "The DAP stream was cancelled unexpectedly", "");
                        scheduleCleanup();
                    }
                }
            });
        }

        private void initializeAdapter() throws Exception {
            IDebugProtocolServer protocol = server;
            if (protocol == null) {
                throw new IOException("The debug adapter connection is not available");
            }
            InitializeRequestArguments arguments = new InitializeRequestArguments();
            arguments.setAdapterID("java");
            arguments.setClientID("vintelli");
            arguments.setClientName("Vintelli");
            arguments.setLinesStartAt1(true);
            Capabilities capabilities = protocol.initialize(arguments).get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (capabilities == null || !Boolean.TRUE.equals(capabilities.getSupportsConfigurationDoneRequest())) {
                throw new IOException("The Java debug adapter does not support configurationDone");
            }
            if (isTerminal()) {
                throw new IOException("The debug adapter ended during initialization");
            }
        }

        private boolean configure(int targetPort) throws Exception {
            IDebugProtocolServer protocol = server;
            if (protocol == null) {
                throw new IOException("The debug adapter connection is not available");
            }
            if (isTerminal()) {
                return false;
            }
            state(State.ATTACHING, "");
            long deadline = System.nanoTime() + sessionTimeout.toNanos();
            CompletableFuture<Void> initialized = callbacks.armInitialized();
            CompletableFuture<Void> attach = protocol.attach(Map.of("hostName", LOOPBACK, "port", targetPort));
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new TimeoutException("Timed out attaching to 127.0.0.1:" + targetPort);
                }
                try {
                    initialized.get(Math.min(remaining,
                            Math.min(requestTimeout.toNanos(), EVENT_POLL_INTERVAL.toNanos())), TimeUnit.NANOSECONDS);
                    if (isTerminal()) {
                        return false;
                    }
                    break;
                } catch (TimeoutException waitingForInitialized) {
                    if (isTerminal()) {
                        return false;
                    }
                    if (initialized.isDone()) {
                        break;
                    }
                    if (attach.isCompletedExceptionally()) {
                        try {
                            attach.get();
                        } catch (ExecutionException failedAttempt) {
                            if (System.nanoTime() >= deadline) {
                                throw new TimeoutException("Timed out attaching to 127.0.0.1:" + targetPort);
                            }
                            Thread.sleep(Math.min(150, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))));
                            initialized = callbacks.armInitialized();
                            attach = protocol.attach(Map.of("hostName", LOOPBACK, "port", targetPort));
                        }
                    }
                }
            }
            if (isTerminal()) {
                return false;
            }
            protocol.configurationDone(new ConfigurationDoneArguments())
                    .get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException("Timed out completing Java debug configuration");
            }
            attach.get(remaining, TimeUnit.NANOSECONDS);
            if (isTerminal()) {
                return false;
            }
            transitionToRunningUnlessStopped();
            return true;
        }

        private void setRunHandle(RunHandle handle) {
            runHandle = handle;
            if (isTerminal()) {
                cancelRunHandle(handle);
            }
        }

        private void cancelRunHandle(RunHandle handle) {
            if (ownsRun && handle != null && runCancellationRequested.compareAndSet(false, true)) {
                try {
                    handle.cancel();
                } catch (RuntimeException ignored) {
                }
            }
        }

        private void output(Consumer<String> consumer, String line) {
            if (consumer == null) {
                return;
            }
            try {
                outputExecutor.execute(() -> {
                    try {
                        consumer.accept(line);
                    } catch (RuntimeException ignored) {
                    }
                });
            } catch (RejectedExecutionException ignored) {
            }
        }

        private void runCompleted(RunCompletion completion) {
            String reason = completion.summary();
            terminal(State.TERMINATED, reason, "");
            scheduleCleanup();
        }

        private void eventStopped(StoppedEventArguments event) {
            String reason = event == null || event.getReason() == null ? "stopped" : event.getReason();
            synchronized (this) {
                if (terminal) {
                    return;
                }
                update(snapshot -> new DebugSessionSnapshot(id, label, State.STOPPED, snapshot.threads(), "", reason));
            }
        }

        private void eventContinued() {
            if (!isTerminal()) {
                state(State.RUNNING, "");
            }
        }

        private void eventTerminated() {
            terminal(State.TERMINATED, "The debuggee terminated", "");
            scheduleCleanup();
        }

        private void eventOutput(String output) {
            if (output != null && !output.isEmpty()) {
                output(outputLines, output);
            }
        }

        private void terminal(State state, String reason, String stoppedReason) {
            synchronized (this) {
                if (terminal) {
                    return;
                }
                terminal = true;
                update(snapshot -> new DebugSessionSnapshot(id, label, state, snapshot.threads(), reason, stoppedReason));
            }
        }

        private boolean isTerminal() {
            return terminal;
        }

        private void transitionToRunningUnlessStopped() {
            synchronized (this) {
                if (!terminal && current.get().state() != State.STOPPED) {
                    update(snapshot -> new DebugSessionSnapshot(id, label, State.RUNNING,
                            snapshot.threads(), "", ""));
                }
            }
        }

        private void state(State state, String reason) {
            synchronized (this) {
                if (terminal) {
                    return;
                }
                update(snapshot -> new DebugSessionSnapshot(id, label, state, snapshot.threads(), reason, ""));
            }
        }

        private void fail(String reason) {
            terminal(State.FAILED, reason, "");
            cleanup(true);
            try {
                cleanupComplete.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException ignored) {
            }
        }

        private void scheduleCleanup() {
            try {
                cleanupExecutor.execute(() -> cleanup(ownsRun));
            } catch (RejectedExecutionException ignored) {
                CompletableFuture.runAsync(() -> cleanup(ownsRun));
            }
        }

        private void cleanup(boolean cancelOwnedRun) {
            if (!cleanupStarted.compareAndSet(false, true)) {
                return;
            }
            IDebugProtocolServer protocol = server;
            try {
                if (cancelOwnedRun) {
                    cancelRunHandle(runHandle);
                }
                if (protocol != null) {
                    try {
                        DisconnectArguments arguments = new DisconnectArguments();
                        arguments.setTerminateDebuggee(false);
                        protocol.disconnect(arguments).get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                Socket connected = socket;
                if (connected != null) {
                    try {
                        connected.close();
                    } catch (IOException ignored) {
                    }
                }
                Future<Void> reader = listening;
                if (reader != null) {
                    reader.cancel(true);
                }
                listenerExecutor.shutdownNow();
                outputExecutor.shutdownNow();
                cleanupExecutor.shutdown();
                readerWatcher.shutdownNow();
                activeSessions.release();
                cleanupComplete.complete(null);
            }
        }

        private void shutdown() {
            terminal(State.DISCONNECTED, "Provider shutdown", "");
            cancelRunHandle(runHandle);
            cleanup(true);
            try {
                cleanupComplete.get(requestTimeout.toMillis() * 2, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException ignored) {
            }
        }

        private void update(UnaryOperator<DebugSessionSnapshot> transition) {
            current.updateAndGet(transition);
        }

        @Override
        public DebugSessionSnapshot snapshot() {
            return current.get();
        }

        @Override
        public CapabilityResult<List<DebugThread>> threads() {
            IDebugProtocolServer protocol = server;
            if (protocol == null || isTerminal()) {
                return CapabilityResult.failed(new CapabilityFailure("The debug session is not active", ""));
            }
            try {
                ThreadsResponse response = protocol.threads().get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
                List<DebugThread> threads = response == null || response.getThreads() == null
                        ? List.of()
                        : Arrays.stream(response.getThreads())
                                .filter(Objects::nonNull)
                                .map(thread -> new DebugThread(thread.getId(), thread.getName()))
                                .toList();
                List<DebugThread> copy = List.copyOf(threads);
                update(snapshot -> new DebugSessionSnapshot(id, label, snapshot.state(), copy,
                        snapshot.reason(), snapshot.stoppedReason()));
                return CapabilityResult.ok(copy);
            } catch (Exception failure) {
                return CapabilityResult.failed(new CapabilityFailure(failureReason(failure), ""));
            }
        }

        @Override
        public CapabilityResult<Boolean> disconnect() {
            terminal(State.DISCONNECTED, "Disconnected", "");
            cleanup(true);
            return CapabilityResult.ok(Boolean.TRUE);
        }

        @Override
        public void cancel() {
            terminal(State.DISCONNECTED, "Cancelled", "");
            scheduleCleanup();
        }

        private final class DapClient implements IDebugProtocolClient {

            private final AtomicReference<CompletableFuture<Void>> initialized =
                    new AtomicReference<>(new CompletableFuture<>());

            private CompletableFuture<Void> armInitialized() {
                CompletableFuture<Void> next = new CompletableFuture<>();
                initialized.set(next);
                return next;
            }

            @Override
            public void initialized() {
                initialized.get().complete(null);
            }

            @Override
            public void stopped(StoppedEventArguments event) {
                eventStopped(event);
            }

            @Override
            public void continued(ContinuedEventArguments event) {
                eventContinued();
            }

            @Override
            public void terminated(TerminatedEventArguments event) {
                eventTerminated();
            }

            @Override
            public void output(OutputEventArguments event) {
                if (event != null) {
                    eventOutput(event.getOutput());
                }
            }
        }
    }
}
