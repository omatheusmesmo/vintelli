package dev.omatheusmesmo.vintelli.tui.symbol;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.WorkspaceSymbol;

/**
 * State of the Go to Class and Go to Symbol overlays: the query, the symbols the server answered for it and the
 * selected row. The query is sent once typing pauses for the debounce delay, on a virtual thread, and an answer is kept
 * only while its query is still the current one, so a late answer to an older query never replaces the rows of a newer
 * one. The matching is the server's: the query is passed on as typed.
 */
public final class SymbolSearchModel {

    static final Duration DEBOUNCE = Duration.ofMillis(150);

    /** What the overlay lists: types only for Go to Class, every kind for Go to Symbol. */
    public enum Scope {
        TYPES,
        ALL;

        boolean accepts(int kind) {
            return this == ALL || SymbolKinds.isType(kind);
        }
    }

    /** Answers one query; called on a worker thread, never on the render thread. */
    @FunctionalInterface
    public interface Source {
        CapabilityResult<List<WorkspaceSymbol>> symbols(String query);
    }

    public record View(Scope scope, String query, int selected, List<WorkspaceSymbol> symbols, boolean searching,
            String error) {
    }

    private final Source source;
    private final Duration debounce;
    private final AtomicLong generation = new AtomicLong();
    private Scope scope = Scope.ALL;
    private String query = "";
    private int selected;
    private List<WorkspaceSymbol> symbols = List.of();
    private boolean open;
    private boolean searching;
    private String error;

    public SymbolSearchModel(Source source) {
        this(source, DEBOUNCE);
    }

    SymbolSearchModel(Source source, Duration debounce) {
        this.source = Objects.requireNonNull(source, "source");
        this.debounce = Objects.requireNonNull(debounce, "debounce");
    }

    public synchronized void open(Scope scope) {
        generation.incrementAndGet();
        this.scope = Objects.requireNonNull(scope, "scope");
        query = "";
        selected = 0;
        symbols = List.of();
        error = null;
        searching = false;
        open = true;
    }

    public synchronized void close() {
        open = false;
        searching = false;
        generation.incrementAndGet();
    }

    public synchronized boolean isOpen() {
        return open;
    }

    public synchronized void type(int codePoint) {
        if (Character.isValidCodePoint(codePoint) && !Character.isISOControl(codePoint)) {
            query += Character.toString(codePoint);
            refresh();
        }
    }

    public synchronized void backspace() {
        if (!query.isEmpty()) {
            int end = query.offsetByCodePoints(query.length(), -1);
            query = query.substring(0, end);
            refresh();
        }
    }

    public synchronized void move(int delta) {
        selected = Math.clamp(selected + delta, 0, Math.max(0, symbols.size() - 1));
    }

    /** @return the selected symbol, or null when no row is selected */
    public synchronized WorkspaceSymbol selectedSymbol() {
        return selected < symbols.size() ? symbols.get(selected) : null;
    }

    public synchronized View view() {
        return new View(scope, query, selected, List.copyOf(symbols), searching, error);
    }

    private void refresh() {
        selected = 0;
        request();
    }

    private void request() {
        long request = generation.incrementAndGet();
        symbols = List.of();
        error = null;
        searching = open && !query.isEmpty();
        if (!searching) {
            return;
        }
        String requested = query;
        Thread.ofVirtual().name("vintelli-symbol-search").start(() -> answer(request, requested));
    }

    private void answer(long request, String requested) {
        try {
            Thread.sleep(debounce);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        if (generation.get() != request) {
            return;
        }
        CapabilityResult<List<WorkspaceSymbol>> result = fetch(requested);
        synchronized (this) {
            if (!open || generation.get() != request) {
                return;
            }
            searching = false;
            if (result instanceof CapabilityResult.Ok<List<WorkspaceSymbol>> ok) {
                symbols = ok.value().stream().filter(symbol -> scope.accepts(symbol.kind())).toList();
                selected = Math.clamp(selected, 0, Math.max(0, symbols.size() - 1));
            } else {
                error = describe(result.failureOf().orElseThrow());
            }
        }
    }

    private CapabilityResult<List<WorkspaceSymbol>> fetch(String requested) {
        try {
            return source.symbols(requested);
        } catch (RuntimeException failure) {
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return CapabilityResult.failed(new CapabilityFailure("failed", reason));
        }
    }

    private static String describe(CapabilityFailure failure) {
        return failure.detail().isBlank() ? failure.reason() : failure.detail();
    }
}
