package dev.omatheusmesmo.vintelli.debug;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.TerminatedEventArguments;
import org.eclipse.lsp4j.debug.Thread;
import org.eclipse.lsp4j.debug.ThreadsResponse;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.Breakpoint;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;

final class DapTwin implements AutoCloseable {

    private final Mode mode;
    private final ServerSocket listener;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final AtomicInteger connectionSequence = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch firstConnection = new CountDownLatch(1);
    private volatile List<Integer> breakpointLines = List.of();

    DapTwin(Mode mode) throws IOException {
        this.mode = mode;
        listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        executor.submit(this::accept);
    }

    int port() {
        return listener.getLocalPort();
    }

    void drop() {
        connections.forEach(Connection::close);
    }

    List<Integer> breakpointLines() {
        return breakpointLines;
    }

    Connection connection() {
        try {
            if (!firstConnection.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("DAP client did not connect");
            }
        } catch (InterruptedException interrupted) {
            throw new AssertionError("Interrupted waiting for DAP client connection", interrupted);
        }
        return connections.getFirst();
    }

    private void accept() {
        while (!listener.isClosed()) {
            try {
                Socket socket = listener.accept();
                Connection connection = new Connection(socket, connectionSequence.incrementAndGet());
                connections.add(connection);
                firstConnection.countDown();
                executor.submit(connection::serve);
            } catch (IOException failure) {
                if (!listener.isClosed()) {
                    throw new IllegalStateException(failure);
                }
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            listener.close();
        } catch (IOException ignored) {
        }
        connections.forEach(Connection::close);
        executor.shutdownNow();
    }

    enum Mode {
        NORMAL,
        WAIT_FOR_CONFIGURATION_DONE,
        TERMINATE_DURING_ATTACH,
        FAIL_ONCE_THEN_INITIALIZED,
        BLOCK_DISCONNECT,
        NEVER_ATTACH,
        UNSUPPORTED,
        MALFORMED,
        REJECT_BREAKPOINT,
        REJECT_SECOND_SESSION,
        RESOLVE_BREAKPOINT
    }

    final class Connection {

        private final Socket socket;
        private final int connectionNumber;
        private final TwinServer server = new TwinServer();
        private final AtomicInteger attachCalls = new AtomicInteger();
        private volatile CompletableFuture<Void> pendingAttach;
        private volatile IDebugProtocolClient client;
        private volatile boolean configurationDone;
        private volatile boolean initializedSent;
        private volatile boolean terminateDebuggee;
        private final CountDownLatch disconnectReceived = new CountDownLatch(1);
        private volatile String hostName;
        private volatile Integer targetPort;

        private Connection(Socket socket, int connectionNumber) {
            this.socket = socket;
            this.connectionNumber = connectionNumber;
        }

        private void serve() {
            if (mode == Mode.MALFORMED) {
                try {
                    OutputStream output = socket.getOutputStream();
                    output.write("Content-Length: 7\r\n\r\ninvalid".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                } catch (IOException ignored) {
                } finally {
                    close();
                }
                return;
            }
            try {
                var launcher = DSPLauncher.createServerLauncher(server, socket.getInputStream(), socket.getOutputStream(),
                        executor, endpoint -> endpoint);
                client = launcher.getRemoteProxy();
                launcher.startListening().get();
            } catch (Exception ignored) {
            } finally {
                close();
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        boolean configurationDone() {
            return configurationDone;
        }

        boolean initializedSent() {
            return initializedSent;
        }

        String hostName() {
            return hostName;
        }

        Integer targetPort() {
            return targetPort;
        }

        int attachCalls() {
            return attachCalls.get();
        }

        boolean terminateDebuggee() {
            return terminateDebuggee;
        }

        boolean awaitDisconnect(long timeout, TimeUnit unit) throws InterruptedException {
            return disconnectReceived.await(timeout, unit);
        }

        void outputBurst(int count) {
            IDebugProtocolClient remote = client;
            for (int index = 0; index < count; index++) {
                OutputEventArguments event = new OutputEventArguments();
                event.setOutput("line " + index);
                remote.output(event);
            }
        }

        private final class TwinServer implements IDebugProtocolServer {

            @Override
            public CompletableFuture<Capabilities> initialize(InitializeRequestArguments arguments) {
                Capabilities capabilities = new Capabilities();
                capabilities.setSupportsConfigurationDoneRequest(mode != Mode.UNSUPPORTED);
                return CompletableFuture.completedFuture(capabilities);
            }

            @Override
            public CompletableFuture<Void> attach(Map<String, Object> arguments) {
                int attempt = attachCalls.incrementAndGet();
                hostName = String.valueOf(arguments.get("hostName"));
                Object port = arguments.get("port");
                targetPort = port instanceof Number number ? number.intValue() : null;
                if (mode == Mode.FAIL_ONCE_THEN_INITIALIZED && attempt == 1) {
                    return CompletableFuture.failedFuture(new IOException("JDWP listener is not ready"));
                }
                if (mode == Mode.NEVER_ATTACH) {
                    pendingAttach = new CompletableFuture<>();
                    return pendingAttach;
                }
                if (mode == Mode.WAIT_FOR_CONFIGURATION_DONE || mode == Mode.TERMINATE_DURING_ATTACH) {
                    pendingAttach = new CompletableFuture<>();
                    if (mode == Mode.TERMINATE_DURING_ATTACH) {
                        TerminatedEventArguments terminated = new TerminatedEventArguments();
                        client.terminated(terminated);
                    }
                    sendInitialized();
                    return pendingAttach;
                }
                sendInitialized();
                stopAtBreakpoint();
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> configurationDone(ConfigurationDoneArguments arguments) {
                if (!initializedSent) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("configurationDone arrived before initialized"));
                }
                configurationDone = true;
                CompletableFuture<Void> pending = pendingAttach;
                if (pending != null && (mode == Mode.WAIT_FOR_CONFIGURATION_DONE
                        || mode == Mode.TERMINATE_DURING_ATTACH)) {
                    stopAtBreakpoint();
                    pending.complete(null);
                }
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<ThreadsResponse> threads() {
                Thread thread = new Thread();
                thread.setId(1);
                thread.setName("main");
                ThreadsResponse response = new ThreadsResponse();
                response.setThreads(new Thread[] { thread });
                return CompletableFuture.completedFuture(response);
            }

            @Override
            public CompletableFuture<SetBreakpointsResponse> setBreakpoints(SetBreakpointsArguments arguments) {
                List<SourceBreakpoint> requested = arguments.getBreakpoints() == null ? List.of()
                        : List.of(arguments.getBreakpoints());
                breakpointLines = requested.stream().map(SourceBreakpoint::getLine).toList();
                SetBreakpointsResponse response = new SetBreakpointsResponse();
                response.setBreakpoints(requested.stream().map(sourceBreakpoint -> {
                    Breakpoint breakpoint = new Breakpoint();
                    breakpoint.setLine(sourceBreakpoint.getLine());
                    boolean verified = (mode != Mode.REJECT_BREAKPOINT || sourceBreakpoint.getLine() != 4)
                            && (mode != Mode.REJECT_SECOND_SESSION || connectionNumber != 2);
                    breakpoint.setVerified(verified);
                    if (mode == Mode.RESOLVE_BREAKPOINT) {
                        breakpoint.setLine(sourceBreakpoint.getLine() + 1);
                        Source resolved = new Source();
                        resolved.setPath("/mapped/Main.java");
                        breakpoint.setSource(resolved);
                    }
                    if (!verified) {
                        breakpoint.setMessage("Source line is not executable");
                    }
                    return breakpoint;
                }).toArray(Breakpoint[]::new));
                return CompletableFuture.completedFuture(response);
            }

            @Override
            public CompletableFuture<Void> disconnect(DisconnectArguments arguments) {
                terminateDebuggee = Boolean.TRUE.equals(arguments.getTerminateDebuggee());
                disconnectReceived.countDown();
                if (mode == Mode.BLOCK_DISCONNECT) {
                    return new CompletableFuture<>();
                }
                return CompletableFuture.completedFuture(null);
            }

            private void stopAtBreakpoint() {
                IDebugProtocolClient remote = client;
                if (remote != null) {
                    StoppedEventArguments stopped = new StoppedEventArguments();
                    stopped.setReason("breakpoint");
                    stopped.setThreadId(1);
                    remote.stopped(stopped);
                }
            }

            private void sendInitialized() {
                initializedSent = true;
                client.initialized();
            }
        }
    }
}
