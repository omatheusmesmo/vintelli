package dev.omatheusmesmo.vintelli.tui.editor;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.omatheusmesmo.vintelli.api.ShellEditorPalette;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.SourceRange;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.DocumentHighlight;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.DocumentHighlight.Kind;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.SourcePosition;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.ColorConverter;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;
import dev.tamboui.text.CharWidth;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.widgets.input.TextAreaState;

/**
 * Marks every occurrence of the symbol at the caret in the open file, as IntelliJ does. The editor reports each frame
 * through {@link #observe}; once the caret has rested for the debounce time on an identifier, the navigation provider
 * is asked on a virtual thread and the answer goes to the render thread through {@code deliver}. The occurrences are
 * painted over the text with the {@code editor-usage-read} and {@code editor-usage-write} roles, and only on cells
 * that still have the plain editor background, so the selection, the diagnostics and the find matches keep their look.
 * An answer is dropped when the caret or the text is no longer the one it was asked for. The highlight is cleared when
 * the text changes or the caret leaves every occurrence.
 */
public final class UsageHighlightController {

    static final Duration DEBOUNCE = Duration.ofMillis(300);
    private static final String READ_ROLE = "editor-usage-read";
    private static final String WRITE_ROLE = "editor-usage-write";

    private final Supplier<NavigationCapability> navigation;
    private final BuiltinEditor editor;
    private final Consumer<Runnable> deliver;
    private final Duration debounce;
    private final AtomicLong ticket = new AtomicLong();
    private CompletionSite observed;
    private CompletionSite shownFor;
    private List<DocumentHighlight> shown = List.of();

    /**
     * @param navigation the provider that serves document highlights, or null while none is available
     * @param deliver hands an update to the render thread, which applies it on its next pass
     */
    public UsageHighlightController(Supplier<NavigationCapability> navigation, BuiltinEditor editor,
            Consumer<Runnable> deliver) {
        this(navigation, editor, deliver, DEBOUNCE);
    }

    UsageHighlightController(Supplier<NavigationCapability> navigation, BuiltinEditor editor,
            Consumer<Runnable> deliver, Duration debounce) {
        this.navigation = navigation;
        this.editor = editor;
        this.deliver = deliver;
        this.debounce = debounce;
    }

    /**
     * Reports the document the editor draws this frame. Nothing happens while the caret and the text stay as they
     * were; a caret that moved inside the shown occurrences keeps them, any other change clears them and starts the
     * debounce for the new caret.
     */
    void observe(EditorDocument document) {
        synchronized (editor) {
            CompletionSite now = document == null ? null
                    : new CompletionSite(document.file(), document.text(), document.row(), document.column());
            if (Objects.equals(now, observed)) {
                return;
            }
            observed = now;
            if (now != null && keeps(now)) {
                return;
            }
            long issued = ticket.incrementAndGet();
            shown = List.of();
            shownFor = null;
            if (now != null && onIdentifier(now)) {
                schedule(issued, now);
            }
        }
    }

    /**
     * Paints the shown occurrences of {@code document} that are inside the viewport.
     */
    void paint(Buffer buffer, Rect area, EditorDocument document, RenderContext context) {
        synchronized (editor) {
            if (shown.isEmpty() || area.isEmpty() || !document.file().equals(shownFor.file())) {
                return;
            }
            Style read = EditorElement.roleStyle(context, READ_ROLE);
            Style write = EditorElement.roleStyle(context, WRITE_ROLE);
            ShellEditorPalette palette = editor.palette();
            Color plain = ColorConverter.INSTANCE.convert(palette.background()).orElse(null);
            for (DocumentHighlight highlight : shown) {
                paint(buffer, area, document, highlight.range(), highlight.kind() == Kind.WRITE ? write : read, plain);
            }
        }
    }

    private static void paint(Buffer buffer, Rect area, EditorDocument document, SourceRange range, Style style,
            Color plain) {
        TextAreaState state = document.state();
        for (int row = range.startLine(); row <= range.endLine(); row++) {
            int viewRow = document.viewRow(row);
            int screenRow = viewRow - state.scrollRow();
            if (viewRow < 0 || screenRow < 0 || screenRow >= area.height()) {
                continue;
            }
            String line = state.getLine(viewRow);
            int from = Math.clamp(state.scrollCol(), 0, line.length());
            int first = row == range.startLine() ? range.startColumn() : 0;
            int last = row == range.endLine() ? range.endColumn() : line.length();
            int start = CharWidth.of(line.substring(from, Math.clamp(first, from, line.length())));
            int end = CharWidth.of(line.substring(from, Math.clamp(last, from, line.length())));
            int y = area.y() + screenRow;
            for (int x = start; x < end && x < area.width(); x++) {
                Cell cell = buffer.get(area.x() + x, y);
                Color background = cell.style().bg().orElse(null);
                boolean selected = cell.style().addModifiers().contains(Modifier.REVERSED);
                if (!selected && (background == null || background.equals(plain))) {
                    buffer.set(area.x() + x, y, cell.patchStyle(style));
                }
            }
        }
    }

    private boolean keeps(CompletionSite now) {
        return !shown.isEmpty() && shownFor.file().equals(now.file()) && shownFor.text().equals(now.text())
                && shown.stream().anyMatch(highlight -> covers(highlight.range(), now.row(), now.column()));
    }

    private static boolean covers(SourceRange range, int row, int column) {
        boolean afterStart = row > range.startLine() || row == range.startLine() && column >= range.startColumn();
        boolean beforeEnd = row < range.endLine() || row == range.endLine() && column <= range.endColumn();
        return afterStart && beforeEnd;
    }

    private static boolean onIdentifier(CompletionSite site) {
        String line = site.line();
        int column = Math.min(site.column(), line.length());
        return column < line.length() && Character.isJavaIdentifierPart(line.charAt(column))
                || column > 0 && Character.isJavaIdentifierPart(line.charAt(column - 1));
    }

    private void schedule(long issued, CompletionSite site) {
        Thread.ofVirtual().name("vintelli-usage-highlights").start(() -> {
            try {
                Thread.sleep(debounce);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (ticket.get() != issued) {
                return;
            }
            CapabilityResult<List<DocumentHighlight>> result = fetch(site);
            deliver.accept(() -> receive(issued, site, result));
        });
    }

    private CapabilityResult<List<DocumentHighlight>> fetch(CompletionSite site) {
        try {
            NavigationCapability provider = navigation.get();
            if (provider == null) {
                return CapabilityResult.failed(new CapabilityFailure("unavailable", "no navigation provider"));
            }
            return provider.documentHighlights(new SourcePosition(site.file(), site.row(), site.column()));
        } catch (RuntimeException failure) {
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return CapabilityResult.failed(new CapabilityFailure("failed", reason));
        }
    }

    private void receive(long issued, CompletionSite requested, CapabilityResult<List<DocumentHighlight>> result) {
        synchronized (editor) {
            if (ticket.get() != issued || editor.completionSite().filter(requested::equals).isEmpty()) {
                return;
            }
            if (result instanceof CapabilityResult.Ok<List<DocumentHighlight>> ok) {
                shown = ok.value();
                shownFor = requested;
            }
        }
    }
}
