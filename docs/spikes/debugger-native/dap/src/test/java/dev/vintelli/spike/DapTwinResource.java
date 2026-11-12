package dev.vintelli.spike;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.eclipse.lsp4j.debug.Thread;
import java.util.concurrent.ExecutorService;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.ThreadsResponse;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public final class DapTwinResource implements QuarkusTestResourceLifecycleManager {
    private ServerSocket server;
    private final List<Socket> connections = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    @Override public Map<String, String> start() {
        try {
            server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
            executor.submit(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        connections.add(socket);
                        Twin twin = new Twin();
                        var launcher = DSPLauncher.createServerLauncher(twin, traced(socket.getInputStream()), traced(socket.getOutputStream()), executor,
                                consumer -> consumer);
                        twin.client = launcher.getRemoteProxy();
                        launcher.startListening();
                    } catch (Exception failure) { if (!server.isClosed()) throw new IllegalStateException(failure); }
                }
            });
            return Map.of("spike.target.port", String.valueOf(server.getLocalPort()));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static InputStream traced(InputStream input) {
        return new FilterInputStream(input) {
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = super.read(bytes, offset, length);
                if (count > 0) System.out.println("DAP twin received: " + new String(bytes, offset, count, StandardCharsets.UTF_8));
                return count;
            }
        };
    }

    private static OutputStream traced(OutputStream output) {
        return new FilterOutputStream(output) {
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                System.out.println("DAP twin sent: " + new String(bytes, offset, length, StandardCharsets.UTF_8));
                out.write(bytes, offset, length);
            }
        };
    }
    @Override public void stop() {
        try {
            if (server != null) server.close();
            for (Socket socket : connections) socket.close();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
        finally { executor.shutdownNow(); }
    }

    public static final class Twin implements IDebugProtocolServer {
        private IDebugProtocolClient client;
        @Override public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {
            Capabilities result = new Capabilities();
            result.setSupportsConfigurationDoneRequest(true);
            return CompletableFuture.completedFuture(result);
        }
        @Override public CompletableFuture<Void> attach(Map<String, Object> args) {
            if (!"127.0.0.1".equals(args.get("hostName"))) return CompletableFuture.failedFuture(new IllegalArgumentException());
            StoppedEventArguments stopped = new StoppedEventArguments();
            stopped.setReason("breakpoint");
            stopped.setThreadId(1);
            client.stopped(stopped);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<ThreadsResponse> threads() {
            var thread = new Thread();
            thread.setId(1);
            thread.setName("main");
            ThreadsResponse result = new ThreadsResponse();
            result.setThreads(new Thread[] { thread });
            return CompletableFuture.completedFuture(result);
        }
        @Override public CompletableFuture<Void> disconnect(DisconnectArguments args) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
