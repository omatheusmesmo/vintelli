package dev.omatheusmesmo.vintelli.lsp;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;

import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeNotebookDocumentParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseNotebookDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenNotebookDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveNotebookDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.NotebookDocumentService;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * Stand in for a language server with fixed answers, launched as a single main
 * class through the regular profile command of the client: every handled
 * request or notification appends its method name to a trace file, so a test
 * asserts the wire level behavior of a session, among others that initialize,
 * a diagnostics round trip and shutdown reached the server and that exit did
 * end the process.
 */
public final class FakeLspServer {

    /**
     * Flags: {@code --ignore-exit} keeps the process alive after the exit
     * notification, {@code --spawn-child} starts a holding child during
     * initialize, {@code --fail-initialize} refuses the initialize request and
     * {@code --hold} turns the process into the holding child itself.
     */
    public static void main(String[] args) throws Exception {
        if (List.of(args).contains("--hold")) {
            Thread.sleep(Long.MAX_VALUE);
            return;
        }
        FakeLanguageServer server = new FakeLanguageServer(tracePath(args), markerOf(args), List.of(args));
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, System.in, System.out);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening().get(120, TimeUnit.SECONDS);
    }

    /**
     * @return the classpath entries the server process needs: this class, the
     *         LSP4J protocol types, its JSON-RPC runtime and Gson
     * @throws URISyntaxException when a code source location is not a URI
     */
    public static String classpath() throws URISyntaxException {
        return String.join(File.pathSeparator,
                entryOf(FakeLspServer.class),
                entryOf(LanguageServer.class),
                entryOf(Launcher.class),
                entryOf(JsonObject.class));
    }

    private static String entryOf(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static Optional<Path> tracePath(String[] args) {
        for (int index = 0; index < args.length - 1; index++) {
            if ("--trace".equals(args[index])) {
                return Optional.of(Path.of(args[index + 1]));
            }
        }
        return Optional.empty();
    }

    private static String markerOf(String[] args) {
        for (int index = 0; index < args.length - 1; index++) {
            if ("--marker".equals(args[index])) {
                return args[index + 1];
            }
        }
        return "default-marker";
    }

    private FakeLspServer() {
    }

    static final class FakeLanguageServer implements LanguageServer, LanguageClientAware {

        private final FakeTextDocumentService textDocumentService = new FakeTextDocumentService(this);
        private final Optional<Path> trace;
        private final String marker;
        private final List<String> arguments;
        private volatile LanguageClient remoteClient;
        private volatile String initializationOptions = "";

        FakeLanguageServer(Optional<Path> trace, String marker, List<String> arguments) {
            this.trace = trace;
            this.marker = marker;
            this.arguments = arguments;
        }

        @Override
        public void connect(LanguageClient client) {
            remoteClient = client;
        }

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            if (params.getInitializationOptions() != null) {
                initializationOptions = params.getInitializationOptions().toString();
            }
            trace("initialize");
            if (initializationOptions != null && !initializationOptions.isBlank()) {
                trace("initializationOptions " + initializationOptions);
            }
            if (arguments.contains("--spawn-child")) {
                spawnHoldingChild();
            }
            if (arguments.contains("--fail-initialize")) {
                return CompletableFuture.failedFuture(new IllegalStateException("the fake server refuses to start"));
            }
            ServerCapabilities capabilities = new ServerCapabilities();
            capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
            capabilities.setHoverProvider(true);
            capabilities.setDefinitionProvider(true);
            capabilities.setReferencesProvider(true);
            capabilities.setCompletionProvider(new CompletionOptions());
            capabilities.setCallHierarchyProvider(true);
            return CompletableFuture.completedFuture(new InitializeResult(capabilities,
                    new ServerInfo("fake-language-server", "1.0.0")));
        }

        @Override
        public void initialized(InitializedParams params) {
            trace("initialized");
            remoteClient.logMessage(new MessageParams(MessageType.Log, "the fake language server is ready"));
        }

        @Override
        public CompletableFuture<Object> shutdown() {
            trace("shutdown");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void exit() {
            trace("exit");
            if (!arguments.contains("--ignore-exit")) {
                System.exit(0);
            }
        }

        private void spawnHoldingChild() {
            try {
                Process child = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"),
                        "-cp", classpath(), FakeLspServer.class.getName(), "--hold")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                trace("pid " + ProcessHandle.current().pid());
                trace("child " + child.pid());
            } catch (IOException | URISyntaxException failure) {
                throw new IllegalStateException("the holding child cannot start", failure);
            }
        }

        @Override
        public TextDocumentService getTextDocumentService() {
            return textDocumentService;
        }

        @Override
        public WorkspaceService getWorkspaceService() {
            return new FakeWorkspaceService(this);
        }

        @Override
        public NotebookDocumentService getNotebookDocumentService() {
            return new FakeNotebookDocumentService();
        }

        void trace(String method) {
            trace.ifPresent(path -> {
                try {
                    Files.writeString(path, method + "\n", StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignored) {
                }
            });
        }

        void publish(String uri, List<Diagnostic> diagnostics) {
            remoteClient.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
        }

        String marker() {
            return marker;
        }
    }

    static final class FakeTextDocumentService implements TextDocumentService {

        private final FakeLanguageServer server;

        FakeTextDocumentService(FakeLanguageServer server) {
            this.server = server;
        }

        @Override
        public void didOpen(DidOpenTextDocumentParams params) {
            server.trace("didOpen");
            server.publish(params.getTextDocument().getUri(), List.of(new Diagnostic(
                    new Range(new Position(0, 0), new Position(0, 5)),
                    "fake problem on " + params.getTextDocument().getUri(),
                    DiagnosticSeverity.Error, "fake")));
        }

        @Override
        public void didChange(DidChangeTextDocumentParams params) {
            server.trace("didChange");
            server.publish(params.getTextDocument().getUri(), List.of(new Diagnostic(
                    new Range(new Position(0, 0), new Position(0, 5)), "fake problem changed",
                    DiagnosticSeverity.Warning, "fake")));
        }

        @Override
        public void didClose(DidCloseTextDocumentParams params) {
            server.trace("didClose");
            server.publish(params.getTextDocument().getUri(), List.of());
        }

        @Override
        public void didSave(org.eclipse.lsp4j.DidSaveTextDocumentParams params) {
        }

        @Override
        public CompletableFuture<Hover> hover(HoverParams params) {
            server.trace("hover");
            return CompletableFuture.completedFuture(new Hover(
                    new MarkupContent("markdown", "# fake hover " + server.marker())));
        }

        @Override
        public CompletableFuture<Either<List<? extends Location>, List<? extends org.eclipse.lsp4j.LocationLink>>> definition(
                DefinitionParams params) {
            server.trace("definition");
            return CompletableFuture.completedFuture(Either.forLeft(List.of(new Location(
                    params.getTextDocument().getUri(), new Range(new Position(0, 0), new Position(0, 4))))));
        }

        @Override
        public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
            server.trace("references");
            return CompletableFuture.completedFuture(List.of(
                    new Location(params.getTextDocument().getUri(), new Range(new Position(2, 0), new Position(2, 4))),
                    new Location("file:///fake/other.java", new Range(new Position(3, 0), new Position(3, 4)))));
        }

        @Override
        public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
            server.trace("completion");
            return CompletableFuture.completedFuture(Either.forLeft(List.of(
                    new CompletionItem("fake_completion_" + server.marker()),
                    new CompletionItem("fake_second"))));
        }

        @Override
        public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(CallHierarchyPrepareParams params) {
            server.trace("callHierarchy/prepare");
            CallHierarchyItem item = new CallHierarchyItem("fakeCallSymbol", SymbolKind.Method,
                    "fake detail", new Range(new Position(0, 0), new Position(0, 4)),
                    new Range(new Position(0, 0), new Position(0, 4)));
            item.setUri(params.getTextDocument().getUri());
            return CompletableFuture.completedFuture(List.of(item));
        }

        @Override
        public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(
                CallHierarchyIncomingCallsParams params) {
            server.trace("callHierarchy/incomingCalls");
            Range range = new Range(new Position(1, 0), new Position(1, 4));
            CallHierarchyItem caller = new CallHierarchyItem("fakeCaller", SymbolKind.Method, "fake caller",
                    range, range);
            caller.setUri("file:///fake/caller.java");
            return CompletableFuture.completedFuture(List.of(new CallHierarchyIncomingCall(caller, List.of(range))));
        }

        @Override
        public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(
                CallHierarchyOutgoingCallsParams params) {
            server.trace("callHierarchy/outgoingCalls");
            Range range = new Range(new Position(2, 0), new Position(2, 4));
            CallHierarchyItem callee = new CallHierarchyItem("fakeCallee", SymbolKind.Method, "fake callee",
                    range, range);
            callee.setUri("file:///fake/callee.java");
            return CompletableFuture.completedFuture(List.of(new CallHierarchyOutgoingCall(callee, List.of(range))));
        }
    }

    static final class FakeWorkspaceService implements WorkspaceService {

        private final FakeLanguageServer server;

        FakeWorkspaceService(FakeLanguageServer server) {
            this.server = server;
        }

        @Override
        public void didChangeConfiguration(org.eclipse.lsp4j.DidChangeConfigurationParams params) {
        }

        @Override
        public void didChangeWatchedFiles(org.eclipse.lsp4j.DidChangeWatchedFilesParams params) {
        }

    }

    static final class FakeNotebookDocumentService implements NotebookDocumentService {

        @Override
        public void didOpen(DidOpenNotebookDocumentParams params) {
        }

        @Override
        public void didChange(DidChangeNotebookDocumentParams params) {
        }

        @Override
        public void didSave(DidSaveNotebookDocumentParams params) {
        }

        @Override
        public void didClose(DidCloseNotebookDocumentParams params) {
        }
    }
}
