package dev.omatheusmesmo.vintelli.web;

import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.lsp.CodeReference;
import dev.omatheusmesmo.vintelli.lsp.CompletionEntry;
import dev.omatheusmesmo.vintelli.lsp.LspClient;
import dev.omatheusmesmo.vintelli.lsp.LspProfileConfig;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * Headless anchor for the LSP client so the integration tests, including the
 * native runner, can drive one language server session over HTTP against the
 * fake server instead of the TUI. The diagnostics and call hierarchy answers
 * come through the {@code vintelli-api} capability ports; hover, definition,
 * references and completion are served by the client directly, because the
 * ports for them come with their shell surfaces.
 *
 * <p>The endpoint is off unless {@code vintelli.lsp.debug-endpoint.enabled} is
 * {@code true} and answers 404 otherwise, because it launches the configured
 * language server for the session.
 */
@Path("/lsp")
@Singleton
@Produces(MediaType.APPLICATION_JSON)
public final class LspResource {

    private final CapabilityRegistry registry;
    private final LspClient client;
    private final List<NavigationCapability> navigationProviders;
    private final boolean enabled;

    @Inject
    LspResource(CapabilityRegistry registry, LspClient client, LspProfileConfig config,
            Instance<NavigationCapability> navigationProviders) {
        this.registry = registry;
        this.client = client;
        this.navigationProviders = navigationProviders.stream().toList();
        this.enabled = config.debugEndpointEnabled();
    }

    @GET
    @Path("/diagnostics")
    @Blocking
    public RestResponse<LspReply> diagnostics(@QueryParam("file") String file) {
        if (!enabled) {
            return disabled();
        }
        return optionalFile(file)
                .map(FileSystems.getDefault()::getPath)
                .map(path -> overPort(registry.diagnostics(),
                        provider -> provider.diagnostics(path),
                        problems -> lines(problems, LspResource::problemLine)))
                .orElseGet(() -> rejected("file is required"));
    }

    @GET
    @Path("/callers")
    @Blocking
    public RestResponse<LspReply> callers(@QueryParam("file") String file,
                                          @QueryParam("line") @DefaultValue("0") int line,
                                          @QueryParam("column") @DefaultValue("0") int column) {
        if (!enabled) {
            return disabled();
        }
        return optionalFile(file)
                .map(FileSystems.getDefault()::getPath)
                .map(path -> overPort(registry.callHierarchy(),
                        provider -> provider.callers(new CallHierarchyCapability.SourcePosition(path, line, column)),
                        calls -> lines(calls, LspResource::callSiteLine)))
                .orElseGet(() -> rejected("file is required"));
    }

    @GET
    @Path("/callees")
    @Blocking
    public RestResponse<LspReply> callees(@QueryParam("file") String file,
                                          @QueryParam("line") @DefaultValue("0") int line,
                                          @QueryParam("column") @DefaultValue("0") int column) {
        if (!enabled) {
            return disabled();
        }
        return optionalFile(file)
                .map(FileSystems.getDefault()::getPath)
                .map(path -> overPort(registry.callHierarchy(),
                        provider -> provider.callees(new CallHierarchyCapability.SourcePosition(path, line, column)),
                        calls -> lines(calls, LspResource::callSiteLine)))
                .orElseGet(() -> rejected("file is required"));
    }

    @GET
    @Path("/hover")
    @Blocking
    public RestResponse<LspReply> hover(@QueryParam("file") String file,
                                        @QueryParam("line") @DefaultValue("0") int line,
                                        @QueryParam("column") @DefaultValue("0") int column) {
        if (!enabled) {
            return disabled();
        }
        return optionalFile(file)
                .map(FileSystems.getDefault()::getPath)
                .map(path -> reply(client.hover(path, line, column), text -> text))
                .orElseGet(() -> rejected("file is required"));
    }

    @GET
    @Path("/definition")
    @Blocking
    public RestResponse<LspReply> definition(@QueryParam("file") String file,
                                             @QueryParam("line") @DefaultValue("0") int line,
                                             @QueryParam("column") @DefaultValue("0") int column) {
        if (!enabled) {
            return disabled();
        }
        return optionalFile(file)
                .map(FileSystems.getDefault()::getPath)
                .map(path -> reply(client.definition(path, line, column),
                        targets -> lines(targets, LspResource::referenceLine)))
                .orElseGet(() -> rejected("file is required"));
    }

    @GET
    @Path("/references")
    @Blocking
    public RestResponse<LspReply> references(@QueryParam("file") String file,
                                             @QueryParam("line") @DefaultValue("0") int line,
                                             @QueryParam("column") @DefaultValue("0") int column) {
        if (!enabled) {
            return disabled();
        }
        return optionalFile(file)
                .map(FileSystems.getDefault()::getPath)
                .map(path -> reply(client.references(path, line, column, false),
                        targets -> lines(targets, LspResource::referenceLine)))
                .orElseGet(() -> rejected("file is required"));
    }

