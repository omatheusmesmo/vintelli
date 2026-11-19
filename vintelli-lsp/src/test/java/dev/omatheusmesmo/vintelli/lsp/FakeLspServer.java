package dev.omatheusmesmo.vintelli.lsp;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonObject;

import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.CreateFile;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightKind;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.DidChangeNotebookDocumentParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseNotebookDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenNotebookDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveNotebookDocumentParams;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.ExecuteCommandParams;
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
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
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

    static final List<String> FIXTURE_COMMANDS = List.of("fake.apply", "fake.reject", "fake.hang", "fake.drop",
            "fake.drop-after-edit");

    /**
     * Flags: {@code --ignore-exit} keeps the process alive after the exit
     * notification, {@code --spawn-child} starts a holding child during
     * initialize, {@code --fail-initialize} refuses the initialize request,
     * {@code --trace-java-home} traces the {@code JAVA_HOME} the process got
     * and {@code --hold} turns the process into the holding child itself.
     * {@code --publish-real-path} publishes the diagnostics under the real path
     * of the document, as jdt.ls does when the workspace goes through a link.
     * {@code --deprecated-tag} tags the diagnostic published on open with the
     * {@code Deprecated} tag. {@code --resolve-provider} advertises
     * {@code completionItem/resolve} for the completions. {@code --no-formatting}
     * leaves the formatting providers out of the advertised capabilities.
     * {@code --no-document-highlight} leaves {@code documentHighlightProvider}
     * out of the capabilities.
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
        private final AtomicInteger debugAdapterRequests = new AtomicInteger();
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
            if (arguments.contains("--trace-java-home")) {
                trace("JAVA_HOME " + System.getenv("JAVA_HOME"));
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
            capabilities.setDocumentHighlightProvider(!arguments.contains("--no-document-highlight"));
            CompletionOptions completion = new CompletionOptions();
            completion.setResolveProvider(arguments.contains("--resolve-provider"));
            capabilities.setCompletionProvider(completion);
            capabilities.setCallHierarchyProvider(true);
            capabilities.setDocumentSymbolProvider(true);
            capabilities.setWorkspaceSymbolProvider(true);
            capabilities.setCodeActionProvider(true);
            if (!arguments.contains("--no-formatting")) {
                capabilities.setDocumentFormattingProvider(true);
                capabilities.setDocumentRangeFormattingProvider(true);
            }
            capabilities.setExecuteCommandProvider(new ExecuteCommandOptions(advertisedCommands()));
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

        private List<String> advertisedCommands() {
            List<String> commands = new ArrayList<>(FIXTURE_COMMANDS);
            if (arguments.contains("--java-debug")) {
                commands.add("vscode.java.startDebugSession");
            }
            return commands;
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
            remoteClient.publishDiagnostics(new PublishDiagnosticsParams(publishedUri(uri), diagnostics));
        }

        private String publishedUri(String uri) {
            if (!arguments.contains("--publish-real-path")) {
                return uri;
            }
            try {
                Path path = Path.of(URI.create(uri));
                Path real = Files.exists(path)
                        ? path.toRealPath()
                        : path.getParent().toRealPath().resolve(path.getFileName());
                return real.toUri().toString();
            } catch (IOException unresolvable) {
                return uri;
            }
        }

        String marker() {
            return marker;
        }
    }

    public static final String WORKSPACE_SYMBOL_QUERY = "AbCo";
    public static final String WORKSPACE_TYPE_URI = "file:///fake/workspace/AbstractController.java";
    public static final String LIBRARY_TYPE_URI = "jdt://contents/rt.jar/java.util/ArrayList.class?=fake";

    public static final int RICH_COMPLETION_LINE = 1;
    public static final int BUFFER_COMPLETION_LINE = 2;
    public static final int IMPORT_COMPLETION_LINE = 3;
    static final String RESOLVED_IMPORT = "import java.util.LinkedList;\n";
    static final String HANGING_DATA = "hang.";
    public static final String UNSAVED_MARKER = "unsaved-marker";
    public static final int RICH_HOVER_LINE = 3;
    public static final int EMPTY_HOVER_LINE = 4;
    public static final int BUFFER_HOVER_LINE = 5;
    public static final String RICH_HOVER = """
            ```java
            int String.length()
            ```
            ---
            Returns the <code>length</code> of this string.<br>
            <p>See <b>Unicode</b> &amp; the &lt;T&gt; notes.</p>
            """;

    static final class FakeTextDocumentService implements TextDocumentService {

        private final FakeLanguageServer server;
        private final Map<String, String> texts = new ConcurrentHashMap<>();

        FakeTextDocumentService(FakeLanguageServer server) {
            this.server = server;
        }

        @Override
        public void didOpen(DidOpenTextDocumentParams params) {
            server.trace("didOpen");
            texts.put(params.getTextDocument().getUri(), params.getTextDocument().getText());
            Diagnostic opened = new Diagnostic(
                    new Range(new Position(0, 0), new Position(0, 5)),
                    "fake problem on " + params.getTextDocument().getUri(),
                    DiagnosticSeverity.Error, "fake");
            if (server.arguments.contains("--deprecated-tag")) {
                opened.setTags(List.of(DiagnosticTag.Deprecated));
            }
            server.publish(params.getTextDocument().getUri(), List.of(opened));
        }

        @Override
        public void didChange(DidChangeTextDocumentParams params) {
            server.trace("didChange");
            texts.put(params.getTextDocument().getUri(), params.getContentChanges().getFirst().getText());
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
            int line = params.getPosition().getLine();
            if (line == RICH_HOVER_LINE) {
                return CompletableFuture.completedFuture(new Hover(new MarkupContent("markdown", RICH_HOVER)));
            }
            if (line == EMPTY_HOVER_LINE) {
                return CompletableFuture.completedFuture(null);
            }
            if (line == BUFFER_HOVER_LINE) {
                String text = texts.getOrDefault(params.getTextDocument().getUri(), "");
                String source = text.contains(UNSAVED_MARKER) ? "unsaved" : "disk";
                return CompletableFuture.completedFuture(new Hover(
                        new MarkupContent("markdown", "buffer " + source)));
            }
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
        public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(
                DocumentHighlightParams params) {
            server.trace("textDocument/documentHighlight");
            Position at = params.getPosition();
            return CompletableFuture.completedFuture(List.of(
                    new DocumentHighlight(new Range(at, new Position(at.getLine(), at.getCharacter() + 4)),
                            DocumentHighlightKind.Read),
                    new DocumentHighlight(new Range(new Position(at.getLine() + 1, 0),
                            new Position(at.getLine() + 1, 4)), DocumentHighlightKind.Write),
                    new DocumentHighlight(new Range(new Position(at.getLine() + 2, 2),
                            new Position(at.getLine() + 2, 6)), DocumentHighlightKind.Text),
                    new DocumentHighlight(new Range(new Position(at.getLine() + 3, 1),
                            new Position(at.getLine() + 3, 5)))));
        }

        @Override
        public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
            server.trace("completion");
            int line = params.getPosition().getLine();
            if (line == RICH_COMPLETION_LINE) {
                return CompletableFuture.completedFuture(Either.forLeft(richCompletions()));
            }
            if (line == BUFFER_COMPLETION_LINE) {
                String text = texts.getOrDefault(params.getTextDocument().getUri(), "");
                String source = text.contains(UNSAVED_MARKER) ? "unsaved" : "disk";
                return CompletableFuture.completedFuture(Either.forLeft(List.of(
                        new CompletionItem("buffer_" + source))));
            }
            if (line == IMPORT_COMPLETION_LINE) {
                return CompletableFuture.completedFuture(Either.forLeft(importCompletions()));
            }
            return CompletableFuture.completedFuture(Either.forLeft(List.of(
                    new CompletionItem("fake_completion_" + server.marker()),
                    new CompletionItem("fake_second"))));
        }

        /**
         * {@code ArrayList} carries its import in the item, {@code LinkedList} gets it only from
         * {@code completionItem/resolve} (its data names the type), and {@code HangingList} is resolved by a
         * server that never answers.
         */
        private static List<CompletionItem> importCompletions() {
            CompletionItem arrayList = new CompletionItem("ArrayList");
            arrayList.setKind(CompletionItemKind.Class);
            arrayList.setDetail("java.util");
            arrayList.setAdditionalTextEdits(List.of(importEdit("import java.util.ArrayList;\n")));
            CompletionItem linkedList = new CompletionItem("LinkedList");
            linkedList.setKind(CompletionItemKind.Class);
            linkedList.setData("java.util.LinkedList");
            CompletionItem hanging = new CompletionItem("HangingList");
            hanging.setKind(CompletionItemKind.Class);
            hanging.setData(HANGING_DATA + "HangingList");
            return List.of(arrayList, linkedList, hanging);
        }

        private static TextEdit importEdit(String text) {
            return new TextEdit(new Range(new Position(0, 0), new Position(0, 0)), text);
        }

        @Override
        public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
            server.trace("completionItem/resolve");
            String data = String.valueOf(unresolved.getData());
            if (data.contains(HANGING_DATA)) {
                return new CompletableFuture<>();
            }
            if (data.contains("java.util.LinkedList")) {
                unresolved.setAdditionalTextEdits(List.of(importEdit(RESOLVED_IMPORT)));
            }
            return CompletableFuture.completedFuture(unresolved);
        }

        private static List<CompletionItem> richCompletions() {
            CompletionItem zeta = new CompletionItem("zeta");
            zeta.setKind(CompletionItemKind.Field);
            zeta.setDetail("int");
            zeta.setSortText("0004");
            CompletionItem gamma = new CompletionItem("gamma");
            gamma.setKind(CompletionItemKind.Method);
            gamma.setTextEdit(Either.forRight(new InsertReplaceEdit("gamma()",
                    new Range(new Position(RICH_COMPLETION_LINE, 0), new Position(RICH_COMPLETION_LINE, 2)),
                    new Range(new Position(RICH_COMPLETION_LINE, 0), new Position(RICH_COMPLETION_LINE, 4)))));
            gamma.setSortText("0003");
            CompletionItem beta = new CompletionItem("beta");
            beta.setKind(CompletionItemKind.Method);
            beta.setTextEdit(Either.forLeft(new TextEdit(
                    new Range(new Position(RICH_COMPLETION_LINE, 0), new Position(RICH_COMPLETION_LINE, 4)),
                    "beta()")));
            beta.setSortText("0002");
            CompletionItem alpha = new CompletionItem("alpha");
            alpha.setKind(CompletionItemKind.Snippet);
            alpha.setInsertTextFormat(InsertTextFormat.Snippet);
            alpha.setInsertText("alpha(${1:value}) $0");
            alpha.setDetail("(value: String)");
            alpha.setFilterText("alphaFilter");
            alpha.setSortText("0001");
            return List.of(zeta, gamma, beta, alpha);
        }

        @Override
        public CompletableFuture<List<Either<org.eclipse.lsp4j.SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>>> documentSymbol(
                org.eclipse.lsp4j.DocumentSymbolParams params) {
            server.trace("documentSymbol");
            org.eclipse.lsp4j.Range classRange = new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(0, 0),
                    new org.eclipse.lsp4j.Position(2, 0));
            org.eclipse.lsp4j.Range classSelection = new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(0, 6),
                    new org.eclipse.lsp4j.Position(0, 15));
            org.eclipse.lsp4j.DocumentSymbol clazz = new org.eclipse.lsp4j.DocumentSymbol("FakeClass",
                    org.eclipse.lsp4j.SymbolKind.Class, classRange, classSelection);
            org.eclipse.lsp4j.Range methodRange = new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(1, 4),
                    new org.eclipse.lsp4j.Position(1, 20));
            org.eclipse.lsp4j.Range methodSelection = new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(1, 9),
                    new org.eclipse.lsp4j.Position(1, 19));
            org.eclipse.lsp4j.DocumentSymbol method = new org.eclipse.lsp4j.DocumentSymbol("fakeMethod",
                    org.eclipse.lsp4j.SymbolKind.Method, methodRange, methodSelection);
            clazz.setChildren(java.util.List.of(method));
            return java.util.concurrent.CompletableFuture.completedFuture(
                    java.util.List.of(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(clazz)));
        }

        /**
         * Answers by the start line of the requested range, so one twin serves every
         * code action failure mode: line 0 a quick fix with a bare command, line 1 an
         * empty list, line 2 an error response, line 3 an edit without a range (a
         * malformed payload), line 4 a file creation the editor cannot apply, line 5 a
         * variable extraction whose edit travels in a {@code java.apply.workspaceEdit}
         * command and line 6 a method extraction with an edit. A request for the
         * {@code source} kind at line 0 answers the generate actions instead. Every request
         * traces the kinds it asked for in {@code context.only}.
         */
        @Override
        public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
            server.trace("codeAction");
            server.trace("codeAction only " + (params.getContext().getOnly() == null ? "none"
                    : params.getContext().getOnly()));
            String uri = params.getTextDocument().getUri();
            if (asksForOrganizeImports(params)) {
                server.trace("organizeImports");
                CodeAction organize = organizeImports(uri);
                return CompletableFuture.completedFuture(organize == null ? List.of()
                        : List.of(Either.forRight(organize)));
            }
            if (isSourceRequest(params) && params.getRange().getStart().getLine() == 0) {
                return CompletableFuture.completedFuture(generateActions(uri));
            }
            return switch (params.getRange().getStart().getLine()) {
                case 0 -> CompletableFuture.completedFuture(List.of(
                        Either.forRight(quickFix(uri)),
                        Either.forLeft(new Command("Run the fake fixer", "fake.fixer"))));
                case 1 -> CompletableFuture.completedFuture(List.of());
                case 2 -> CompletableFuture.failedFuture(new ResponseErrorException(new ResponseError(
                        ResponseErrorCode.InternalError, "the fake server cannot compute fixes", null)));
                case 3 -> CompletableFuture.completedFuture(List.of(Either.forRight(malformedFix())));
                case 5 -> CompletableFuture.completedFuture(List.of(Either.forRight(extractVariable(uri))));
                case 6 -> CompletableFuture.completedFuture(List.of(Either.forRight(extractMethod(uri))));
                default -> CompletableFuture.completedFuture(List.of(Either.forRight(createFileFix(uri))));
            };
        }

        private static boolean isSourceRequest(CodeActionParams params) {
            List<String> only = params.getContext().getOnly();
            return only != null && only.contains(CodeActionKind.Source);
        }

        /**
         * Source actions for a request that asks for the {@code source} kind: a generate action with a plain
         * edit, a jdtls prompt command that needs a field selection, and an organize imports action the client
         * has to leave out of a generate menu.
         */
        private static List<Either<Command, CodeAction>> generateActions(String uri) {
            CodeAction accessors = new CodeAction("Generate Getters and Setters");
            accessors.setKind("source.generate.accessors");
            accessors.setEdit(new WorkspaceEdit(Map.of(uri, List.of(new TextEdit(
                    new Range(new Position(0, 0), new Position(0, 0)), "public int getCount() { return 0; }\n")))));
            CodeAction toString = new CodeAction("Generate toString()");
            toString.setKind("source.generate.toString");
            toString.setCommand(new Command("Generate toString()", "java.action.generateToStringPrompt"));
            CodeAction imports = new CodeAction("Organize Imports");
            imports.setKind("source.organizeImports");
            imports.setEdit(new WorkspaceEdit(Map.of(uri, List.of())));
            return List.of(Either.forRight(accessors), Either.forRight(toString), Either.forRight(imports));
        }

        /**
         * The variable extraction as jdt.ls sends it: the edit travels in the argument of the client side
         * command {@code java.apply.workspaceEdit}, not in the action's edit.
         */
        private static CodeAction extractVariable(String uri) {
            CodeAction extraction = new CodeAction("Extract to local variable");
            extraction.setKind("refactor.extract.variable");
            extraction.setCommand(new Command("Extract to local variable", "java.apply.workspaceEdit",
                    List.of(new WorkspaceEdit(Map.of(uri, List.of(
                            new TextEdit(new Range(new Position(0, 0), new Position(0, 0)),
                                    "int extracted = 1;\n")))))));
            return extraction;
        }

        private static CodeAction extractMethod(String uri) {
            CodeAction extraction = new CodeAction("Extract to method");
            extraction.setKind("refactor.extract.function");
            extraction.setEdit(new WorkspaceEdit(Map.of(uri, List.of(
                    new TextEdit(new Range(new Position(0, 0), new Position(0, 0)), "void extracted() {}\n")))));
            return extraction;
        }

        private static CodeAction quickFix(String uri) {
            CodeAction fix = new CodeAction("Add missing import java.util.List");
            fix.setKind(CodeActionKind.QuickFix);
            fix.setIsPreferred(true);
            fix.setEdit(new WorkspaceEdit(Map.of(uri, List.of(new TextEdit(
                    new Range(new Position(0, 0), new Position(0, 0)), "import java.util.List;\n")))));
            return fix;
        }

        private static CodeAction malformedFix() {
            TextEdit broken = new TextEdit();
            broken.setNewText("import java.util.List;\n");
            CodeAction fix = new CodeAction("Add the import without a range");
            fix.setKind(CodeActionKind.QuickFix);
            fix.setEdit(new WorkspaceEdit(Map.of("file:///fake/Fake.java", List.of(broken))));
            return fix;
        }

        private static CodeAction createFileFix(String uri) {
            CodeAction fix = new CodeAction("Create the missing class");
            fix.setKind(CodeActionKind.QuickFix);
            List<Either<TextDocumentEdit, ResourceOperation>> changes = List.of(
                    Either.forRight(new CreateFile(uri.replace("Fake.java", "Missing.java"))));
            fix.setEdit(new WorkspaceEdit(changes));
            return fix;
        }

        /**
         * Formats by one rule: a line indented with exactly two spaces is indented with four, every other line
         * stays. The answer uses the text the client synchronized, so unsaved edits are what gets formatted, and
         * the trace records the options the client sent.
         */
        @Override
        public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
            server.trace("formatting");
            FormattingOptions options = params.getOptions();
            server.trace("formatting tab=" + options.getTabSize() + " spaces=" + options.isInsertSpaces());
            String uri = params.getTextDocument().getUri();
            return CompletableFuture.completedFuture(reindent(uri, 0, Integer.MAX_VALUE));
        }

        @Override
        public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
            server.trace("rangeFormatting");
            FormattingOptions options = params.getOptions();
            server.trace("rangeFormatting tab=" + options.getTabSize() + " spaces=" + options.isInsertSpaces());
            String uri = params.getTextDocument().getUri();
            return CompletableFuture.completedFuture(reindent(uri, params.getRange().getStart().getLine(),
                    params.getRange().getEnd().getLine()));
        }

        private List<TextEdit> reindent(String uri, int fromLine, int toLine) {
            String[] lines = texts.getOrDefault(uri, "").split("\n", -1);
            List<TextEdit> edits = new ArrayList<>();
            for (int line = fromLine; line <= Math.min(toLine, lines.length - 1); line++) {
                if (lines[line].startsWith("  ") && !lines[line].startsWith("   ")) {
                    edits.add(new TextEdit(new Range(new Position(line, 0), new Position(line, 2)), "    "));
                }
            }
            return edits;
        }

        private static boolean asksForOrganizeImports(CodeActionParams params) {
            CodeActionContext context = params.getContext();
            return context != null && context.getOnly() != null
                    && context.getOnly().contains(CodeActionKind.SourceOrganizeImports);
        }

        /**
         * Removes every import whose simple name the rest of the text does not use; no action when none is unused.
         */
        private CodeAction organizeImports(String uri) {
            String[] lines = texts.getOrDefault(uri, "").split("\n", -1);
            List<TextEdit> removals = new ArrayList<>();
            for (int line = 0; line < lines.length; line++) {
                if (lines[line].startsWith("import ") && !usedElsewhere(lines, lines[line])) {
                    removals.add(new TextEdit(new Range(new Position(line, 0), new Position(line + 1, 0)), ""));
                }
            }
            if (removals.isEmpty()) {
                return null;
            }
            CodeAction organize = new CodeAction("Organize imports");
            organize.setKind(CodeActionKind.SourceOrganizeImports);
            organize.setEdit(new WorkspaceEdit(Map.of(uri, removals)));
            return organize;
        }

        private static boolean usedElsewhere(String[] lines, String importLine) {
            String name = importLine.substring(importLine.lastIndexOf('.') + 1).replace(";", "").strip();
            for (String line : lines) {
                if (!line.startsWith("import ") && line.contains(name)) {
                    return true;
                }
            }
            return false;
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

        @Override
        public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(
                WorkspaceSymbolParams params) {
            server.trace("workspace/symbol");
            if (!WORKSPACE_SYMBOL_QUERY.equals(params.getQuery())) {
                return CompletableFuture.completedFuture(Either.forLeft(List.of()));
            }
            return CompletableFuture.completedFuture(Either.forLeft(List.of(
                    new SymbolInformation("AbstractController", SymbolKind.Class, new Location(
                            WORKSPACE_TYPE_URI, new Range(new Position(4, 13), new Position(4, 31))),
                            "com.example.web"),
                    new SymbolInformation("abcoRun", SymbolKind.Method, new Location(
                            WORKSPACE_TYPE_URI, new Range(new Position(9, 4), new Position(9, 10))),
                            "AbstractController"),
                    new SymbolInformation("ArrayList", SymbolKind.Class, new Location(
                            LIBRARY_TYPE_URI, new Range(new Position(0, 0), new Position(0, 9))), "java.util"))));
        }

        @Override
        public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
            server.trace("executeCommand " + params.getCommand());
            if (FIXTURE_COMMANDS.contains(params.getCommand())) {
                return fixtureCommand(params.getCommand());
            }
            if (!server.arguments.contains("--java-debug")
                    || !"vscode.java.startDebugSession".equals(params.getCommand())) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("unsupported command"));
            }
            for (String argument : server.arguments) {
                if (argument.startsWith("--dap-ports=")) {
                    String[] ports = argument.substring("--dap-ports=".length()).split(",");
                    int request = Math.min(server.debugAdapterRequests.getAndIncrement(), ports.length - 1);
                    return portValue(ports[request]);
                }
                if (argument.startsWith("--dap-port=")) {
                    return portValue(argument.substring("--dap-port=".length()));
                }
            }
            return CompletableFuture.completedFuture(60001);
        }

        /**
         * Answers the fixture commands: {@code fake.apply} asks the client for an edit of the
         * document, {@code fake.reject} asks for a file creation the client cannot apply,
         * {@code fake.hang} never answers, {@code fake.drop} ends the process at once and
         * {@code fake.drop-after-edit} ends it after the client answered an edit.
         */
        private CompletableFuture<Object> fixtureCommand(String command) {
            return switch (command) {
                case "fake.apply" -> askForEdit(fixtureEdit(), "applied");
                case "fake.reject" -> askForEdit(createFileEdit(), "rejected");
                case "fake.drop-after-edit" -> askForEdit(fixtureEdit(), "unused")
                        .thenApply(response -> dropConnection());
                case "fake.drop" -> CompletableFuture.completedFuture(dropConnection());
                default -> new CompletableFuture<>();
            };
        }

        private CompletableFuture<Object> askForEdit(WorkspaceEdit edit, String result) {
            return server.remoteClient.applyEdit(new ApplyWorkspaceEditParams(edit)).thenApply(response -> {
                server.trace("applyEdit " + response.isApplied() + " " + response.getFailureReason());
                return result;
            });
        }

        private Object dropConnection() {
            server.trace("drop");
            System.exit(1);
            return null;
        }

        private static WorkspaceEdit fixtureEdit() {
            return new WorkspaceEdit(Map.of("file:///fake/Fake.java", List.of(new TextEdit(
                    new Range(new Position(0, 0), new Position(0, 0)), "import java.util.List;\n"))));
        }

        private static WorkspaceEdit createFileEdit() {
            List<Either<TextDocumentEdit, ResourceOperation>> changes = List.of(
                    Either.forRight(new CreateFile("file:///fake/Missing.java")));
            return new WorkspaceEdit(changes);
        }

        private static CompletableFuture<Object> portValue(String value) {
            try {
                return CompletableFuture.completedFuture(Integer.valueOf(value));
            } catch (NumberFormatException malformed) {
                return CompletableFuture.completedFuture(value);
            }
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
