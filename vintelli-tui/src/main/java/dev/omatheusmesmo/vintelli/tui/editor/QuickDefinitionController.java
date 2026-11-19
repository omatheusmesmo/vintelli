package dev.omatheusmesmo.vintelli.tui.editor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.DocumentSymbol;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.Location;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.SourcePosition;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;

/**
 * Runs the quick definition popup: resolves the declaration of the symbol at the caret through the navigation
 * capability on a virtual thread, reads its file from the open document or the read file capability, and hands the
 * declaration text to the render thread through {@code deliver}, which draws it through {@link QuickDefinitionOverlay}.
 * The editor keeps its caret, scroll and tabs: the popup only peeks, and opens the file for real when the shell asks
 * for it with the {@link Target} that {@link #press(OverlayKey)} and {@link #accept()} hand back. An answer that
 * arrives after the caret or the text moved is dropped: the popup closes and nothing is shown for the old position.
 */
public final class QuickDefinitionController {

    private static final int MAX_TARGETS = 20;
    private static final String LIBRARY_SCHEME = "jdt:";

    /**
     * The declaration the popup opens for real.
     *
     * @param file the file of the declaration
     * @param line zero based line the target points at
     */
    public record Target(Path file, int line) {
    }

    private final Supplier<NavigationCapability> navigation;
    private final CapabilityRegistry registry;
    private final BuiltinEditor editor;
    private final Consumer<Runnable> deliver;
    private final Consumer<String> status;
    private final QuickDefinitionMenu menu = new QuickDefinitionMenu();

    /**
     * @param navigation the available navigation provider, null when there is none
     * @param deliver hands an update to the render thread, which applies it on its next pass
     * @param status receives the line the status bar shows
     */
    public QuickDefinitionController(Supplier<NavigationCapability> navigation, CapabilityRegistry registry,
            BuiltinEditor editor, Consumer<Runnable> deliver, Consumer<String> status) {
        this.navigation = navigation;
        this.registry = registry;
        this.editor = editor;
        this.deliver = deliver;
        this.status = status;
    }

    /**
     * Opens the popup in the loading state for the document site and resolves the declaration off the render thread.
     */
    public void open(CompletionSite site) {
        long ticket = menu.begin();
        Thread.ofVirtual().name("vintelli-quick-definition").start(() -> {
            CapabilityResult<List<QuickDefinitionMenu.Peek>> result = fetch(site);
            deliver.accept(() -> receive(ticket, site, result));
        });
    }

    public void close() {
        menu.close();
    }

    public boolean isOpen() {
        return menu.isOpen();
    }

    /**
     * @return whether the open popup takes the key; a key it does not take closes the popup and reaches the editor
     */
    public boolean takes(OverlayKey key) {
        return menu.takes(key);
    }

    /**
     * @return the declaration to open when the key opens it, which also closes the popup
     */
    public Optional<Target> press(OverlayKey key) {
        return menu.press(key).map(QuickDefinitionController::targetOf);
    }

    /**
     * Opens the shown declaration, the way Enter does: the popup closes and the declaration is handed back.
     */
    public Optional<Target> accept() {
        return menu.accept().map(QuickDefinitionController::targetOf);
    }

    public void next() {
        menu.next();
    }

    public void previous() {
        menu.previous();
    }

    public void wheel(int direction) {
        menu.wheel(direction);
    }

    QuickDefinitionMenu.View view() {
        return menu.view();
    }

    void layout(OverlayRegion inner) {
        menu.layout(inner);
    }

    private static Target targetOf(QuickDefinitionMenu.Peek peek) {
        return new Target(peek.file(), peek.line());
    }

    private CapabilityResult<List<QuickDefinitionMenu.Peek>> fetch(CompletionSite site) {
        try {
            NavigationCapability provider = navigation.get();
            if (provider == null) {
                return failed("Quick definition unavailable", "Navigation provider unavailable");
            }
            CapabilityResult<List<Location>> found = provider
                    .definition(new SourcePosition(site.file(), site.row(), site.column()));
            if (found instanceof CapabilityResult.Failed<List<Location>> failure) {
                return CapabilityResult.failed(failure.failure());
            }
            List<Location> locations = ((CapabilityResult.Ok<List<Location>>) found).value();
            if (locations.isEmpty()) {
                return failed("No definition found", "");
            }
            return peeksOf(provider, locations.stream().limit(MAX_TARGETS).toList());
        } catch (RuntimeException failure) {
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return failed("Quick definition failed", reason);
        }
    }

