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
        if (!edit.unsupported().isEmpty()) {
            UnsupportedChange change = edit.unsupported().getFirst();
            return new Rejected("the editor cannot apply the " + change.kind() + " change of '" + change.target()
                    + "' yet");
        }
        Path open = document.file().toAbsolutePath().normalize();
        List<TextEdit> edits = new ArrayList<>();
        for (DocumentEdit documentEdit : edit.documents()) {
            if (!documentEdit.file().toAbsolutePath().normalize().equals(open)) {
                return new Rejected("the edit changes '" + documentEdit.file() + "', which is not the open document");
            }
            edits.addAll(documentEdit.edits());
        }
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
