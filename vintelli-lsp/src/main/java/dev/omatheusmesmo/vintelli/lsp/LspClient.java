package dev.omatheusmesmo.vintelli.lsp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jboss.logging.Logger;

/**
 * Generic client for a language server launched from a configured profile
 * (spike 23 resolution recorded in issue 20): the process is launched through
 * the LSP4J launcher with an executor that does not linger, the session runs
 * initialize, initialized, didOpen, didChange, didClose and shutdown/exit and
 * the capability ports of {@code vintelli-api} are served from it. Hover,
 * definition, references and completion are exposed for the surfaces that
 * consume the client directly.
 *
 * <p>Everything blocks: the SESSION lives on one process and the port contract
 * declares {@link CapabilityResult} instead of checked exceptions, so both an
 * offline server, a missing binary and a server that is still indexing end in
 * an explicit result the callers can render.
 */
@Singleton
public final class LspClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(LspClient.class);

    private static final Duration DIAGNOSTICS_WAIT = Duration.ofSeconds(30);

    private final LspProfileConfig profiles;
    private final Object lock = new Object();
    private volatile LspServerSession session;

    @Inject
    LspClient(LspProfileConfig profiles) {
        this.profiles = profiles;
    }

    /**
     * @return the health of the configured profile: unavailable with a clear
     *         reason while no profile is selected or the command does not
     *         resolve, available without starting the server
     */
    public ProviderHealth health() {
        Selection selection = selection();
        if (selection.failure() != null) {
            return ProviderHealth.unavailable(selection.failure());
        }
        LspServerSession current = session;
        if (current != null && !current.isLive()) {
            return ProviderHealth.unavailable("the language server of profile '" + current.profileName()
                    + "' is not running");
        }
        return ProviderHealth.available();
    }

    /**
     * @return all problems the server pushed for the file; a failed result
     *         carries the configuration or readiness reason, including the
     *         still indexing state of a server that publishes nothing in time
     */
    public CapabilityResult<List<DiagnosticsCapability.Diagnostic>> diagnostics(Path file) {
        Selection selection = selection();
        if (selection.failure() != null) {
            return CapabilityResult.failed(new CapabilityFailure("unavailable", selection.failure()));
        }
        try {
            LspServerSession current = ensureStarted(selection);
            String content = Files.readString(file);
            List<Diagnostic> problems = current.isOpen(file)
                    ? current.knownDiagnostics(file)
                    : current.publishAndAwait(file, () -> current.openDocument(file, content), DIAGNOSTICS_WAIT);
            return CapabilityResult.ok(problems.stream().map(problem -> capabilityDiagnostic(file, problem)).toList());
        } catch (TimeoutException notTranslated) {
            return CapabilityResult.failed(new CapabilityFailure("not-ready",
                    "the language server of profile '" + selection.profile() + "' published no diagnostics for '"
                            + file + "' within " + DIAGNOSTICS_WAIT.toSeconds() + "s; it may still be indexing"));
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return CapabilityResult.failed(failureDetail(selection, failure));
        }
    }

    /**
     * @return the hover at the file position as markdown, empty when the
     *         server has no hover there
     */
    public CapabilityResult<String> hover(Path file, int line, int column) {
        return serve(selection(),
                current -> current.hover(LspServerSession.uriFor(file), new Position(line, column)),
                LspClient::hoverText);
    }

    /**
     * @return the problems the server publishes after the change notification
     */
    public CapabilityResult<List<DiagnosticsCapability.Diagnostic>> modifyDocument(Path file, String content) {
        Selection selection = selection();
        if (selection.failure() != null) {
            return CapabilityResult.failed(new CapabilityFailure("unavailable", selection.failure()));
        }
        try {
            LspServerSession current = ensureStarted(selection);
            String disk = Files.readString(file);
            if (!current.isOpen(file)) {
                current.publishAndAwait(file, () -> current.openDocument(file, disk), DIAGNOSTICS_WAIT);
            }
            List<Diagnostic> problems = current.publishAndAwait(file,
                    () -> current.changeDocument(file, content), DIAGNOSTICS_WAIT);
            return CapabilityResult.ok(problems.stream().map(problem -> capabilityDiagnostic(file, problem)).toList());
        } catch (TimeoutException notYetPublishing) {
            return CapabilityResult.failed(new CapabilityFailure("not-ready",
                    "the language server of profile '" + selection.profile() + "' published no diagnostics for '"
                            + file + "' within " + DIAGNOSTICS_WAIT.toSeconds() + "s; it may still be indexing"));
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return CapabilityResult.failed(failureDetail(selection, failure));
        }
    }

    /**
     * Tells the server the document truth went back to the file on disk.
     */
    public void closeDocument(Path file) {
        LspServerSession current = session;
        if (current == null || !current.isLive()) {
            return;
        }
        try {
            current.closeDocument(file);
        } catch (RuntimeException failure) {
            LOG.debug("the didClose notification for '" + file + "': " + LspServerSession.failureDetail(failure),
                    failure);
        }
    }

    /**
     * @return the definitions of the symbol at the file position
     */
    public CapabilityResult<List<CodeReference>> definition(Path file, int line, int column) {
        return serve(selection(),
                current -> current.definition(LspServerSession.uriFor(file), new Position(line, column)),
                LspClient::definitionTargets);
    }

    /**
     * @return the project wide references of the symbol at the file position
     */
    public CapabilityResult<List<CodeReference>> references(Path file, int line, int column,
            boolean includeDeclaration) {
        return serve(selection(),
                current -> current.references(LspServerSession.uriFor(file), new Position(line, column),
                        includeDeclaration),
                LspClient::referenceTargets);
    }

    /**
     * @return the completion labels at the file position
     */
    public CapabilityResult<List<CompletionEntry>> completion(Path file, int line, int column) {
        return serve(selection(),
                current -> current.completion(LspServerSession.uriFor(file), new Position(line, column)),
                LspClient::completionEntries);
    }

    /**
     * @return the document outline (classes, methods, fields, ...) the server
     *         returns for the file; an empty list when the server has no
     *         symbols for it
     */
    public CapabilityResult<List<DocumentSymbol>> documentSymbols(Path file) {
        Selection selection = selection();
        if (selection.failure() != null) {
            return CapabilityResult.failed(new CapabilityFailure("unavailable", selection.failure()));
        }
        try {
            LspServerSession current = ensureStarted(selection);
            String content = Files.readString(file);
            if (!current.isOpen(file)) {
                current.openDocument(file, content);
            }
            List<Either<SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>> result = current.documentSymbol(
                    LspServerSession.uriFor(file));
            return CapabilityResult.ok(documentSymbolsOf(result));
        } catch (IOException | InterruptedException | ExecutionException | TimeoutException | RuntimeException failure) {
            if (failure instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return CapabilityResult.failed(failureDetail(selection, failure));
        }
    }

    /**
     * Alias for {@link #documentSymbols(Path)} matching the LSP request name.
     */
    public CapabilityResult<List<DocumentSymbol>> documentSymbol(Path file) {
        return documentSymbols(file);
    }

    /**
     * Alias for {@link #documentSymbols(Path)}.
     */
    public CapabilityResult<List<DocumentSymbol>> symbols(Path file) {
        return documentSymbols(file);
    }

    /**
     * @return call sites that call the symbol under the position
     */
    public CapabilityResult<List<CallHierarchyCapability.CallSite>> callers(
            CallHierarchyCapability.SourcePosition position) {
        return callSite(selection(), position, false);
    }

    /**
     * @return call sites the symbol under the position calls
     */
    public CapabilityResult<List<CallHierarchyCapability.CallSite>> callees(
            CallHierarchyCapability.SourcePosition position) {
        return callSite(selection(), position, true);
    }

    /**
     * @return the name of the profile that serves, empty while none is selected
     */
    public Optional<String> activeProfileName() {
        Selection selection = selection();
        if (selection.profile() != null) {
            return Optional.of(selection.profile());
        }
        return Optional.empty();
    }

    @Override
    @PreDestroy
    public void close() {
        synchronized (lock) {
            LspServerSession current = session;
            session = null;
            if (current != null) {
                current.close();
            }
        }
    }

    private CapabilityResult<List<CallHierarchyCapability.CallSite>> callSite(Selection selection,
            CallHierarchyCapability.SourcePosition position, boolean outgoing) {
        if (selection.failure() != null) {
            return CapabilityResult.failed(new CapabilityFailure("unavailable", selection.failure()));
        }
        try {
            LspServerSession current = ensureStarted(selection);
            String uri = LspServerSession.uriFor(position.filePath());
            List<CallHierarchyItem> items = current.prepareCallHierarchy(uri, new Position(position.line(),
                    position.column()));
            if (items == null || items.isEmpty()) {
                return CapabilityResult.failed(new CapabilityFailure("not-resolvable",
                        "the position " + position.filePath() + ":" + position.line() + ":" + position.column()
                                + " does not resolve to a symbol"));
            }
            List<CallHierarchyCapability.CallSite> calls = new ArrayList<>();
            if (outgoing) {
                for (CallHierarchyOutgoingCall call : current.outgoingCalls(items.get(0))) {
                    calls.add(callSiteOf(call.getTo()));
                }
            } else {
                for (CallHierarchyIncomingCall call : current.incomingCalls(items.get(0))) {
                    calls.add(callSiteOf(call.getFrom()));
                }
            }
            return CapabilityResult.ok(List.copyOf(calls));
        } catch (ExecutionException | IOException | InterruptedException | TimeoutException | RuntimeException failure) {
            if (failure instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return CapabilityResult.failed(failureDetail(selection, failure));
        }
    }

    /**
     * Fetches the feature and wraps it into the typed outcome of the call,
     * including the failure of a session that cannot answer.
     */
    private <V, T> CapabilityResult<T> serve(Selection selection, SessionQuery<V> query,
            Function<V, T> mapping) {
        if (selection.failure() != null) {
            return CapabilityResult.failed(new CapabilityFailure("unavailable", selection.failure()));
        }
        try {
            LspServerSession current = ensureStarted(selection);
            return CapabilityResult.ok(mapping.apply(query.fetch(current)));
        } catch (ExecutionException | IOException | InterruptedException | TimeoutException | RuntimeException failure) {
            if (failure instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return CapabilityResult.failed(failureDetail(selection, failure));
        }
    }

    @FunctionalInterface
    private interface SessionQuery<V> {
        V fetch(LspServerSession session) throws IOException, InterruptedException, ExecutionException, TimeoutException;
    }

    private static CapabilityFailure failureDetail(Selection selection, Throwable failure) {
        String detail = "the language server of profile '" + selection.profile() + "' refused the request: "
                + LspServerSession.failureDetail(failure);
        LOG.debug(detail, failure);
        return new CapabilityFailure("unavailable", detail);
    }


    private static List<DocumentSymbol> documentSymbolsOf(
            List<Either<SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>> result) {
        if (result == null) {
            return List.of();
        }
        List<DocumentSymbol> mapped = new ArrayList<>();
        for (Either<SymbolInformation, org.eclipse.lsp4j.DocumentSymbol> item : result) {
            if (item.isLeft()) {
                mapped.add(documentSymbolOf(item.getLeft()));
            } else if (item.getRight() != null) {
                mapped.add(documentSymbolOf(item.getRight()));
            }
        }
        return mapped;
    }

    private static DocumentSymbol documentSymbolOf(org.eclipse.lsp4j.DocumentSymbol symbol) {
        Range range = symbol.getRange();
        Range selection = symbol.getSelectionRange();
        List<DocumentSymbol> children = symbol.getChildren() == null ? List.of()
                : symbol.getChildren().stream().map(LspClient::documentSymbolOf).toList();
        return new DocumentSymbol(symbol.getName(), symbol.getDetail(), symbol.getKind(),
                lineOf(range), characterOf(range),
                range == null || range.getEnd() == null ? 0 : range.getEnd().getLine(),
                range == null || range.getEnd() == null ? 0 : range.getEnd().getCharacter(),
                lineOf(selection), characterOf(selection),
                selection == null || selection.getEnd() == null ? 0 : selection.getEnd().getLine(),
                selection == null || selection.getEnd() == null ? 0 : selection.getEnd().getCharacter(),
                children);
    }

    private static DocumentSymbol documentSymbolOf(SymbolInformation info) {
        Location location = info.getLocation();
        Range range = location == null ? null : location.getRange();
        return new DocumentSymbol(info.getName(), "", info.getKind(),
                lineOf(range), characterOf(range),
                range == null || range.getEnd() == null ? 0 : range.getEnd().getLine(),
                range == null || range.getEnd() == null ? 0 : range.getEnd().getCharacter(),
                lineOf(range), characterOf(range),
                range == null || range.getEnd() == null ? 0 : range.getEnd().getLine(),
                range == null || range.getEnd() == null ? 0 : range.getEnd().getCharacter(),
                List.of());
    }


    private static String hoverText(Hover hover) {
        if (hover == null || hover.getContents() == null) {
            return "";
        }
        Either<List<Either<String, MarkedString>>, MarkupContent> contents = hover.getContents();
        if (contents.isLeft()) {
            return contents.getLeft().stream()
                    .map(item -> item.map(data -> data, data -> data.getValue()))
                    .collect(Collectors.joining("\n"));
        }
        MarkupContent markup = contents.getRight();
        return markup.getValue() == null ? "" : markup.getValue();
    }

    private static List<CodeReference> definitionTargets(
            Either<List<? extends Location>, List<? extends LocationLink>> definition) {
        if (definition.isLeft()) {
            return definition.getLeft().stream().map(LspClient::referenceOf).toList();
        }
        return definition.getRight().stream()
                .map(link -> new CodeReference(LspServerSession.fileFor(link.getTargetUri()),
                        lineOf(link.getTargetSelectionRange()), characterOf(link.getTargetSelectionRange()),
                        link.getTargetSelectionRange() == null ? 0 : link.getTargetSelectionRange().getEnd().getLine(),
                        link.getTargetSelectionRange() == null ? 0
                                : link.getTargetSelectionRange().getEnd().getCharacter()))
                .toList();
    }

    private static List<CodeReference> referenceTargets(List<? extends Location> locations) {
        return locations.stream().map(LspClient::referenceOf).toList();
    }

    private static CodeReference referenceOf(Location location) {
        Range range = location.getRange();
        return new CodeReference(LspServerSession.fileFor(location.getUri()),
                lineOf(range), characterOf(range),
                range == null ? 0 : range.getEnd().getLine(),
                range == null ? 0 : range.getEnd().getCharacter());
    }

    private static List<CompletionEntry> completionEntries(
            Either<List<CompletionItem>, CompletionList> completion) {
        List<CompletionItem> items = completion.isLeft() ? completion.getLeft() : completion.getRight().getItems();
        return items.stream()
                .map(item -> new CompletionEntry(item.getLabel(),
                        item.getDetail() == null ? "" : item.getDetail()))
                .toList();
    }

    private static CallHierarchyCapability.CallSite callSiteOf(CallHierarchyItem item) {
        Position start = item.getSelectionRange().getStart();
        return new CallHierarchyCapability.CallSite(item.getName(),
                new CallHierarchyCapability.SourcePosition(
                        Path.of(LspServerSession.fileFor(item.getUri())),
                        start.getLine(), start.getCharacter()));
    }

    private static int lineOf(Range range) {
        return range == null || range.getStart() == null ? 0 : range.getStart().getLine();
    }

    private static int characterOf(Range range) {
        return range == null || range.getStart() == null ? 0 : range.getStart().getCharacter();
    }

    private static DiagnosticsCapability.Diagnostic capabilityDiagnostic(Path file, Diagnostic problem) {
        Range range = problem.getRange();
        String message = problem.getMessage() == null ? "" : problem.getMessage().map(
                body -> body,
                MarkupContent::getValue);
        return new DiagnosticsCapability.Diagnostic(file, lineOf(range), characterOf(range),
                range == null || range.getEnd() == null ? 0 : range.getEnd().getLine(),
                range == null || range.getEnd() == null ? 0 : range.getEnd().getCharacter(),
                severityOf(problem.getSeverity()),
                problem.getSource() == null ? "" : problem.getSource(),
                message);
    }

    private static DiagnosticsCapability.Severity severityOf(DiagnosticSeverity severity) {
        if (severity == null) {
            return DiagnosticsCapability.Severity.INFORMATION;
        }
        return switch (severity) {
            case Error -> DiagnosticsCapability.Severity.ERROR;
            case Warning -> DiagnosticsCapability.Severity.WARNING;
            case Hint -> DiagnosticsCapability.Severity.HINT;
            default -> DiagnosticsCapability.Severity.INFORMATION;
        };
    }

    private LspServerSession ensureStarted(Selection selection) throws IOException {
        synchronized (lock) {
            LspServerSession current = session;
            if (current != null && current.isLive()) {
                return current;
            }
            if (current != null) {
                session = null;
                current.close();
            }
            List<String> command = new ArrayList<>(List.of(selection.executable.toString()));
            selection.settings().args().ifPresent(command::addAll);
            String configuredRoot = selection.settings().workspaceRoot().orElse("");
            Path root = configuredRoot.isBlank()
                    ? Path.of(System.getProperty("user.dir"))
                    : Path.of(configuredRoot);
            LspServerSession.Launch launch = new LspServerSession.Launch(selection.profile(), command,
                    root, selection.settings().initializationOptions().orElse(null));
            current = LspServerSession.start(launch);
            session = current;
            return current;
        }
    }

    private Selection selection() {
        Map<String, LspProfileConfig.Profile> configured = profiles.profiles();
        if (configured.isEmpty()) {
            return Selection.unavailable("no language server profile is configured; set "
                    + "vintelli.lsp.profiles.<name>.command, for example "
                    + "vintelli.lsp.profiles.jdtls.command=jdtls");
        }
        String picked = profiles.profile().orElse(null);
        if (picked != null) {
            LspProfileConfig.Profile settings = configured.get(picked);
            if (settings == null) {
                return Selection.unavailable("the profile '" + picked + "' is not configured; known profiles: "
                        + String.join(", ", configured.keySet()));
            }
            return of(picked, settings);
        }
        if (configured.size() == 1) {
            Map.Entry<String, LspProfileConfig.Profile> single = configured.entrySet().iterator().next();
            return of(single.getKey(), single.getValue());
        }
        return Selection.unavailable("several language server profiles are configured (" + String.join(", ",
                configured.keySet()) + "); set vintelli.lsp.profile to pick one");
    }

    private static Selection of(String profile, LspProfileConfig.Profile settings) {
        if (settings.command().orElse("").isBlank()) {
            return Selection.unavailable("the profile '" + profile + "' declares no command; set "
                    + "vintelli.lsp.profiles." + profile + ".command");
        }
        String command = settings.command().orElseThrow();
        Optional<Path> resolved = resolve(command);
        if (resolved.isEmpty()) {
            return Selection.unavailable("the command '" + command + "' of profile '" + profile
                    + "' was not found; is the language server installed?");
        }
        return Selection.available(profile, settings, resolved.get());
    }

    /**
     * Only resolves the command: the health contract forbids blocking I O, so
     * no session start or reachability probe happens here.
     */
    private static Optional<Path> resolve(String command) {
        String trimmed = command.trim();
        if (trimmed.indexOf('/') >= 0 || trimmed.indexOf(File.separatorChar) >= 0) {
            Path candidate = Path.of(trimmed);
            return Files.isExecutable(candidate) ? Optional.of(candidate) : Optional.empty();
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String directory : path.split(File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            Path candidate = Path.of(directory).resolve(trimmed);
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * The configuration read of one selection: {@code profile} and
     * {@code executable} are non null with an empty {@code failure}.
     */
    record Selection(String profile, LspProfileConfig.Profile settings, Path executable, String failure) {

        static Selection available(String profile, LspProfileConfig.Profile settings, Path executable) {
            return new Selection(profile, settings, executable, null);
        }

        static Selection unavailable(String failure) {
            return new Selection(null, null, null, Objects.requireNonNull(failure));
        }
    }
}
