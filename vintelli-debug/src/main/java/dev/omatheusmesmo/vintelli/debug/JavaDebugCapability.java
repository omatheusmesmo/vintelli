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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.TreeMap;
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
import java.util.stream.Collectors;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.ContinuedEventArguments;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.TerminatedEventArguments;
import org.eclipse.lsp4j.debug.Breakpoint;
import org.eclipse.lsp4j.debug.ContinueArguments;
import org.eclipse.lsp4j.debug.NextArguments;
import org.eclipse.lsp4j.debug.PauseArguments;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.StackTraceResponse;
import org.eclipse.lsp4j.debug.StepInArguments;
import org.eclipse.lsp4j.debug.StepOutArguments;
import org.eclipse.lsp4j.debug.StackFrame;
import org.eclipse.lsp4j.debug.ScopesArguments;
import org.eclipse.lsp4j.debug.ScopesResponse;
import org.eclipse.lsp4j.debug.Scope;
import org.eclipse.lsp4j.debug.VariablesArguments;
import org.eclipse.lsp4j.debug.VariablesResponse;
import org.eclipse.lsp4j.debug.Variable;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.ThreadsResponse;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
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
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugFrame;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugCapabilities;
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
    private final ConcurrentHashMap<String, List<DebugCapability.SourceBreakpoint>> sourceBreakpoints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> sourceBreakpointLocks = new ConcurrentHashMap<>();
    private final Set<String> knownBreakpointSources = ConcurrentHashMap.newKeySet();
    private final Object breakpointSetsLock = new Object();
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

    @Override
    public CapabilityResult<List<DebugCapability.SourceBreakpoint>> setBreakpoints(String sourcePath,
            List<DebugCapability.SourceBreakpoint> breakpoints) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return CapabilityResult.failed(new CapabilityFailure("A breakpoint source path is required", ""));
        }
        if (breakpoints != null && breakpoints.stream().anyMatch(point -> !sourcePath.equals(point.sourcePath()))) {
            return CapabilityResult.failed(new CapabilityFailure("Breakpoint source path does not match the request", sourcePath));
        }
        List<DebugCapability.SourceBreakpoint> requested = breakpoints == null ? List.of() : breakpoints.stream()
                .sorted(Comparator.comparingInt(DebugCapability.SourceBreakpoint::line)).toList();
        synchronized (breakpointSetsLock) {
            Object sourceLock = sourceBreakpointLocks.computeIfAbsent(sourcePath, ignored -> new Object());
            synchronized (sourceLock) {
                if (requested.isEmpty()) { sourceBreakpoints.remove(sourcePath); }
                else { sourceBreakpoints.put(sourcePath, requested); }
                knownBreakpointSources.add(sourcePath);
                return applyBreakpointsToLiveSessions(sourcePath, requested);
            }
        }
    }

    @Override
    public CapabilityResult<Map<String, List<DebugCapability.SourceBreakpoint>>> replaceBreakpoints(
            Map<String, List<DebugCapability.SourceBreakpoint>> completeSources) {
        Map<String, List<DebugCapability.SourceBreakpoint>> requestedSources = completeSources == null
                ? Map.of() : Map.copyOf(completeSources);
        synchronized (breakpointSetsLock) {
            List<String> paths = new ArrayList<>(knownBreakpointSources);
            requestedSources.keySet().forEach(path -> {
                if (path == null || path.isBlank()) {
                    throw new IllegalArgumentException("Breakpoint source path is required");
                }
                if (!paths.contains(path)) {
                    paths.add(path);
                }
            });
            paths.sort(String::compareTo);
            Map<String, List<DebugCapability.SourceBreakpoint>> result = new TreeMap<>();
            for (String path : paths) {
                List<DebugCapability.SourceBreakpoint> points = requestedSources.getOrDefault(path, List.of());
                CapabilityResult<List<DebugCapability.SourceBreakpoint>> synchronizedPoints = setBreakpoints(path, points);
                if (synchronizedPoints instanceof CapabilityResult.Failed<List<DebugCapability.SourceBreakpoint>> failed) {
                    return CapabilityResult.failed(failed.failure());
                }
                result.put(path, ((CapabilityResult.Ok<List<DebugCapability.SourceBreakpoint>>) synchronizedPoints).value());
            }
            return CapabilityResult.ok(Map.copyOf(result));
        }
    }

    private CapabilityResult<List<DebugCapability.SourceBreakpoint>> applyBreakpointsToLiveSessions(String sourcePath,
            List<DebugCapability.SourceBreakpoint> requested) {
        Map<Integer, List<DebugCapability.SourceBreakpoint>> observations = new TreeMap<>();
        for (DapSession session : sessions.values().stream().filter(value -> !value.isTerminal())
                .sorted(Comparator.comparing(value -> value.id)).toList()) {
            CapabilityResult<List<DebugCapability.SourceBreakpoint>> applied = session.setBreakpoints(sourcePath, requested);
            List<DebugCapability.SourceBreakpoint> sessionPoints;
            if (applied instanceof CapabilityResult.Ok<List<DebugCapability.SourceBreakpoint>> ok) {
                sessionPoints = ok.value();
            } else {
                String reason = ((CapabilityResult.Failed<List<DebugCapability.SourceBreakpoint>>) applied)
                        .failure().reason();
                sessionPoints = requested.stream().map(point -> new DebugCapability.SourceBreakpoint(sourcePath,
                        point.line(), DebugCapability.BreakpointState.REJECTED, reason)).toList();
            }
            Map<Integer, DebugCapability.SourceBreakpoint> byLine = new HashMap<>();
            sessionPoints.forEach(point -> byLine.put(point.line(), point));
            for (DebugCapability.SourceBreakpoint point : requested) {
                observations.computeIfAbsent(point.line(), ignored -> new ArrayList<>()).add(byLine.getOrDefault(
                        point.line(), new DebugCapability.SourceBreakpoint(sourcePath, point.line())));
            }
        }
        List<DebugCapability.SourceBreakpoint> result = requested.stream().map(point -> {
            List<DebugCapability.SourceBreakpoint> reports = observations.getOrDefault(point.line(), List.of());
            DebugCapability.BreakpointState state = reports.stream()
                    .anyMatch(report -> report.state() == DebugCapability.BreakpointState.REJECTED)
                            ? DebugCapability.BreakpointState.REJECTED
                            : reports.isEmpty() || reports.stream()
                                    .anyMatch(report -> report.state() == DebugCapability.BreakpointState.PENDING)
                                            ? DebugCapability.BreakpointState.PENDING
                                            : DebugCapability.BreakpointState.VERIFIED;
            String message = reports.stream().map(DebugCapability.SourceBreakpoint::message).filter(value -> !value.isBlank())
                    .distinct().sorted().collect(Collectors.joining("; "));
            List<Integer> resolvedLines = reports.stream().map(DebugCapability.SourceBreakpoint::resolvedLine)
                    .filter(Objects::nonNull).distinct().toList();
            boolean everyReportResolved = !reports.isEmpty()
                    && reports.stream().allMatch(report -> report.resolvedLine() != null);
            List<String> resolvedSources = reports.stream().map(DebugCapability.SourceBreakpoint::resolvedSourcePath)
                    .distinct().toList();
            String resolvedSource = resolvedSources.size() == 1 ? resolvedSources.getFirst() : sourcePath;
            Integer resolvedLine = everyReportResolved && resolvedLines.size() == 1 && resolvedSources.size() == 1
                    ? resolvedLines.getFirst() : null;
            return new DebugCapability.SourceBreakpoint(sourcePath, point.line(), state, message, resolvedLine, resolvedSource);
        }).toList();
        return CapabilityResult.ok(result);
    }

    private void replayBreakpoints(DapSession session) {
        for (String sourcePath : knownBreakpointSources.stream().sorted().toList()) {
            Object sourceLock = sourceBreakpointLocks.computeIfAbsent(sourcePath, ignored -> new Object());
            synchronized (sourceLock) {
                List<DebugCapability.SourceBreakpoint> requested = sourceBreakpoints.getOrDefault(sourcePath, List.of());
                session.setBreakpoints(sourcePath, requested);
            }
        }
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

    static <T> T awaitInspection(CompletableFuture<T> response, Duration timeout) throws Exception {
        try {
            return response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            response.cancel(true);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (TimeoutException timeoutFailure) {
            response.cancel(true);
            throw timeoutFailure;
        }
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
        private volatile DebugCapabilities debugCapabilities = new DebugCapabilities(false, false, false, false, false);
        private final AtomicBoolean executionRequestPending = new AtomicBoolean();
        private long stopGeneration;
        private PendingExecution pendingExecution;

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
            debugCapabilities = new DebugCapabilities(true, false, true, true, true, false);
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
            replayBreakpoints(this);
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
            int threadId = event == null || event.getThreadId() == null ? 0 : event.getThreadId();
            long generation;
            synchronized (this) {
                if (terminal) {
                    return;
                }
                generation = ++stopGeneration;
                PendingExecution pending = pendingExecution;
                boolean matching = matchesPending(pending, State.STOPPED, threadId);
                State stoppedState = pending != null && !matching ? State.EXECUTING : State.STOPPED;
                String stateReason = stoppedState == State.EXECUTING
                        ? "Waiting for the requested debugger event" : "";
                update(snapshot -> new DebugSessionSnapshot(id, label, stoppedState, snapshot.threads(), stateReason, reason,
                        threadId, null, debugCapabilities));
                if (matching) completePendingExecution(State.STOPPED, threadId);
            }
            if (threadId > 0) loadTopFrame(threadId, generation);
        }

        private void eventContinued(ContinuedEventArguments event) {
            if (!isTerminal()) {
                synchronized (this) {
                    ++stopGeneration;
                    PendingExecution pending = pendingExecution;
                    State runningState = pending != null && pending.state() == State.STOPPED
                            ? State.EXECUTING : State.RUNNING;
                    String reason = runningState == State.EXECUTING
                            ? "Waiting for the requested debugger event" : "";
                    update(snapshot -> new DebugSessionSnapshot(id, label, runningState, snapshot.threads(), reason, "",
                            0, null, debugCapabilities));
                    completePendingExecution(State.RUNNING, event == null
                            ? 0 : event.getThreadId());
                }
            }
        }

        private void completePendingExecution(State state, int threadId) {
            PendingExecution pending = pendingExecution;
            if (matchesPending(pending, state, threadId)) {
                pending.transition().complete(null);
                if (pendingExecution == pending) pendingExecution = null;
                executionRequestPending.set(false);
            }
        }

        private boolean matchesPending(PendingExecution pending, State eventState, int threadId) {
            if (pending == null || stopGeneration <= pending.baselineGeneration()) return false;
            boolean stateMatches = pending.state() == eventState
                    || pending.state() == State.RUNNING && eventState == State.STOPPED;
            return stateMatches && (pending.threadId() == 0 || threadId == 0 || pending.threadId() == threadId);
        }

        private void loadTopFrame(int threadId, long generation) {
            IDebugProtocolServer protocol = server;
            if (protocol == null || isTerminal()) return;
            StackTraceArguments arguments = new StackTraceArguments();
            arguments.setThreadId(threadId);
            protocol.stackTrace(arguments).orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .whenComplete((response, failure) -> {
                        if (failure != null || response == null || response.getStackFrames() == null
                                || response.getStackFrames().length == 0 || isTerminal()) return;
                        StackFrame top = response.getStackFrames()[0];
                        Source source = top.getSource();
                        if (source == null || source.getPath() == null || top.getLine() < 1) return;
                        DebugFrame frame = new DebugFrame(top.getName(), source.getPath(), top.getLine(),
                                top.getColumn() < 1 ? 1 : top.getColumn());
                        synchronized (this) {
                            if (!terminal && stopGeneration == generation && current.get().state() == State.STOPPED
                                    && current.get().stoppedThreadId() == threadId) {
                                update(snapshot -> new DebugSessionSnapshot(id, label, snapshot.state(), snapshot.threads(),
                                        snapshot.reason(), snapshot.stoppedReason(), threadId, frame, debugCapabilities));
                            }
                        }
                    });
        }

        private CapabilityResult<Boolean> requestExecution(SteppingAction action) {
            IDebugProtocolServer protocol = server;
            DebugSessionSnapshot before = snapshot();
            if (protocol == null || isTerminal()) return CapabilityResult.failed(
                    new CapabilityFailure("The debug session is not active", ""));
            boolean pauseProbe = action == SteppingAction.PAUSE && !debugCapabilities.known(action);
            if (!debugCapabilities.supports(action) && !pauseProbe) return CapabilityResult.failed(
                    new CapabilityFailure(actionLabel(action) + " is not supported by this adapter", ""));
            if (action != SteppingAction.PAUSE && before.state() != State.STOPPED)
                return CapabilityResult.failed(new CapabilityFailure(actionLabel(action) + " requires a stopped session", ""));
            if (action == SteppingAction.PAUSE && before.state() != State.RUNNING)
                return CapabilityResult.failed(new CapabilityFailure("Pause requires a running session", ""));
            if (!executionRequestPending.compareAndSet(false, true)) return CapabilityResult.failed(
                    new CapabilityFailure("Another debugger action is still waiting for its DAP event", ""));
            State expected = action == SteppingAction.RESUME ? State.RUNNING : State.STOPPED;
            int threadId = action == SteppingAction.PAUSE
                    ? (before.threads().isEmpty() ? 0 : before.threads().getFirst().id()) : stoppedThreadId(before);
            CompletableFuture<Void> transition = new CompletableFuture<>();
            PendingExecution pending;
            synchronized (this) {
                pending = new PendingExecution(expected, threadId, stopGeneration, transition);
                pendingExecution = pending;
            }
            return executeAndAwait(protocol, action, before, pauseProbe, pending);
        }

        private CapabilityResult<Boolean> executeAndAwait(IDebugProtocolServer protocol, SteppingAction action,
                DebugSessionSnapshot before, boolean pauseProbe, PendingExecution pending) {
            boolean acknowledged = false;
            try {
                synchronized (this) {
                    update(snapshot -> new DebugSessionSnapshot(id, label, State.EXECUTING, snapshot.threads(),
                            "Waiting for the debugger execution event", snapshot.stoppedReason(),
                            snapshot.stoppedThreadId(), snapshot.currentFrame(), debugCapabilities));
                }
                switch (action) {
                    case RESUME -> {
                        ContinueArguments arguments = new ContinueArguments();
                        arguments.setThreadId(stoppedThreadId(before));
                        protocol.continue_(arguments).get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    }
                    case PAUSE -> {
                        PauseArguments arguments = new PauseArguments();
                        List<DebugThread> availableThreads = before.threads();
                        if (availableThreads.isEmpty()) {
                            ThreadsResponse response = protocol.threads().get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
                            if (response != null && response.getThreads() != null) {
                                availableThreads = Arrays.stream(response.getThreads()).filter(Objects::nonNull)
                                        .map(thread -> new DebugThread(thread.getId(), thread.getName())).toList();
                            }
                        }
                        arguments.setThreadId(availableThreads.isEmpty() ? 1 : availableThreads.getFirst().id());
                        protocol.pause(arguments).get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    }
                    case STEP_OVER -> {
                        NextArguments arguments = new NextArguments();
                        arguments.setThreadId(stoppedThreadId(before));
                        protocol.next(arguments).get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    }
                    case STEP_INTO -> {
                        StepInArguments arguments = new StepInArguments();
                        arguments.setThreadId(stoppedThreadId(before));
                        protocol.stepIn(arguments).get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    }
                    case STEP_OUT -> {
                        StepOutArguments arguments = new StepOutArguments();
                        arguments.setThreadId(stoppedThreadId(before));
                        protocol.stepOut(arguments).get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    }
                }
                acknowledged = true;
                pending.transition().get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (action == SteppingAction.PAUSE && pauseProbe) {
                    updatePauseCapability(true);
                }
                return CapabilityResult.ok(Boolean.TRUE);
            } catch (Exception failure) {
                if (action == SteppingAction.PAUSE && pauseProbe && isExplicitUnsupportedRequest(failure)) {
                    updatePauseCapability(false);
                }
                if (!acknowledged && !pending.transition().isDone() && !hasEventAfter(pending)) {
                    restoreExecutionState(before, pending);
                }
                return CapabilityResult.failed(new CapabilityFailure(failureReason(failure), ""));
            } finally {
                if (pending.transition().isDone() || !acknowledged && !hasEventAfter(pending)) {
                    releasePendingExecution(pending);
                }
            }
        }

        private boolean hasEventAfter(PendingExecution pending) {
            synchronized (this) {
                return stopGeneration > pending.baselineGeneration();
            }
        }

        private void restoreExecutionState(DebugSessionSnapshot before, PendingExecution pending) {
            synchronized (this) {
                if (pendingExecution != pending) return;
                update(snapshot -> snapshot.state() == State.EXECUTING
                        ? new DebugSessionSnapshot(id, label, before.state(), snapshot.threads(), before.reason(),
                                before.stoppedReason(), before.stoppedThreadId(), before.currentFrame(), debugCapabilities)
                        : snapshot);
            }
        }

        private void releasePendingExecution(PendingExecution pending) {
            synchronized (this) {
                if (pendingExecution == pending) pendingExecution = null;
                if (pendingExecution == null) executionRequestPending.set(false);
            }
        }

        private void updatePauseCapability(boolean supported) {
            synchronized (this) {
                debugCapabilities = new DebugCapabilities(debugCapabilities.resume(), supported,
                        debugCapabilities.stepOver(), debugCapabilities.stepInto(), debugCapabilities.stepOut(), true);
                update(snapshot -> new DebugSessionSnapshot(id, label, snapshot.state(), snapshot.threads(),
                        snapshot.reason(), snapshot.stoppedReason(), snapshot.stoppedThreadId(),
                        snapshot.currentFrame(), debugCapabilities));
            }
        }

        private boolean isExplicitUnsupportedRequest(Exception failure) {
            Throwable cause = failure;
            while (cause != null) {
                if (cause instanceof ResponseErrorException responseFailure) {
                    ResponseError error = responseFailure.getResponseError();
                    if (error != null && (error.getCode() == ResponseErrorCode.MethodNotFound.getValue()
                            || explicitlyUnsupported(error.getMessage()))) {
                        return true;
                    }
                }
                if (explicitlyUnsupported(cause.getMessage())) return true;
                cause = cause.getCause();
            }
            return false;
        }

        private boolean explicitlyUnsupported(String message) {
            if (message == null) return false;
            String reason = message.toLowerCase(Locale.ROOT);
            return reason.contains("not supported") || reason.contains("unsupported")
                    || reason.contains("method not found") || reason.contains("unknown command");
        }

        private record PendingExecution(State state, int threadId, long baselineGeneration,
                CompletableFuture<Void> transition) { }

        private int stoppedThreadId(DebugSessionSnapshot value) {
            return value.stoppedThreadId() > 0 ? value.stoppedThreadId()
                    : value.threads().isEmpty() ? 1 : value.threads().getFirst().id();
        }

        private String actionLabel(SteppingAction action) {
            return switch (action) {
                case RESUME -> "Resume";
                case PAUSE -> "Pause";
                case STEP_OVER -> "Step over";
                case STEP_INTO -> "Step into";
                case STEP_OUT -> "Step out";
            };
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
                ++stopGeneration;
                if (pendingExecution != null) pendingExecution.transition().completeExceptionally(
                        new IOException("The debug session ended before the requested execution event"));
                pendingExecution = null;
                executionRequestPending.set(false);
                update(snapshot -> new DebugSessionSnapshot(id, label, state, snapshot.threads(), reason, stoppedReason,
                        0, null, debugCapabilities));
            }
        }

        private boolean isTerminal() {
            return terminal;
        }

        private void transitionToRunningUnlessStopped() {
            synchronized (this) {
                if (!terminal && current.get().state() != State.STOPPED) {
                    update(snapshot -> new DebugSessionSnapshot(id, label, State.RUNNING,
                            snapshot.threads(), "", "", 0, null, debugCapabilities));
                }
            }
        }

        private void state(State state, String reason) {
            synchronized (this) {
                if (terminal) {
                    return;
                }
                update(snapshot -> new DebugSessionSnapshot(id, label, state, snapshot.threads(), reason, "",
                        0, null, debugCapabilities));
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
        public synchronized long inspectionGeneration() {
            return stopGeneration;
        }

        @Override
        public CapabilityResult<List<DebugThread>> threads() {
            IDebugProtocolServer protocol = server;
            if (protocol == null || isTerminal()) {
                return CapabilityResult.failed(new CapabilityFailure("The debug session is not active", ""));
            }
            try {
                ThreadsResponse response = awaitInspection(protocol.threads(), requestTimeout);
                List<DebugThread> threads = response == null || response.getThreads() == null
                        ? List.of()
                        : Arrays.stream(response.getThreads())
                                .filter(Objects::nonNull)
                                .map(thread -> new DebugThread(thread.getId(), thread.getName()))
                                .toList();
                List<DebugThread> copy = List.copyOf(threads);
                update(snapshot -> new DebugSessionSnapshot(id, label, snapshot.state(), copy,
                        snapshot.reason(), snapshot.stoppedReason(), snapshot.stoppedThreadId(),
                        snapshot.currentFrame(), debugCapabilities));
                return CapabilityResult.ok(copy);
            } catch (Exception failure) {
                return CapabilityResult.failed(new CapabilityFailure(failureReason(failure), ""));
            }
        }

        @Override
        public CapabilityResult<List<DebugFrame>> stackTrace(int threadId) {
            IDebugProtocolServer protocol = server;
            if (protocol == null || isTerminal() || snapshot().state() != State.STOPPED) return inspectionUnavailable();
            try {
                StackTraceArguments args = new StackTraceArguments();
                args.setThreadId(threadId);
                args.setStartFrame(0);
                args.setLevels(200);
                StackTraceResponse response = awaitInspection(protocol.stackTrace(args), requestTimeout);
                List<DebugFrame> result = response == null || response.getStackFrames() == null ? List.of()
                        : Arrays.stream(response.getStackFrames()).filter(Objects::nonNull)
                                .filter(frame -> frame.getLine() > 0)
                                .limit(200)
                                .map(frame -> new DebugFrame(frame.getName(),
                                        frame.getSource() == null ? "" : frame.getSource().getPath(), frame.getLine(),
                                        Math.max(1, frame.getColumn()), frame.getId()))
                                .toList();
                return CapabilityResult.ok(result);
            } catch (Exception failure) { return CapabilityResult.failed(new CapabilityFailure(failureReason(failure), "")); }
        }

        @Override
        public CapabilityResult<List<DebugScope>> scopes(int frameId) {
            IDebugProtocolServer protocol = server;
            if (protocol == null || isTerminal() || snapshot().state() != State.STOPPED) return inspectionUnavailable();
            try {
                ScopesArguments args = new ScopesArguments();
                args.setFrameId(frameId);
                ScopesResponse response = awaitInspection(protocol.scopes(args), requestTimeout);
                List<DebugScope> result = response == null || response.getScopes() == null ? List.of()
                        : Arrays.stream(response.getScopes()).filter(Objects::nonNull).limit(64)
                                .map(scope -> new DebugScope(scope.getName(), scope.getVariablesReference(),
                                        Boolean.TRUE.equals(scope.isExpensive()))).toList();
                return CapabilityResult.ok(result);
            } catch (Exception failure) { return CapabilityResult.failed(new CapabilityFailure(failureReason(failure), "")); }
        }

        @Override
        public CapabilityResult<List<DebugVariable>> variables(int variablesReference) {
            IDebugProtocolServer protocol = server;
            if (protocol == null || isTerminal() || snapshot().state() != State.STOPPED || variablesReference <= 0)
                return inspectionUnavailable();
            try {
                VariablesArguments args = new VariablesArguments();
                args.setVariablesReference(variablesReference);
                VariablesResponse response = awaitInspection(protocol.variables(args), requestTimeout);
                List<DebugVariable> result = response == null || response.getVariables() == null ? List.of()
                        : Arrays.stream(response.getVariables()).filter(Objects::nonNull).limit(200)
                                .map(variable -> new DebugVariable(variable.getName(), variable.getValue(), variable.getType(),
                                        variable.getVariablesReference(), variable.getVariablesReference() > 0)).toList();
                return CapabilityResult.ok(result);
            } catch (Exception failure) { return CapabilityResult.failed(new CapabilityFailure(failureReason(failure), "")); }
        }

        @Override
        public CapabilityResult<Boolean> selectFrame(DebugFrame frame) {
            return selectFrame(frame, inspectionGeneration());
        }

        @Override
        public synchronized CapabilityResult<Boolean> selectFrame(DebugFrame frame, long expectedInspectionGeneration) {
            if (stopGeneration != expectedInspectionGeneration) {
                return CapabilityResult.failed(new CapabilityFailure("The debug stop changed before frame selection", ""));
            }
            if (frame == null || isTerminal() || snapshot().state() != State.STOPPED)
                return CapabilityResult.failed(new CapabilityFailure("The debug session is not stopped", ""));
            update(value -> new DebugSessionSnapshot(id, label, value.state(), value.threads(), value.reason(),
                    value.stoppedReason(), value.stoppedThreadId(), frame, debugCapabilities));
            return CapabilityResult.ok(true);
        }

        private <T> CapabilityResult<List<T>> inspectionUnavailable() {
            return CapabilityResult.failed(new CapabilityFailure("The debug session is not stopped", ""));
        }

        @Override public CapabilityResult<Boolean> resume() { return requestExecution(SteppingAction.RESUME); }
        @Override public CapabilityResult<Boolean> pause() { return requestExecution(SteppingAction.PAUSE); }
        @Override public CapabilityResult<Boolean> stepOver() { return requestExecution(SteppingAction.STEP_OVER); }
        @Override public CapabilityResult<Boolean> stepInto() { return requestExecution(SteppingAction.STEP_INTO); }
        @Override public CapabilityResult<Boolean> stepOut() { return requestExecution(SteppingAction.STEP_OUT); }

        private CapabilityResult<List<DebugCapability.SourceBreakpoint>> setBreakpoints(String sourcePath,
                List<DebugCapability.SourceBreakpoint> requested) {
            IDebugProtocolServer protocol = server;
            if (protocol == null || isTerminal()) {
                return CapabilityResult.ok(requested.stream().map(point -> new DebugCapability.SourceBreakpoint(
                        sourcePath, point.line())).toList());
            }
            Source source = new Source();
            source.setPath(sourcePath);
            SetBreakpointsArguments arguments = new SetBreakpointsArguments();
            arguments.setSource(source);
            arguments.setSourceModified(false);
            arguments.setBreakpoints(DapSourceBreakpoints.fromApi(requested));
            try {
                SetBreakpointsResponse response = protocol.setBreakpoints(arguments)
                        .get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
                Breakpoint[] returnedArray = response == null ? null : response.getBreakpoints();
                List<Breakpoint> returned = returnedArray == null ? List.of() : Arrays.asList(returnedArray);
                List<DebugCapability.SourceBreakpoint> applied = new ArrayList<>();
                for (int index = 0; index < requested.size(); index++) {
                    DebugCapability.SourceBreakpoint point = requested.get(index);
                    Breakpoint actual = index < returned.size() ? returned.get(index) : null;
                    DebugCapability.BreakpointState state = actual == null ? DebugCapability.BreakpointState.PENDING
                            : Boolean.TRUE.equals(actual.isVerified()) ? DebugCapability.BreakpointState.VERIFIED
                                    : DebugCapability.BreakpointState.REJECTED;
                    String message = actual == null || actual.getMessage() == null ? "" : actual.getMessage();
                    Source resolvedSource = actual == null ? null : actual.getSource();
                    String resolvedPath = resolvedSource == null || resolvedSource.getPath() == null
                            ? sourcePath : resolvedSource.getPath();
                    Integer resolvedLine = actual == null ? null : actual.getLine();
                    applied.add(new DebugCapability.SourceBreakpoint(sourcePath, point.line(), state, message,
                            resolvedLine, resolvedPath));
                }
                return CapabilityResult.ok(List.copyOf(applied));
            } catch (Exception failure) {
                String reason = failureReason(failure);
                return CapabilityResult.ok(requested.stream().map(point -> new DebugCapability.SourceBreakpoint(
                        sourcePath, point.line(), DebugCapability.BreakpointState.REJECTED, reason)).toList());
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
                eventContinued(event);
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
