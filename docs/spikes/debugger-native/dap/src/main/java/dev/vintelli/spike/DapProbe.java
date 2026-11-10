package dev.vintelli.spike;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Arrays;
import org.eclipse.lsp4j.debug.Thread;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.ThreadsResponse;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkus.runtime.annotations.RegisterForProxy;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/** Isolated typed DAP wire experiment against a JVM adapter twin. */
@Path("/probe")
@Singleton
@Blocking
@RegisterForProxy(targets = { IDebugProtocolServer.class, Endpoint.class })
@RegisterForReflection(targets = { IDebugProtocolServer.class, IDebugProtocolClient.class,
        InitializeRequestArguments.class, Capabilities.class, DisconnectArguments.class,
        StoppedEventArguments.class, ThreadsResponse.class, Thread.class }, registerFullHierarchy = true)
public final class DapProbe {
    private final int port;
    @Inject public DapProbe(@ConfigProperty(name = "spike.target.port") int port) { this.port = port; }

    @GET
    public Result probe() throws Exception {
        ProbeClient client = new ProbeClient();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            var launcher = DSPLauncher.createClientLauncher(client, socket.getInputStream(), socket.getOutputStream(),
                    executor, consumer -> consumer);
            var listening = launcher.startListening();
            try {
                var server = launcher.getRemoteProxy();
                InitializeRequestArguments arguments = new InitializeRequestArguments();
                arguments.setAdapterID("java");
                arguments.setClientID("vintelli-spike");
                arguments.setLinesStartAt1(true);
                Capabilities capabilities = server.initialize(arguments).get(3, TimeUnit.SECONDS);
                server.attach(Map.of("hostName", "127.0.0.1", "port", 5005)).get(3, TimeUnit.SECONDS);
                StoppedEventArguments stopped = client.stopped.get(3, TimeUnit.SECONDS);
                ThreadsResponse threads = server.threads().get(3, TimeUnit.SECONDS);
                server.disconnect(new DisconnectArguments()).get(3, TimeUnit.SECONDS);
                return new Result(Boolean.TRUE.equals(capabilities.getSupportsConfigurationDoneRequest()),
                        stopped.getReason(), Arrays.stream(threads.getThreads()).map(thread -> thread.getName()).toList());
            } catch (Exception failure) {
                if (listening.isDone()) {
                    try { listening.get(1, TimeUnit.SECONDS); }
                    catch (Exception readerFailure) { failure.addSuppressed(readerFailure); }
                }
                throw failure;
            } finally { listening.cancel(true); }
        } finally { executor.shutdownNow(); }
    }

    @RegisterForReflection
    public static final class ProbeClient implements IDebugProtocolClient {
        private final CompletableFuture<StoppedEventArguments> stopped = new CompletableFuture<>();
        @Override public void stopped(StoppedEventArguments event) { stopped.complete(event); }
    }

    public record Result(boolean configurationDone, String reason, List<String> threads) { }
}
