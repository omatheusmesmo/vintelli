//DEPS org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

/**
 * Minimal LSP4J client for the spike 47 native benchmark, the Option A side of
 * the comparison defined in docs/spikes/spike-23-lsp4j-vs-own-jsonrpc-client.md.
 *
 * Runs the same 0.1.0 message flow against the same echo server as the
 * hand-rolled client: initialize, initialized, one textDocument/hover request,
 * shutdown, exit. Uses the stock LSP4J stack: dynamic proxy endpoints, Gson
 * serialization, CompletableFuture API.
 *
 * The default launcher executor is a cached pool of non-daemon threads, which
 * keeps the process alive for about 60 s after the session ends (measured on
 * the JVM and in the native images), so the client exits explicitly once the
 * summary line is printed.
 *
 * Two modes, both with the same summary line (lsp4j <init> <hover> <total> ok):
 * - stdin/stdout mode, for an externally launched server:
 *   jbang lsp4j-minimal-client.java
 * - spawn mode, for quick manual runs: the client starts the server itself
 *   and pipes stdio to it:
 *   jbang lsp4j-minimal-client.java <echo-server-program> [args...]
 */
final class Lsp4jMinimalClient {

    private static final long TIMEOUT_S = 60L;

    public static void main(String[] args) throws Exception {
        Process process = null;
        InputStream stdin;
        OutputStream stdout;
        if (args.length < 1) {
            stdin = new BufferedInputStream(System.in);
            stdout = new BufferedOutputStream(System.out);
        } else {
            List<String> serverCommand = new ArrayList<>();
            serverCommand.add(args[0]);
            for (int i = 1; i < args.length; i++) {
                serverCommand.add(args[i]);
            }
            process = new ProcessBuilder(serverCommand).start();
            stdin = new BufferedInputStream(process.getInputStream());
            stdout = new BufferedOutputStream(process.getOutputStream());
        }

        try {
            runSession(stdin, stdout, process);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static void runSession(InputStream stdin, OutputStream stdout, Process process)
            throws Exception {
        long totalStart = System.nanoTime();
        LanguageClientImpl client = new LanguageClientImpl();
        Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(
                client, stdin, stdout);
        launcher.startListening();
        LanguageServer server = launcher.getRemoteProxy();

        InitializeParams params = new InitializeParams();
        params.setProcessId(null);
        params.setCapabilities(new ClientCapabilities());
        params.setRootUri("file:///mnt/spike-sandbox");
        InitializeResult initializeResult = server.initialize(params)
                .get(TIMEOUT_S, TimeUnit.SECONDS);
        long initNanos = System.nanoTime() - totalStart;
        requireState(initializeResult.getServerInfo() != null, "initialize result incomplete");

        server.initialized(new InitializedParams());

        long hoverStart = System.nanoTime();
        Hover hover = server.getTextDocumentService().hover(new HoverParams(
                new TextDocumentIdentifier("file:///src/A.java"),
                new Position(0, 0)))
                .get(TIMEOUT_S, TimeUnit.SECONDS);
        long hoverNanos = System.nanoTime() - hoverStart;
        requireState(hover != null && hover.getContents() != null, "hover result incomplete");

        server.shutdown().get(TIMEOUT_S, TimeUnit.SECONDS);
        server.exit();
        if (process != null && !process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("echo server did not exit");
        }

        long totalNanos = System.nanoTime() - totalStart;
        System.err.printf("lsp4j %d %d %d ok%n",
                TimeUnit.NANOSECONDS.toMillis(initNanos),
                TimeUnit.NANOSECONDS.toMillis(hoverNanos),
                TimeUnit.NANOSECONDS.toMillis(totalNanos));
        System.exit(0);
    }

    private static void requireState(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * Receives server-to-client traffic with stock LSP4J defaults; abstract
     * methods are implemented explicitly, everything else rides the upstream
     * interface defaults.
     */
    private static final class LanguageClientImpl implements LanguageClient {
        @Override
        public void telemetryEvent(Object object) {
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
        }

        @Override
        public void showMessage(MessageParams messageParams) {
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(
                ShowMessageRequestParams requestParams) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams message) {
        }
    }
}