    private CapabilityResult<List<QuickDefinitionMenu.Peek>> peeksOf(NavigationCapability provider,
            List<Location> locations) {
        List<QuickDefinitionMenu.Peek> peeks = new ArrayList<>();
        CapabilityFailure firstFailure = null;
        for (Location location : locations) {
            CapabilityResult<QuickDefinitionMenu.Peek> peek = peekOf(provider, location);
            if (peek instanceof CapabilityResult.Ok<QuickDefinitionMenu.Peek> ok) {
                peeks.add(ok.value());
            } else if (firstFailure == null) {
                firstFailure = peek.failureOf().orElseThrow();
            }
        }
        return peeks.isEmpty() ? CapabilityResult.failed(firstFailure) : CapabilityResult.ok(peeks);
    }

    private CapabilityResult<QuickDefinitionMenu.Peek> peekOf(NavigationCapability provider, Location location) {
        Path file = location.filePath();
        if (file.toString().startsWith(LIBRARY_SCHEME)) {
            return failed("Quick definition unavailable", file.getFileName()
                    + " is a library or JDK source the server serves as a jdt:// location, which the editor cannot read");
        }
        CapabilityResult<String> text = textOf(file);
        if (text instanceof CapabilityResult.Failed<String> failure) {
            return CapabilityResult.failed(failure.failure());
        }
        List<String> lines = List.of(((CapabilityResult.Ok<String>) text).value().split("\r?\n", -1));
        QuickDefinitionRange range = QuickDefinitionRange.of(symbolsOf(provider, file), location.line(),
                location.column(), lines.size());
        return CapabilityResult.ok(new QuickDefinitionMenu.Peek(file, Math.clamp(location.line(), 0, lines.size() - 1),
                range.start(), lines.subList(range.start(), range.end() + 1).stream()
                        .map(line -> line.replace("\t", "    ")).toList()));
    }

    private List<DocumentSymbol> symbolsOf(NavigationCapability provider, Path file) {
        CapabilityResult<List<DocumentSymbol>> outline = provider.documentSymbols(file);
        return outline instanceof CapabilityResult.Ok<List<DocumentSymbol>> ok ? ok.value() : List.of();
    }

    private CapabilityResult<String> textOf(Path file) {
        Optional<String> open = editor == null ? Optional.empty() : editor.openText(file);
        if (open.isPresent()) {
            return CapabilityResult.ok(open.get());
        }
        if (registry == null) {
            return failed("Quick definition unavailable", "capability registry is not configured");
        }
        CapabilitySelection<ReadFileCapability> chosen = registry.readFile();
        return switch (chosen) {
            case CapabilitySelection.Unavailable<ReadFileCapability> unavailable ->
                    failed("Quick definition unavailable", unavailable.reason());
            case CapabilitySelection.Selected<ReadFileCapability> selected -> selected.provider().readText(file);
        };
    }

    private static <T> CapabilityResult<T> failed(String reason, String detail) {
        return CapabilityResult.failed(new CapabilityFailure(reason, detail));
    }

    private void receive(long ticket, CompletionSite requested, CapabilityResult<List<QuickDefinitionMenu.Peek>> result) {
        if (!menu.awaits(ticket)) {
            return;
        }
        if (!isCurrent(requested)) {
            close();
            return;
        }
        if (result instanceof CapabilityResult.Ok<List<QuickDefinitionMenu.Peek>> ok) {
            menu.showPeeks(ticket, ok.value());
            status.accept("Definition at " + ok.value().getFirst().title());
            return;
        }
        CapabilityFailure failure = result.failureOf().orElseThrow();
        String reason = failure.detail().isBlank() ? failure.reason() : failure.reason() + ": " + failure.detail();
        menu.showFailure(ticket, reason);
        status.accept(reason);
    }

    private boolean isCurrent(CompletionSite requested) {
        Optional<CompletionSite> now = editor == null ? Optional.empty() : editor.completionSite();
        return now.isPresent() && now.get().equals(requested);
    }
}
