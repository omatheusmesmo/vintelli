package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.tamboui.widgets.input.TextAreaState;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/** One document owns its cursor, undo history and highlighting cache. Guarded by BuiltinEditor. */
public final class EditorDocument {
    private static final int HISTORY_LIMIT = 200;
    private record Revision(String text, int row, int column, List<Diagnostic> diagnostics) {}

    private final Path file;
    private final TextAreaState state;
    private final EditorSyntaxHighlighter highlighter = new EditorSyntaxHighlighter();
    private final Deque<Revision> undo = new ArrayDeque<>();
    private final Deque<Revision> redo = new ArrayDeque<>();
    private final String newline;
    private String savedText;
    /** Exact on-disk contents last opened/saved; null means this is a recovered missing file. */
    private String savedDiskText;
    private List<Diagnostic> diagnostics = List.of();
    /** While folded, {@code view} is the widget-facing copy of {@code state} with the import block replaced by its placeholder. */
    private ImportFold fold;
    private TextAreaState view;

    EditorDocument(Path file, String text) {
        this(file, text, text.replace("\r\n", "\n"));
    }

    EditorDocument(Path file, String text, String savedText) {
        this.file = file;
        newline = text.contains("\r\n") ? "\r\n" : "\n";
        this.savedText = savedText;
        savedDiskText = savedText == null ? null : text;
        state = new TextAreaState(text.replace("\r\n", "\n"));
    }

    public Path file() { return file; }
    public String text() { return state.text(); }
    public int row() { return state.cursorRow(); }
    public int column() { return state.cursorCol(); }
    public String lineSeparator() { return newline; }
    public boolean dirty() { return savedText == null || !savedText.equals(text()); }
    public List<Diagnostic> diagnostics() { return diagnostics; }
    TextAreaState state() { return view != null ? view : state; }
    public int lineCount() { return state.lineCount(); }
    public boolean importsFolded() { return fold != null; }
    int documentRow(int viewRow) { return fold == null ? viewRow : fold.toDocument(viewRow); }

    boolean hasDiagnosticAt(int viewRow) {
        int first = documentRow(viewRow);
        int span = fold != null && viewRow == fold.start() ? fold.count() : 1;
        return diagnostics.stream().anyMatch(value -> value.startLine() >= first && value.startLine() < first + span);
    }
    EditorSyntaxHighlighter highlighter() { return highlighter; }

    void mutate(Runnable edit) {
        Revision before = revision();
        edit.run();
        if (fold != null) { syncFold(); }
        if (!before.text().equals(text())) {
            undo.push(before);
            while (undo.size() > HISTORY_LIMIT) { undo.removeLast(); }
            redo.clear();
            remapDiagnostics(before.text(), text());
        }
    }

    public boolean undo() { return restore(undo, redo); }
    public boolean redo() { return restore(redo, undo); }

    private boolean restore(Deque<Revision> from, Deque<Revision> to) {
        if (from.isEmpty()) { return false; }
        to.push(revision());
        Revision revision = from.pop();
        boolean folded = fold != null;
        dropFold();
        state.setText(revision.text());
        position(revision.row(), revision.column());
        diagnostics(revision.diagnostics());
        if (folded) { foldImports(); }
        return true;
    }

    private Revision revision() { return new Revision(text(), row(), column(), diagnostics); }

    void position(int row, int column) {
        if (fold != null && fold.hides(row)) { dropFold(); }
        place(state, row, column);
        if (fold != null) { place(view, fold.toView(row), fold.viewColumn(row, column)); }
    }

    private static void place(TextAreaState target, int row, int column) {
        target.moveCursorToStart();
        for (int i = 0; i < Math.clamp(row, 0, target.lineCount() - 1); i++) { target.moveCursorDown(); }
        while (target.cursorCol() < column) {
            int previous = target.cursorCol();
            target.moveCursorRight();
            if (target.cursorCol() <= previous) { target.moveCursorLeft(); break; }
        }
    }

    /** Collapses the import block when expanded and expands it when collapsed; false when there is nothing to collapse. */
    boolean toggleImportFold() {
        if (fold != null) { dropFold(); return true; }
        return foldImports();
    }

    private boolean foldImports() {
        fold = ImportFold.detect(text()).orElse(null);
        if (fold == null) { return false; }
        String[] lines = text().split("\n", -1);
        List<String> shown = new ArrayList<>(Arrays.asList(lines).subList(0, fold.start()));
        shown.add(fold.placeholder());
        shown.addAll(Arrays.asList(lines).subList(fold.start() + fold.count(), lines.length));
        view = new TextAreaState(String.join("\n", shown));
        place(view, fold.toView(row()), fold.viewColumn(row(), column()));
        paintDiagnostics();
        return true;
    }