    @GET
    @Path("/completion")
    @Blocking
    public RestResponse<LspReply> completion(@QueryParam("file") String file,
                                             @QueryParam("line") @DefaultValue("0") int line,
                                             @QueryParam("column") @DefaultValue("0") int column) {
        if (!enabled) {
            return disabled();
        }
        return optionalFile(file)
                .map(FileSystems.getDefault()::getPath)
                .map(path -> reply(client.completion(path, line, column),
                        entries -> lines(entries, LspResource::entryLine)))
                .orElseGet(() -> rejected("file is required"));
    }

    @GET
    @Path("/symbols")
    @Blocking
    public RestResponse<LspReply> symbols(@QueryParam("file") String file) {
        if (!enabled) {
            return disabled();
        }
        return optionalFile(file).map(FileSystems.getDefault()::getPath).map(path -> {
            NavigationCapability provider = navigationProviders.stream()
                    .filter(candidate -> candidate.health() != null && candidate.health().isAvailable())
                    .findFirst().orElse(null);
            if (provider == null) {
                String reason = navigationProviders.stream().map(NavigationCapability::health)
                        .filter(health -> health != null && !health.isAvailable())
                        .map(health -> health.reason()).findFirst()
                        .orElse("no navigation provider configured");
                return RestResponse.status(RestResponse.Status.SERVICE_UNAVAILABLE,
                        new LspReply("UNAVAILABLE", reason, ""));
            }
            return reply(provider.documentSymbols(path), symbols -> {
                List<String> lines = new ArrayList<>();
                appendSymbols(lines, symbols, 0);
                return String.join("\n", lines);
            });
        }).orElseGet(() -> rejected("file is required"));
    }

    @POST
    @Path("/shutdown")
    @Blocking
    public RestResponse<LspReply> shutdown() {
        if (!enabled) {
            return disabled();
        }
        client.close();
        return RestResponse.status(RestResponse.Status.OK,
                new LspReply("SHUTDOWN", "the language server session was shut down", ""));
    }

    private static Optional<String> optionalFile(String file) {
        if (file == null || file.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(file);
    }

    /**
     * Resolves the capability over the read only registry: the same seam the
     * tool windows and the status bar consume, so the resolved provider and its
     * unavailable reasons stay consistent with the status surface.
     */
    private <T, V> RestResponse<LspReply> overPort(CapabilitySelection<T> selection,
            Function<T, CapabilityResult<V>> call, Function<V, String> rendering) {
        if (!selection.isAvailable()) {
            return RestResponse.status(RestResponse.Status.SERVICE_UNAVAILABLE, new LspReply("UNAVAILABLE",
                    ((CapabilitySelection.Unavailable<T>) selection).reason(), ""));
        }
        T provider = ((CapabilitySelection.Selected<T>) selection).provider();
        return reply(call.apply(provider), rendering);
    }

    private <V> RestResponse<LspReply> reply(CapabilityResult<V> result, Function<V, String> rendering) {
        if (result.isFailure()) {
            var failure = (CapabilityResult.Failed<V>) result;
            return RestResponse.status(RestResponse.Status.SERVICE_UNAVAILABLE,
                    new LspReply(upperCase(failure.failure().reason()), failure.failure().detail(), ""));
        }
        var ok = (CapabilityResult.Ok<V>) result;
        return RestResponse.status(RestResponse.Status.OK, new LspReply("OK", "", rendering.apply(ok.value())));
    }

    private static <V> String lines(List<V> values, Function<V, String> render) {
        return values.stream().map(render).collect(Collectors.joining("\n"));
    }

    private static String upperCase(String reason) {
        return reason.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static String problemLine(DiagnosticsCapability.Diagnostic value) {
        return value.filePath() + ":" + value.startLine() + ":" + value.startColumn() + " " + value.severity()
                + " " + value.message();
    }

    private static String callSiteLine(CallHierarchyCapability.CallSite value) {
        return value.symbol() + " at " + value.position().filePath() + ":" + value.position().line() + ":"
                + value.position().column();
    }

    private static String referenceLine(CodeReference reference) {
        return reference.targetFile() + ":" + reference.startLine() + ":" + reference.startColumn() + " to "
                + reference.endLine() + ":" + reference.endColumn();
    }

    private static String entryLine(CompletionEntry entry) {
        return entry.label() + (entry.detail().isBlank() ? "" : " (" + entry.detail() + ")");
    }

    private static void appendSymbols(List<String> lines, List<NavigationCapability.DocumentSymbol> symbols,
            int depth) {
        for (NavigationCapability.DocumentSymbol symbol : symbols) {
            lines.add("  ".repeat(depth) + symbol.name());
            if (symbol.children() != null && !symbol.children().isEmpty()) {
                appendSymbols(lines, symbol.children(), depth + 1);
            }
        }
    }

    private static RestResponse<LspReply> disabled() {
        return RestResponse.status(RestResponse.Status.NOT_FOUND, new LspReply("DISABLED", "", ""));
    }

    private static RestResponse<LspReply> rejected(String detail) {
        return RestResponse.status(RestResponse.Status.BAD_REQUEST, new LspReply("REJECTED", detail, ""));
    }

    /**
     * Payload of every answer: the classified state, its human readable detail
     * or the failure reason, and the rendered items, one per line.
     */
    public record LspReply(String state, String detail, String text) {
    }
}
