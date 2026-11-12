package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
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
import java.util.Map;

/** One document owns its cursor, undo history and highlighting cache. Guarded by BuiltinEditor. */
public final class EditorDocument {
    private static final int HISTORY_LIMIT = 200;
    private record Revision(String text, int row, int column, List<Diagnostic> diagnostics,
            Map<Integer, DebugCapability.SourceBreakpoint> breakpoints) {}

    /**
     * Breakpoints of this document's file, kept with the undo history: every edit shifts them,
     * and each revision restores the set it was recorded with.
     */
    interface Breakpoints {
        Breakpoints NONE = new Breakpoints() {
            @Override public Map<Integer, DebugCapability.SourceBreakpoint> snapshot() { return Map.of(); }
            @Override public void restore(Map<Integer, DebugCapability.SourceBreakpoint> snapshot) { }
            @Override public void edited(String before, String after) { }
        };

        Map<Integer, DebugCapability.SourceBreakpoint> snapshot();
        void restore(Map<Integer, DebugCapability.SourceBreakpoint> snapshot);
        void edited(String before, String after);
    }

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
    private Breakpoints breakpoints = Breakpoints.NONE;
    /** While folded, {@code view} is the widget-facing copy of {@code state} with the import block replaced by its placeholder. */
    private ImportFold fold;
    private TextAreaState view;
    /** Pointer and keyboard selection in document rows and columns; null when nothing is selected. */
    private SelectionCursor selection;
    /** Set when the caret moved or the text changed, so the next frame brings the caret into view. */
    private boolean revealPending = true;

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
    int viewRow(int documentRow) { return fold == null ? documentRow : fold.hides(documentRow) ? -1 : fold.toView(documentRow); }

    boolean hasDiagnosticAt(int viewRow) {
        int first = documentRow(viewRow);
        int span = fold != null && viewRow == fold.start() ? fold.count() : 1;
        return diagnostics.stream().anyMatch(value -> value.startLine() >= first && value.startLine() < first + span);
    }
    EditorSyntaxHighlighter highlighter() { return highlighter; }

    void breakpoints(Breakpoints value) { breakpoints = value; }

    void mutate(Runnable edit) {
        Revision before = revision();
        edit.run();
        if (fold != null) { syncFold(); }
        if (!before.text().equals(text())) {
            undo.push(before);
            while (undo.size() > HISTORY_LIMIT) { undo.removeLast(); }
            redo.clear();
            remapDiagnostics(before.text(), text());
            breakpoints.edited(before.text(), text());
            clearSelection();
            revealPending = true;
        }
    }

    /** Copies the caret line below itself and moves the caret to the copy, as one undoable step. */
    void duplicateLine() {
        mutate(() -> {
            int row = row();
            int column = column();
            boolean folded = fold != null;
            dropFold();
            List<String> lines = new ArrayList<>(Arrays.asList(state.text().split("\n", -1)));
            lines.add(row + 1, lines.get(row));
            state.setText(String.join("\n", lines));
            position(row + 1, column);
            if (folded) { foldImports(); }
        });
    }

    /** Replaces the whole text as one undoable step; the cursor keeps its place when the text still has it. */
    void replaceText(String replacement) {
        mutate(() -> {
            boolean folded = fold != null;
            int row = row();
            int column = column();
            dropFold();
            state.setText(replacement);
            position(row, column);
            if (folded) { foldImports(); }
        });
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
        breakpoints.restore(revision.breakpoints());
        if (folded) { foldImports(); }
        return true;
    }

    private Revision revision() { return new Revision(text(), row(), column(), diagnostics, breakpoints.snapshot()); }

    void position(int row, int column) {
        if (fold != null && fold.hides(row)) { dropFold(); }
        placeCaret(row, column);
    }

    private void placeCaret(int row, int column) {
        place(state, row, column);
        if (fold != null) { place(view, fold.toView(row), fold.viewColumn(row, column)); }
        revealPending = true;
    }

    /** Moves the caret to a document row and column, clamped to the text, and drops a selection the move leaves behind. */
    void goTo(int row, int column) {
        int targetRow = Math.clamp(row, 0, state.lineCount() - 1);
        position(targetRow, Math.clamp(column, 0, state.getLine(targetRow).length()));
        dropPointerSelection();
    }

    /** Places the caret at a row and character column of the widget-facing view, as a pointer click does. */
    void placeView(int viewRow, int viewColumn) {
        if (fold == null) {
            place(state, viewRow, viewColumn);
            return;
        }
        place(view, viewRow, viewColumn);
        int viewRowPlaced = view.cursorRow();
        place(state, fold.toDocument(viewRowPlaced), fold.documentColumn(viewRowPlaced, view.cursorCol()));
    }

    void pointerPress(int viewRow, int viewColumn) {
        placeView(viewRow, viewColumn);
        selection = SelectionCursor.collapsed(row(), column());
        pushSelection();
    }

    void pointerDrag(int viewRow, int viewColumn) {
        placeView(viewRow, viewColumn);
        SelectionCursor anchored = selection == null ? SelectionCursor.collapsed(row(), column()) : selection;
        selection = anchored.extendTo(row(), column());
        pushSelection();
    }

