package dev.omatheusmesmo.vintelli.lsp;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jboss.logging.Logger;

/**
 * Language client callback the language server drives: records pushed
 * diagnostics so {@link LspClient} can serve the port from them, acknowledges
 * the server initiated requests that keep a real server such as jdt.ls going
 * and logs the window messages.
 */
final class VintelliLanguageClient implements LanguageClient {

    private static final Logger LOG = Logger.getLogger(VintelliLanguageClient.class);

    private final Map<String, List<Diagnostic>> diagnosticsByUri = new ConcurrentHashMap<>();
    private final Map<String, Integer> publicationCounts = new ConcurrentHashMap<>();
    private final Object publicationMonitor = new Object();

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams params) {
        diagnosticsByUri.put(params.getUri(), params.getDiagnostics() == null ? List.of() : params.getDiagnostics());
        synchronized (publicationMonitor) {
            publicationCounts.merge(params.getUri(), 1, Integer::sum);
            publicationMonitor.notifyAll();
        }
    }

    @Override
    public void telemetryEvent(Object object) {
        LOG.debug("Language server telemetry: " + object);
    }

    @Override
    public void showMessage(MessageParams messageParams) {
        LOG.log(severity(messageParams), "Language server message: " + messageParams.getMessage());
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams messageParams) {
        LOG.warn("Language server message request without an answer action: " + messageParams.getMessage());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {
        LOG.log(severity(message), "Language server log: " + message.getMessage());
    }

    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        LOG.debug("Language server registered capabilities " + params.getRegistrations());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
        LOG.debug("Language server unregistered capabilities " + params.getUnregisterations());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
        LOG.debug("Dropping an applied workspace edit: Vintelli does not host the edited documents");
        return CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(false));
    }

    @Override
    public CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void notifyProgress(ProgressParams params) {
        LOG.debug("Language server progress" + params.getToken());
    }

    int publicationCount(String uri) {
        return publicationCounts.getOrDefault(uri, 0);
    }

    boolean awaitPublicationAfter(String uri, int previous, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (publicationMonitor) {
            while (publicationCount(uri) <= previous) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(publicationMonitor, remaining);
            }
            return true;
        }
    }

    List<Diagnostic> knownDiagnostics(String uri) {
        return diagnosticsByUri.getOrDefault(uri, List.of());
    }

    private static Logger.Level severity(MessageParams message) {
        if (message.getType() == null) {
            return Logger.Level.INFO;
        }
        return switch (message.getType()) {
            case Error -> Logger.Level.ERROR;
            case Warning -> Logger.Level.WARN;
            case Info -> Logger.Level.INFO;
            default -> Logger.Level.DEBUG;
        };
    }
}