    private void dropFold() {
        fold = null;
        view = null;
        paintDiagnostics();
    }

    /** Writes the widget's edits of the folded view back into the full text; an edit that damages the placeholder expands the block and is discarded. */
    private void syncFold() {
        String[] shown = view.text().split("\n", -1);
        String placeholder = fold.placeholder();
        int at = -1;
        for (int i = 0; i < shown.length; i++) {
            if (shown[i].equals(placeholder)) {
                if (at >= 0) { at = -1; break; }
                at = i;
            }
        }
        if (at < 0) { dropFold(); return; }
        String[] full = state.text().split("\n", -1);
        List<String> merged = new ArrayList<>(Arrays.asList(shown).subList(0, at));
        merged.addAll(Arrays.asList(full).subList(fold.start(), fold.start() + fold.count()));
        merged.addAll(Arrays.asList(shown).subList(at + 1, shown.length));
        ImportFold moved = new ImportFold(at, fold.count(), fold.statements());
        String text = String.join("\n", merged);
        if (!text.equals(state.text())) { state.setText(text); }
        fold = moved;
        place(state, moved.toDocument(view.cursorRow()), moved.documentColumn(view.cursorRow(), view.cursorCol()));
    }

    boolean find(String query) {
        if (query == null || query.isEmpty()) { return false; }
        String content = text();
        int offset = 0;
        for (int row = 0; row < row(); row++) { offset += state.getLine(row).length() + 1; }
        int match = content.indexOf(query, Math.min(content.length(), offset + column() + 1));
        if (match < 0) { match = content.indexOf(query); }
        if (match < 0) { return false; }
        String prefix = content.substring(0, match);
        int row = (int) prefix.chars().filter(c -> c == '\n').count();
        int lastBreak = prefix.lastIndexOf('\n');
        position(row, match - lastBreak - 1);
        return true;
    }

    void save() throws IOException {
        String diskText = text().replace("\n", newline);
        boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
        if (savedDiskText == null) {
            if (exists) { throw externalChange(); }
            // CREATE_NEW ensures a file that reappears after the check is never truncated.
            try (var output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                output.write(diskText.getBytes(StandardCharsets.UTF_8));
            }
        } else {
            if (!exists || !readExisting().equals(savedDiskText)) {
                throw externalChange();
            }
            try (var output = Files.newOutputStream(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS)) {
                output.write(diskText.getBytes(StandardCharsets.UTF_8));
            }
        }
        savedDiskText = diskText;
        savedText = text();
    }

    private static IOException externalChange() {
        return new IOException("File changed outside the editor; reload or save your changes elsewhere");
    }

    private String readExisting() throws IOException {
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                var input = Channels.newInputStream(channel)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    void diagnostics(List<Diagnostic> values) {
        diagnostics = List.copyOf(values);
        paintDiagnostics();
    }

    private void paintDiagnostics() {
        highlighter.setDiagnostics(diagnostics.stream().map(value -> new EditorSyntaxHighlighter.Diagnostic(
                fold == null ? value.startLine() : fold.toView(value.startLine()), switch (value.severity()) {
                    case ERROR -> EditorSyntaxHighlighter.Severity.ERROR;
                    case WARNING -> EditorSyntaxHighlighter.Severity.WARNING;
                    default -> EditorSyntaxHighlighter.Severity.INFO;
                })).toList());
    }

    private void remapDiagnostics(String before, String after) {
        String[] oldLines = before.split("\n", -1);
        String[] newLines = after.split("\n", -1);
        int prefix = 0;
        while (prefix < Math.min(oldLines.length, newLines.length)
                && oldLines[prefix].equals(newLines[prefix])) { prefix++; }
        int delta = newLines.length - oldLines.length;
        if (delta == 0) { return; }
        List<Diagnostic> mapped = new ArrayList<>();
        for (Diagnostic diagnostic : diagnostics) {
            int start = diagnostic.startLine() >= prefix ? Math.max(prefix, diagnostic.startLine() + delta) : diagnostic.startLine();
            int end = diagnostic.endLine() >= prefix ? Math.max(start, diagnostic.endLine() + delta) : diagnostic.endLine();
            mapped.add(new Diagnostic(diagnostic.filePath(), start, diagnostic.startColumn(), end,
                    diagnostic.endColumn(), diagnostic.severity(), diagnostic.source(), diagnostic.message()));
        }
        diagnostics(mapped);
    }
}