    /**
     * Runs a caret movement on the widget-facing state and extends the selection to the new caret,
     * keeping the anchor where the selection started. The move is not an edit: no undo step.
     */
    void extendSelection(Runnable move) {
        SelectionCursor anchored = selection == null ? SelectionCursor.collapsed(row(), column()) : selection;
        move.run();
        if (fold != null) { syncFold(); }
        selection = anchored.extendTo(row(), column());
        revealPending = true;
        pushSelection();
    }

    void extendSelectionByPage(boolean down, int rows) {
        extendSelection(() -> {
            for (int step = 0; step < rows; step++) {
                if (down) {
                    state().moveCursorDown();
                } else {
                    state().moveCursorUp();
                }
            }
        });
    }

    /** Selects the whole document: the anchor is the start of the text and the caret rests at its end. */
    void selectAll() {
        int last = state.lineCount() - 1;
        int lastColumn = state.getLine(last).length();
        selection = new SelectionCursor(0, 0, last, lastColumn);
        placeCaret(last, lastColumn);
        pushSelection();
    }

    SelectionCursor selection() {
        return selection == null ? SelectionCursor.collapsed(row(), column()) : selection;
    }

    String selectedText() {
        if (selection == null || selection.isEmpty()) { return ""; }
        StringBuilder text = new StringBuilder();
        for (int row = selection.startRow(); row <= selection.endRow(); row++) {
            String line = state.getLine(row);
            int from = row == selection.startRow() ? selection.startCol() : 0;
            int to = row == selection.endRow() ? selection.endCol() : line.length();
            if (row > selection.startRow()) { text.append('\n'); }
            text.append(line, Math.min(from, line.length()), Math.min(to, line.length()));
        }
        return text.toString();
    }

    boolean hasSelection() {
        return selection != null && !selection.isEmpty();
    }

    /** Replaces the selection, or inserts at the caret when nothing is selected, as one undoable step. */
    void replaceSelection(String replacement) {
        mutate(() -> splice(replacement));
    }

    /**
     * Removes the selected text inside an edit that is already a single undo step, so a typed
     * character or Backspace applies to the selection instead of the characters around the caret.
     */
    void deleteSelectionWithinEdit() {
        if (hasSelection()) { splice(""); }
    }

    private void splice(String replacement) {
        SelectionCursor range = hasSelection() ? selection : SelectionCursor.collapsed(row(), column());
        int start = offsetOf(range.startRow(), range.startCol());
        int end = offsetOf(range.endRow(), range.endCol());
        String text = state.text();
        String updated = text.substring(0, start) + replacement + text.substring(end);
        int caret = start + replacement.length();
        int caretRow = (int) updated.substring(0, caret).chars().filter(c -> c == '\n').count();
        int caretColumn = caret - (updated.lastIndexOf('\n', caret - 1) + 1);
        boolean folded = fold != null;
        dropFold();
        state.setText(updated);
        position(caretRow, caretColumn);
        if (folded) { foldImports(); }
    }

    private int offsetOf(int row, int column) {
        int offset = 0;
        for (int line = 0; line < row; line++) { offset += state.getLine(line).length() + 1; }
        return offset + Math.min(column, state.getLine(row).length());
    }

    void clearSelection() {
        selection = null;
        pushSelection();
    }

    /** Ends a pointer selection once the caret left it; a selection set on the highlighter alone stays. */
    void dropPointerSelection() {
        if (selection != null) { clearSelection(); }
    }

    private void pushSelection() {
        SelectionCursor shown = selection == null ? SelectionCursor.collapsed(0, 0)
                : fold == null ? selection : fold.toView(selection);
        highlighter.setSelection(shown);
    }

    void scrollBy(int rows, int visibleRows) {
        if (rows < 0) {
            state().scrollUp(-rows);
        } else {
            state().scrollDown(rows, visibleRows);
        }
    }

    int scrollRow() { return state().scrollRow(); }
    int scrollCol() { return state().scrollCol(); }

    void requestReveal() { revealPending = true; }

    boolean consumeReveal() {
        boolean pending = revealPending;
        revealPending = false;
        return pending;
    }

    /**
     * Runs the widget paint with the caret parked inside the viewport when the caret sits outside it,
     * so the widget's own follow-the-caret step cannot scroll a viewport that was moved away from the
     * caret. The caret is restored afterwards; nothing else about the document changes.
     */
    void paintViewport(int visibleRows, Runnable paint) {
        TextAreaState target = state();
        int row = target.cursorRow();
        int top = target.scrollRow();
        if (row >= top && row < top + visibleRows) {
            paint.run();
            return;
        }
        int column = target.cursorCol();
        int parkRow = top;
        for (int candidate = top; candidate < Math.min(top + visibleRows, target.lineCount()); candidate++) {
            if (target.getLine(candidate).length() >= target.scrollCol()) {
                parkRow = candidate;
                break;
            }
        }
        place(target, parkRow, target.scrollCol());
        try {
            paint.run();
        } finally {
            place(target, row, column);
        }
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
        clearSelection();
        revealPending = true;
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
        clearSelection();
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
