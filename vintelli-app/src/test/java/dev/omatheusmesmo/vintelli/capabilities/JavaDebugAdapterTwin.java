package dev.omatheusmesmo.vintelli.capabilities;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.Thread;
import org.eclipse.lsp4j.debug.ThreadsResponse;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.Breakpoint;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;

/** Test-only DAP twin that maps attach and thread requests to a real local JDWP debuggee with JDI. */
final class JavaDebugAdapterTwin implements AutoCloseable {

    enum Mode {
        HEALTHY,
        CLOSED_PORT,
        TIMEOUT,
        MALFORMED,
        DROP
    }

    private final int port;
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService clientExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Map<Long, Integer> threadIds = new ConcurrentHashMap<>();
    private final AtomicInteger nextThreadId = new AtomicInteger(1);
    private final AtomicReference<List<Integer>> breakpointLines = new AtomicReference<>(List.of());
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.HEALTHY);
    private volatile ServerSocket listener;
    private volatile VirtualMachine virtualMachine;
    private volatile int lastTargetPort;
    private volatile boolean closed;

    JavaDebugAdapterTwin() throws IOException {
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = reservation.getLocalPort();
        }
        openListener();
    }

    int port() {
        return port;
    }

    int lastTargetPort() {
        return lastTargetPort;
    }

    List<Integer> breakpointLines() {
        return breakpointLines.get();
    }

    void mode(Mode requested) {
        mode.set(requested);
        if (requested == Mode.CLOSED_PORT) {
            closeListener();
        } else if (listener == null && !closed) {
            try {
                openListener();
            } catch (IOException failure) {
                throw new IllegalStateException("Could not reopen the DAP twin listener", failure);
            }
        }
    }

    private synchronized void openListener() throws IOException {
        if (closed || listener != null) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        listener = socket;
        acceptExecutor.submit(() -> accept(socket));
    }

    private void accept(ServerSocket socket) {
        while (!closed && listener == socket) {
            try {
                Socket connection = socket.accept();
                connections.add(connection);
                clientExecutor.submit(() -> serve(connection, mode.get()));
            } catch (IOException failure) {
                if (!closed && listener == socket) {
                    mode.compareAndSet(Mode.HEALTHY, Mode.DROP);
                }
                return;
            }
        }
    }

    private void serve(Socket connection, Mode selectedMode) {
        ExecutorService protocolExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try (connection) {
            if (selectedMode == Mode.DROP) {
                return;
            }
            if (selectedMode == Mode.MALFORMED) {
                connection.getOutputStream().write("Content-Length: invalid\r\n\r\n{}"
                        .getBytes(StandardCharsets.US_ASCII));
                connection.getOutputStream().flush();
                return;
            }
            DapServer server = new DapServer(selectedMode);
            Launcher<IDebugProtocolClient> launcher = DSPLauncher.createServerLauncher(server,
                    connection.getInputStream(), connection.getOutputStream(), protocolExecutor, consumer -> consumer);
            server.client = launcher.getRemoteProxy();
            launcher.startListening().get();
        } catch (Exception ignored) {
        } finally {
            protocolExecutor.shutdownNow();
            connections.remove(connection);
        }
    }

    private synchronized void closeListener() {
        ServerSocket current = listener;
        listener = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        closeListener();
        connections.forEach(connection -> {
            try {
                connection.close();
            } catch (IOException ignored) {
            }
        });
        acceptExecutor.shutdownNow();
        clientExecutor.shutdownNow();
        disposeTarget();
    }

    private void disposeTarget() {
        VirtualMachine current = virtualMachine;
        if (current != null) {
            try {
                current.dispose();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private final class DapServer implements IDebugProtocolServer {

        private final Mode selectedMode;
        private final CompletableFuture<Void> configured = new CompletableFuture<>();
        private final AtomicInteger initialized = new AtomicInteger();
        private volatile IDebugProtocolClient client;

        private DapServer(Mode selectedMode) {
            this.selectedMode = selectedMode;
        }

        @Override
        public CompletableFuture<Capabilities> initialize(InitializeRequestArguments arguments) {
            if (selectedMode == Mode.TIMEOUT) {
                return new CompletableFuture<>();
            }
            Capabilities capabilities = new Capabilities();
            capabilities.setSupportsConfigurationDoneRequest(Boolean.TRUE);
            return CompletableFuture.completedFuture(capabilities);
        }

        @Override
        public CompletableFuture<Void> attach(Map<String, Object> arguments) {
            Object portValue = arguments.get("port");
            if (!(portValue instanceof Number targetPort)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("DAP attach omitted its port"));
            }
            try {
                lastTargetPort = targetPort.intValue();
                virtualMachine = attachJdi(lastTargetPort);
                IDebugProtocolClient remote = client;
                if (remote == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException("DAP client is not connected"));
                }
                initialized.set(1);
                remote.initialized();
                return configured;
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override
        public CompletableFuture<Void> configurationDone(ConfigurationDoneArguments arguments) {
            if (initialized.get() != 1) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("configurationDone arrived before the JDWP attach and initialized event"));
            }
            try {
                VirtualMachine current = virtualMachine;
                if (current == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException("No JDWP target is attached"));
                }
                current.resume();
                configured.complete(null);
                return CompletableFuture.completedFuture(null);
            } catch (RuntimeException failure) {
                configured.completeExceptionally(failure);
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override
        public CompletableFuture<ThreadsResponse> threads() {
            VirtualMachine current = virtualMachine;
            if (current == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("No JDWP target is attached"));
            }
            try {
                ThreadsResponse response = new ThreadsResponse();
                response.setThreads(current.allThreads().stream().map(target -> {
                    Thread thread = new Thread();
                    thread.setId(threadIds.computeIfAbsent(target.uniqueID(), ignored -> nextThreadId.getAndIncrement()));
                    thread.setName(target.name());
                    return thread;
                }).toArray(Thread[]::new));
                return CompletableFuture.completedFuture(response);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override
        public CompletableFuture<SetBreakpointsResponse> setBreakpoints(SetBreakpointsArguments arguments) {
            List<SourceBreakpoint> requested = arguments.getBreakpoints() == null
                    ? List.of() : List.of(arguments.getBreakpoints());
            breakpointLines.set(requested.stream().map(SourceBreakpoint::getLine).toList());
            SetBreakpointsResponse response = new SetBreakpointsResponse();
            response.setBreakpoints(requested.stream().map(sourceBreakpoint -> {
                Breakpoint breakpoint = new Breakpoint();
                breakpoint.setLine(sourceBreakpoint.getLine());
                breakpoint.setVerified(true);
                return breakpoint;
            }).toArray(Breakpoint[]::new));
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public CompletableFuture<Void> disconnect(DisconnectArguments arguments) {
            disposeTarget();
            return CompletableFuture.completedFuture(null);
        }
    }

    private static VirtualMachine attachJdi(int targetPort) throws Exception {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(candidate -> candidate.name().contains("SocketAttach"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("The JDK has no socket attaching connector"));
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("hostname").setValue("127.0.0.1");
        arguments.get("port").setValue(Integer.toString(targetPort));
        Connector.Argument timeout = arguments.get("timeout");
        if (timeout != null) {
            timeout.setValue("5000");
        }
        return connector.attach(arguments);
    }
}
