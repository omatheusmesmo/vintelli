package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.DocumentEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.TextEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.UnsupportedChange;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Applies a {@link WorkspaceEdit} to the open document as one undoable step.
 * Text edits are located with LSP positions (zero based lines, columns in UTF-16
 * code units, which is the native unit of the document text), validated for
 * overlaps, and applied from the end of the document so that earlier offsets
 * stay valid. The whole edit is computed before the document changes, so a
 * rejected edit leaves the document untouched.
 */
public final class WorkspaceEditApplier {

    private WorkspaceEditApplier() {
    }

    /**
     * Outcome of one apply: the edit either changed the document or was rejected
     * with the reason the user can read.
     */
    public sealed interface Result permits Applied, Rejected {
    }

    /**
     * @param editCount number of text edits that were applied
     */
    public record Applied(int editCount) implements Result {
    }

    /**
     * @param reason why the edit was not applied, the document is unchanged
     */
    public record Rejected(String reason) implements Result {
    }

    /**
     * @param document the open document the edit applies to
     * @param edit the edit a code action carries
     * @return whether the edit was applied; resource operations and edits of other
     *         documents are rejected because the editor cannot apply them yet
     */
    public static Result apply(EditorDocument document, WorkspaceEdit edit) {
        String refusal = refusal(document.file(), edit);
        if (refusal != null) {
            return new Rejected(refusal);
        }
        List<TextEdit> edits = textEditsOf(edit);
        if (edits.isEmpty()) {
            return new Applied(0);
        }
        try {
            document.replaceText(applyTo(document.text(), edits));
        } catch (IllegalArgumentException invalid) {
            return new Rejected(invalid.getMessage());
        }
        return new Applied(edits.size());
    }

    /**
     * Applies an edit that formats the document or organizes its imports: the checks and the single undo step of
     * {@link #apply}, and the caret stays on its code line when the edit adds or removes lines above it. An edit
     * that leaves the text as it is changes nothing and reports zero edits.
     *
     * @param document the open document the edit applies to
     * @param edit the edit the server returned
     * @return the number of text edits that changed the document, or the reason it was not applied
     */
    public static Result applyKeepingLine(EditorDocument document, WorkspaceEdit edit) {
        String refusal = refusal(document.file(), edit);
        if (refusal != null) {
            return new Rejected(refusal);
        }
        List<TextEdit> edits = textEditsOf(edit);
        if (edits.isEmpty()) {
            return new Applied(0);
        }
        String text = document.text();
        String replaced;
        try {
            replaced = applyTo(text, edits);
        } catch (IllegalArgumentException invalid) {
            return new Rejected(invalid.getMessage());
        }
        if (replaced.equals(text)) {
            return new Applied(0);
        }
        document.replaceText(replaced, rowAfter(edits, document.row()), document.column());
        return new Applied(edits.size());
    }

    private static List<TextEdit> textEditsOf(WorkspaceEdit edit) {
        List<TextEdit> edits = new ArrayList<>();
        for (DocumentEdit documentEdit : edit.documents()) {
            edits.addAll(documentEdit.edits());
        }
        return edits;
    }

    /**
     * @param edits text edits of one document, none of them overlapping
     * @param row the zero based row of the caret before the edits
     * @return the row of the same code line after the edits: rows below an edit move by the lines the edit added
     *         or removed, and a row inside an edit moves to the first row of that edit
     */
    static int rowAfter(List<TextEdit> edits, int row) {
        List<TextEdit> ordered = edits.stream()
                .sorted(Comparator.comparingInt(TextEdit::startLine).thenComparingInt(TextEdit::startColumn))
                .toList();
        int shift = 0;
        for (TextEdit edit : ordered) {
            if (edit.endLine() < row) {
                shift += newlines(edit.newText()) - (edit.endLine() - edit.startLine());
            } else if (edit.startLine() <= row) {
                return Math.max(0, edit.startLine() + shift);
            }
        }
        return Math.max(0, row + shift);
    }

    private static int newlines(String text) {
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                count++;
            }
        }
        return count;
    }

    /**
     * @param open the open file the edit has to change
     * @param edit the edit to check
     * @return why the editor cannot apply the edit to the open file, or null when it can
     */
    static String refusal(Path open, WorkspaceEdit edit) {
        if (!edit.unsupported().isEmpty()) {
            UnsupportedChange change = edit.unsupported().getFirst();
            return "the editor cannot apply the " + change.kind() + " change of '" + change.target() + "' yet";
        }
        Path normalized = open.toAbsolutePath().normalize();
        for (DocumentEdit documentEdit : edit.documents()) {
            if (!documentEdit.file().toAbsolutePath().normalize().equals(normalized)) {
                return "the edit changes '" + documentEdit.file() + "', which is not the open document";
            }
        }
        return null;
    }

    /**
     * @param text the document text, lines separated by a newline
     * @param edits text edits in the document, in the order the server sent them
     * @return the text with every edit applied
     * @throws IllegalArgumentException when a range lies outside the document or
     *         two edits overlap
     */
    static String applyTo(String text, List<TextEdit> edits) {
        int[] lineStarts = lineStarts(text);
        List<Span> spans = new ArrayList<>(edits.size());
        for (int order = 0; order < edits.size(); order++) {
            TextEdit edit = edits.get(order);
            int start = offsetOf(text, lineStarts, edit.startLine(), edit.startColumn());
            int end = offsetOf(text, lineStarts, edit.endLine(), edit.endColumn());
            if (end < start) {
                throw new IllegalArgumentException("an edit ends before it starts");
            }
            spans.add(new Span(start, end, edit.newText(), order));
        }
        spans.sort(Comparator.comparingInt(Span::start).thenComparingInt(Span::end).thenComparingInt(Span::order));
        for (int index = 1; index < spans.size(); index++) {
            if (spans.get(index).start() < spans.get(index - 1).end()) {
                throw new IllegalArgumentException("the edits overlap and cannot be applied together");
            }
        }
        StringBuilder result = new StringBuilder(text);
        for (int index = spans.size() - 1; index >= 0; index--) {
            Span span = spans.get(index);
            result.replace(span.start(), span.end(), span.newText());
        }
        return result.toString();
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                starts.add(index + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int offsetOf(String text, int[] lineStarts, int line, int column) {
        if (line < 0 || line >= lineStarts.length) {
            throw new IllegalArgumentException("line " + line + " is outside the document");
        }
        if (column < 0) {
            throw new IllegalArgumentException("column " + column + " is negative");
        }
        int lineEnd = line + 1 < lineStarts.length ? lineStarts[line + 1] - 1 : text.length();
        int lineLength = lineEnd - lineStarts[line];
        return lineStarts[line] + Math.min(column, lineLength);
    }

    private record Span(int start, int end, String newText, int order) {
    }
}
