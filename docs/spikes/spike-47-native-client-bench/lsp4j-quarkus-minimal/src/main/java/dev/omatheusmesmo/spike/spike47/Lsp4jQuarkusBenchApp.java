package dev.omatheusmesmo.spike.spike47;

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

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Same minimal LSP4J flow as the standalone jbang client, hosted inside a
 * Quarkus application so the Quarkus native image cost of the stock LSP4J
 * stack can be measured. @QuarkusMain runs the flow after Quarkus bootstrap
 * and exits non zero on failure.
 */
@QuarkusMain
public class Lsp4jQuarkusBenchApp implements QuarkusApplication {

    private static final long TIMEOUT_S = 60L;

    public static void main(String[] args) {
        Quarkus.run(Lsp4jQuarkusBenchApp.class, args);
    }

    @Override
    public int run(String[] args) throws Exception {
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
            return runSession(stdin, stdout, process);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private int runSession(InputStream stdin, OutputStream stdout, Process process) throws Exception {
        long totalStart = System.nanoTime();
        LanguageClientImpl client = new LanguageClientImpl();
        Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(client, stdin, stdout);
        launcher.startListening();
        LanguageServer server = launcher.getRemoteProxy();

        InitializeParams params = new InitializeParams();
        params.setProcessId(null);
        params.setCapabilities(new ClientCapabilities());
        params.setRootUri("file:///mnt/spike-sandbox");
        InitializeResult initializeResult = server.initialize(params).get(TIMEOUT_S, TimeUnit.SECONDS);
        long initNanos = System.nanoTime() - totalStart;
        if (initializeResult.getServerInfo() == null) {
            throw new IllegalStateException("initialize result incomplete");
        }

        server.initialized(new InitializedParams());

        long hoverStart = System.nanoTime();
        Hover hover = server.getTextDocumentService()
                .hover(new HoverParams(new TextDocumentIdentifier("file:///src/A.java"), new Position(0, 0)))
                .get(TIMEOUT_S, TimeUnit.SECONDS);
        long hoverNanos = System.nanoTime() - hoverStart;
        if (hover == null || hover.getContents() == null) {
            throw new IllegalStateException("hover result incomplete");
        }

        server.shutdown().get(TIMEOUT_S, TimeUnit.SECONDS);
        server.exit();
        if (process != null && !process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("echo server did not exit");
        }

        long totalNanos = System.nanoTime() - totalStart;
        System.err.printf("lsp4j-quarkus %d %d %d ok%n",
                TimeUnit.NANOSECONDS.toMillis(initNanos),
                TimeUnit.NANOSECONDS.toMillis(hoverNanos),
                TimeUnit.NANOSECONDS.toMillis(totalNanos));
        return 0;
    }

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
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams message) {
        }
    }
}
