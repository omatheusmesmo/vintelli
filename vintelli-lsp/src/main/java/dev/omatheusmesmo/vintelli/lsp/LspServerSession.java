package dev.omatheusmesmo.vintelli.lsp;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.gson.JsonParser;

import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ClientInfo;
import org.eclipse.lsp4j.CompletionCapabilities;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionCapabilities;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverCapabilities;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.ReferencesCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jboss.logging.Logger;

/**
 * One live LSP session over stdio: starts the language server process, hands
 * the streams to the LSP4J launcher, runs the initialize handshake and serves
 * the document sync notifications plus the feature requests. The launcher
 * executor is a cached pool of daemon threads owned by the session: a stock
 * LSP4J launcher creates non-daemon threads that keep a JVM (or a shell that
 * waits on the process) alive for about a minute after the last message
 * (measured in spike 47), so the executor is configured explicitly here.
 *
 * <p>Because the protocol channel is the stdout of the server process, the
 * stderr is drained into the log by a daemon thread; a server that fills its
 * stderr pipe would otherwise block forever.
 */
final class LspServerSession implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(LspServerSession.class);

    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(5);

    private final Launch launch;
    private final Process process;
    private final ExecutorService executor;
    private final LanguageServer server;
    private final Future<Void> listening;
    private final VintelliLanguageClient listener;
    private final InitializeResult initializeResult;
    private final Set<String> openDocuments = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> documentVersions = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * A resolved profile plus the launch command it turns into.
     *
     * @param profile profile name that identifies the session, for example {@code jdtls}
     * @param command full command of the server process, executable first
     * @param workspaceRoot existing directory used as server rootUri and working directory
     * @param initializationOptions raw JSON object or null
     */
    record Launch(String profile, List<String> command, Path workspaceRoot, String initializationOptions) {

        public Launch {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        }
    }

    private LspServerSession(Launch launch, Process process, ExecutorService executor, LanguageServer server,
            Future<Void> listening, VintelliLanguageClient listener, InitializeResult initializeResult) {
        this.launch = launch;
        this.process = process;
        this.executor = executor;
        this.server = server;
        this.listening = listening;
        this.listener = listener;
        this.initializeResult = initializeResult;
    }

    static LspServerSession start(Launch launch) throws IOException {
        if (!Files.isDirectory(launch.workspaceRoot())) {
            throw new IOException("the workspace root " + launch.workspaceRoot() + " of profile '"
                    + launch.profile() + "' does not exist");
        }
        ProcessBuilder builder = new ProcessBuilder(launch.command());
        builder.directory(launch.workspaceRoot().toFile());
        Process process = builder.start();
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("vintelli-lsp-" + launch.profile() + "-" + thread.getId());
            thread.setDaemon(true);
            return thread;
        });
        drainStderr(launch.profile(), process, executor);
        VintelliLanguageClient listener = new VintelliLanguageClient();
        try {
            Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(
                    listener,
                    process.getInputStream(),
                    process.getOutputStream(),
                    executor,
                    null);
            Future<Void> listening = launcher.startListening();
            LanguageServer server = launcher.getRemoteProxy();
            InitializeResult initializeResult = handshake(server, launch);
            return new LspServerSession(launch, process, executor, server, listening, listener, initializeResult);
        } catch (InterruptedException | ExecutionException | TimeoutException | RuntimeException failure) {
            executor.shutdownNow();
            destroyTree(process);
            throw new IOException("failed to start the language server of profile '" + launch.profile() + "': "
                    + failureDetail(failure), failure);
        }
    }

    String serverName() {
        if (initializeResult.getServerInfo() != null) {
            return initializeResult.getServerInfo().getName();
        }
        return launch.profile();
    }

    String profileName() {
        return launch.profile();
    }

    boolean isLive() {
        return !closed && process.isAlive();
    }

    Hover hover(String uri, Position position) throws ExecutionException, InterruptedException, TimeoutException {
        return server.getTextDocumentService()
                .hover(new HoverParams(new TextDocumentIdentifier(uri), position))
                .get(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    Either<List<? extends Location>, List<? extends LocationLink>> definition(String uri, Position position)
            throws ExecutionException, InterruptedException, TimeoutException {
        return server.getTextDocumentService()
                .definition(new DefinitionParams(new TextDocumentIdentifier(uri), position))
                .get(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    List<? extends Location> references(String uri, Position position, boolean includeDeclaration)
            throws ExecutionException, InterruptedException, TimeoutException {
        ReferenceParams params = new ReferenceParams(new TextDocumentIdentifier(uri), position,
                new ReferenceContext(includeDeclaration));
        return server.getTextDocumentService()
                .references(params)
                .get(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    Either<List<CompletionItem>, CompletionList> completion(String uri, Position position)
            throws ExecutionException, InterruptedException, TimeoutException {
        return server.getTextDocumentService()
                .completion(new CompletionParams(new TextDocumentIdentifier(uri), position))
                .get(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    List<CallHierarchyItem> prepareCallHierarchy(String uri, Position position)
            throws ExecutionException, InterruptedException, TimeoutException {
        return server.getTextDocumentService()
                .prepareCallHierarchy(new CallHierarchyPrepareParams(new TextDocumentIdentifier(uri), position))
                .get(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    List<CallHierarchyIncomingCall> incomingCalls(CallHierarchyItem item)
            throws ExecutionException, InterruptedException, TimeoutException {
        return server.getTextDocumentService()
                .callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(item))
                .get(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    List<CallHierarchyOutgoingCall> outgoingCalls(CallHierarchyItem item)
            throws ExecutionException, InterruptedException, TimeoutException {
        return server.getTextDocumentService()
                .callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(item))
                .get(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    synchronized void openDocument(Path file, String content) {
        String uri = uriFor(file);
        if (openDocuments.contains(uri)) {
            return;
        }
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, languageIdOf(file), 1, content)));
        documentVersions.put(uri, 1);
        openDocuments.add(uri);
    }

    void changeDocument(Path file, String content) {
        String uri = uriFor(file);
        if (!openDocuments.contains(uri)) {
            throw new IllegalStateException("the document " + uri + " is not open in this session");
        }
        int version = documentVersions.merge(uri, 1, Integer::sum);
        DidChangeTextDocumentParams change = new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(uri, version),
                List.of(new TextDocumentContentChangeEvent(content)));
        server.getTextDocumentService().didChange(change);
    }

    void closeDocument(Path file) {
        String uri = uriFor(file);
        if (openDocuments.remove(uri)) {
            server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
            documentVersions.remove(uri);
        }
    }

    boolean isOpen(Path file) {
        return openDocuments.contains(uriFor(file));
    }

    /**
     * Runs one document notification and waits for the diagnostics the server
     * pushes next for the file. The publication count is read before the
     * notification is sent, so a push that races the notification is never
     * missed.
     */
    List<Diagnostic> publishAndAwait(Path file, Runnable notification, Duration timeout)
            throws InterruptedException, TimeoutException {
        String uri = uriFor(file);
        int previous = listener.publicationCount(uri);
        notification.run();
        if (!listener.awaitPublicationAfter(uri, previous, timeout)) {
            throw new TimeoutException("no diagnostics for " + uri + " within " + timeout.toSeconds() + "s");
        }
        return listener.knownDiagnostics(uri);
    }

    List<Diagnostic> knownDiagnostics(Path file) {
        return listener.knownDiagnostics(uriFor(file));
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            server.shutdown().get(SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception shutdownFailure) {
            LOG.debug("the language server of profile '" + launch.profile() + "' answered shutdown with '"
                    + failureDetail(shutdownFailure) + "'", shutdownFailure);
        }
        try {
            server.exit();
        } catch (RuntimeException exitFailure) {
            LOG.debug("the exit notification of profile '" + launch.profile() + "' failed", exitFailure);
        }
        if (!awaitExit(EXIT_TIMEOUT)) {
            LOG.warn("the language server of profile '" + launch.profile() + "' did not exit after the exit"
                    + " notification; its process and descendants are destroyed");
            destroyTree(process);
        }
        executor.shutdownNow();
    }

    /**
     * @param timeout how long the server may take to leave after the exit notification
     * @return whether the process left within the timeout; an interrupted wait
     *         destroys the process tree and reports false
     */
    boolean awaitExit(Duration timeout) {
        try {
            return process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            destroyTree(process);
            return false;
        }
    }

    /**
     * The descendants are collected before the parent is killed, because a
     * launcher script such as the jdtls wrapper spawns the Java child that
     * holds the real work and it is re-parented once the parent is gone. Each
     * process is awaited, so no handle on the workspace is still open when the
     * caller goes on (Windows refuses to delete a directory in use).
     */
    private static void destroyTree(Process process) {
        List<ProcessHandle> tree = new ArrayList<>(process.descendants().toList());
        tree.add(process.toHandle());
        tree.forEach(ProcessHandle::destroyForcibly);
        for (ProcessHandle handle : tree) {
            try {
                handle.onExit().get(EXIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException ignored) {
                LOG.debug("a process of the language server tree did not report its exit in time");
            }
        }
    }

    private static void drainStderr(String profile, Process process, ExecutorService executor) {
        executor.submit(() -> {
            try (LineNumberReader reader = new LineNumberReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOG.debug("language server '" + profile + "' stderr: " + line);
                }
            } catch (IOException ignored) {
            }
        });
    }

    /**
     * Runs the initialize request first: until its result arrives, the protocol
     * does not allow any other message, so the handshake timeout is the upper
     * bound of the session creation.
     */
    private static InitializeResult handshake(LanguageServer server, Launch launch)
            throws InterruptedException, ExecutionException, TimeoutException {
        InitializeParams params = new InitializeParams();
        params.setProcessId((int) ProcessHandle.current().pid());
        params.setClientInfo(new ClientInfo("vintelli", applicationVersion()));
        params.setRootUri(uriFor(launch.workspaceRoot()));
        params.setWorkspaceFolders(List.of(new WorkspaceFolder(uriFor(launch.workspaceRoot()), launch.profile())));
        params.setCapabilities(clientCapabilities());
        if (launch.initializationOptions() != null && !launch.initializationOptions().isBlank()) {
            params.setInitializationOptions(JsonParser.parseString(launch.initializationOptions()));
        }
        InitializeResult result = server.initialize(params).get(HANDSHAKE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        if (result == null || result.getCapabilities() == null) {
            throw new ExecutionException(new IllegalStateException("the initialize result was empty"));
        }
        server.initialized(new InitializedParams());
        return result;
    }

    private static ClientCapabilities clientCapabilities() {
        WorkspaceClientCapabilities workspace = new WorkspaceClientCapabilities();
        workspace.setWorkspaceFolders(true);
        TextDocumentClientCapabilities textDocument = new TextDocumentClientCapabilities();
        HoverCapabilities hover = new HoverCapabilities();
        hover.setContentFormat(List.of(MarkupKind.MARKDOWN));
        textDocument.setHover(hover);
        textDocument.setDefinition(new DefinitionCapabilities());
        textDocument.setReferences(new ReferencesCapabilities());
        textDocument.setCompletion(new CompletionCapabilities());
        ClientCapabilities capabilities = new ClientCapabilities();
        capabilities.setWorkspace(workspace);
        capabilities.setTextDocument(textDocument);
        return capabilities;
    }

    private static String applicationVersion() {
        return System.getProperty("quarkus.application.version", "dev");
    }

    static String uriFor(Path file) {
        return file.toAbsolutePath().toUri().toString();
    }

    /**
     * The target of a location comes back as a URI from the server, which may
     * point outside the worktree; anything that is not a file URI stays a URI.
     */
    static String fileFor(String uri) {
        if (uri != null && uri.startsWith("file:")) {
            try {
                return Path.of(URI.create(uri)).toString();
            } catch (RuntimeException invalid) {
                return uri;
            }
        }
        return uri;
    }

    private static String languageIdOf(Path file) {
        String name = file.getFileName().toString();
        int extension = name.lastIndexOf('.');
        return switch (extension < 0 ? "" : name.substring(extension)) {
            case ".java" -> "java";
            case ".kt" -> "kotlin";
            case ".scala" -> "scala";
            case ".xml" -> "xml";
            default -> "plaintext";
        };
    }

    static String failureDetail(Throwable failure) {
        String message = failure.getMessage();
        if ((message == null || message.isBlank()) && failure.getCause() != null) {
            message = failure.getCause().getMessage();
        }
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}

