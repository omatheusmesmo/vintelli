package dev.omatheusmesmo.vintelli.tui.editor;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.text.DiffRow;
import com.github.difflib.text.DiffRowGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The changes between the {@code HEAD} side and the working side of one file. Line hunks come from
 * java-diff-utils and are laid out as aligned row pairs for the side by side view, with a filler side
 * (a {@code null} {@link Side}) where the other side has no line, and as unified lines with
 * {@code @@ ... @@} headers. Word changes inside a modified pair come from the {@link DiffRowGenerator}
 * inline diff. Tabs are expanded and control characters replaced, so a column of {@link Side#text()}
 * is a terminal cell.
 */
public final class DiffModel {

    public enum Kind { EQUAL, INSERTED, DELETED, MODIFIED }

    public enum LineKind { HEADER, CONTEXT, REMOVED, ADDED }

    /** A changed word range of a line, as character offsets into {@link Side#text()}. */
    public record WordRange(int start, int end) {
    }

    /** One side of a row: its one based line number, its text and its changed words. */
    public record Side(int line, String text, List<WordRange> words) {
    }

    /** One side by side row; a {@code null} side is a filler, {@code hunk} is -1 for an unchanged row. */
    public record Row(Kind kind, Side left, Side right, int hunk) {
    }

    /** The rows {@code [firstRow, endRow)} of one change. */
    public record Hunk(Kind kind, int firstRow, int endRow) {
    }

    /**
     * One unified line. Line numbers are one based and 0 when the line has no such side; {@code row} is the
     * side by side row it shows, so a cursor can move between the two layouts.
     */
    public record UnifiedLine(LineKind kind, String text, int oldLine, int newLine, List<WordRange> words, int hunk,
            int row) {
    }

    static final int CONTEXT_LINES = 3;
    private static final int TAB_WIDTH = 4;
    private static final int MAX_HEADER_SCAN = 2000;
    private static final String WORD_START = "\u0001";
    private static final String WORD_END = "\u0002";
    private static final Pattern METHOD = Pattern.compile(
            "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:(?:public|protected|private|static|final|abstract|synchronized"
                    + "|native|default|strictfp)\\s+)*(?:<[^>]*>\\s+)?[\\w.$<>\\[\\],?]+(?:\\s*\\[\\])*\\s+(\\w+)"
                    + "\\s*\\([^;]*$");
    private static final Pattern TYPE = Pattern.compile("\\b(?:class|interface|enum|record)\\s+\\w+");
    private static final Set<String> STATEMENTS = Set.of("if", "for", "while", "switch", "catch", "return", "new",
            "else", "do", "try", "throw", "yield", "synchronized");

    private final List<Row> rows;
    private final List<Hunk> hunks;
    private final List<UnifiedLine> unified;
    private final boolean headPresent;
    private final boolean workingPresent;

    private DiffModel(List<Row> rows, List<Hunk> hunks, List<UnifiedLine> unified, boolean headPresent,
            boolean workingPresent) {
        this.rows = List.copyOf(rows);
        this.hunks = List.copyOf(hunks);
        this.unified = List.copyOf(unified);
        this.headPresent = headPresent;
        this.workingPresent = workingPresent;
    }

    /**
     * @param head     the committed content, empty for an added file
     * @param working  the working content, empty for a deleted file
     * @param fileName the file name; hunk headers name the enclosing method only for a {@code .java} file
     */
    public static DiffModel of(Optional<String> head, Optional<String> working, String fileName) {
        List<String> oldLines = lines(head.orElse(""));
        List<String> newLines = lines(working.orElse(""));
        List<Row> rows = new ArrayList<>();
        List<Hunk> hunks = new ArrayList<>();
        int oldIndex = 0;
        int newIndex = 0;
        for (AbstractDelta<String> delta : DiffUtils.diff(oldLines, newLines).getDeltas()) {
            Kind kind = switch (delta.getType()) {
                case INSERT -> Kind.INSERTED;
                case DELETE -> Kind.DELETED;
                case CHANGE -> Kind.MODIFIED;
                case EQUAL -> Kind.EQUAL;
            };
            if (kind == Kind.EQUAL) {
                continue;
            }
            while (oldIndex < delta.getSource().getPosition()) {
                rows.add(equalRow(oldLines, newLines, oldIndex++, newIndex++));
            }
            int removed = delta.getSource().size();
            int added = delta.getTarget().size();
            int paired = kind == Kind.MODIFIED ? Math.min(removed, added) : 0;
            int first = rows.size();
            int hunk = hunks.size();
            for (int offset = 0; offset < Math.max(removed, added); offset++) {
                String oldText = offset < removed ? oldLines.get(oldIndex + offset) : null;
                String newText = offset < added ? newLines.get(newIndex + offset) : null;
                List<List<WordRange>> words = offset < paired ? wordChanges(oldText, newText)
                        : List.of(List.of(), List.of());
                Side left = oldText == null ? null : new Side(oldIndex + offset + 1, oldText, words.get(0));
                Side right = newText == null ? null : new Side(newIndex + offset + 1, newText, words.get(1));
                rows.add(new Row(kind, left, right, hunk));
            }
            hunks.add(new Hunk(kind, first, rows.size()));
            oldIndex += removed;
            newIndex += added;
        }
        while (oldIndex < oldLines.size()) {
            rows.add(equalRow(oldLines, newLines, oldIndex++, newIndex++));
        }
        boolean java = fileName.endsWith(".java");
        List<String> headerSource = head.isPresent() ? oldLines : newLines;
        return new DiffModel(rows, hunks, unifiedLines(rows, hunks, java ? headerSource : List.of(), head.isPresent()),
                head.isPresent(), working.isPresent());
    }

    public List<Row> rows() {
        return rows;
    }

    public List<Hunk> hunks() {
        return hunks;
    }

    public List<UnifiedLine> unified() {
        return unified;
    }

    /** False for an added file, which has no {@code HEAD} side. */
    public boolean headPresent() {
        return headPresent;
    }

    /** False for a deleted file, which has no working side. */
    public boolean workingPresent() {
        return workingPresent;
    }

    /** The highest line number of either side, which sizes the line number columns. */
    public int maxLine() {
        int max = 0;
        for (Row row : rows) {
            max = Math.max(max, Math.max(row.left() == null ? 0 : row.left().line(),
                    row.right() == null ? 0 : row.right().line()));
        }
        return max;
    }

    /** The unified line that shows {@code row}, or the nearest one after it. */
    public int unifiedIndexOf(int row) {
        for (int index = 0; index < unified.size(); index++) {
            UnifiedLine line = unified.get(index);
            if (line.kind() != LineKind.HEADER && line.row() >= row) {
                return index;
            }
        }
        return Math.max(0, unified.size() - 1);
    }

    /** The first unified line of {@code hunk} that carries a change. */
    public int unifiedStartOf(int hunk) {
        for (int index = 0; index < unified.size(); index++) {
            UnifiedLine line = unified.get(index);
            if (line.hunk() == hunk && line.kind() != LineKind.HEADER) {
                return index;
            }
        }
        return 0;
    }

    /**
     * The working line to open for {@code row}: its own right side, else the nearest right side below,
     * else above. Empty when the working side has no line at all.
     */
    public OptionalInt workingLineOf(int row) {
        if (rows.isEmpty()) {
            return OptionalInt.empty();
        }
        int start = Math.clamp(row, 0, rows.size() - 1);
        for (int index = start; index < rows.size(); index++) {
            if (rows.get(index).right() != null) {
                return OptionalInt.of(rows.get(index).right().line());
            }
        }
        for (int index = start - 1; index >= 0; index--) {
            if (rows.get(index).right() != null) {
                return OptionalInt.of(rows.get(index).right().line());
            }
        }
        return OptionalInt.empty();
    }

    private static Row equalRow(List<String> oldLines, List<String> newLines, int oldIndex, int newIndex) {
        return new Row(Kind.EQUAL, new Side(oldIndex + 1, oldLines.get(oldIndex), List.of()),
                new Side(newIndex + 1, newLines.get(newIndex), List.of()), -1);
    }

    /**
     * The changed words of a modified pair, through the inline diff of {@link DiffRowGenerator}. The tags
     * are control characters, which {@link #lines} already replaced in the text, so they never collide
     * with content.
     */
    private static List<List<WordRange>> wordChanges(String oldText, String newText) {
        DiffRowGenerator generator = DiffRowGenerator.create()
                .showInlineDiffs(true)
                .inlineDiffByWord(true)
                .mergeOriginalRevised(false)
                .lineNormalizer(Function.identity())
                .oldTag(start -> start ? WORD_START : WORD_END)
                .newTag(start -> start ? WORD_START : WORD_END)
                .build();
        List<DiffRow> generated = generator.generateDiffRows(List.of(oldText), List.of(newText));
        if (generated.size() != 1) {
            return List.of(List.of(), List.of());
        }
        DiffRow row = generated.getFirst();
        return List.of(spans(row.getOldLine(), oldText), spans(row.getNewLine(), newText));
    }

    private static List<WordRange> spans(String tagged, String plain) {
        List<WordRange> spans = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int start = -1;
        for (int index = 0; index < tagged.length(); index++) {
            char current = tagged.charAt(index);
            if (current == WORD_START.charAt(0)) {
                start = text.length();
            } else if (current == WORD_END.charAt(0)) {
                if (start >= 0 && text.length() > start) {
                    addMerged(spans, new WordRange(start, text.length()));
                }
                start = -1;
            } else {
                text.append(current);
            }
        }
        return text.toString().equals(plain) ? List.copyOf(spans) : List.of();
    }

    private static void addMerged(List<WordRange> spans, WordRange span) {
        if (!spans.isEmpty() && spans.getLast().end() == span.start()) {
            spans.set(spans.size() - 1, new WordRange(spans.getLast().start(), span.end()));
        } else {
            spans.add(span);
        }
    }

    private static List<UnifiedLine> unifiedLines(List<Row> rows, List<Hunk> hunks, List<String> headerSource,
            boolean headerFromHead) {
        List<UnifiedLine> lines = new ArrayList<>();
        int hunk = 0;
        while (hunk < hunks.size()) {
            int start = Math.max(0, hunks.get(hunk).firstRow() - CONTEXT_LINES);
            int last = hunk;
            while (last + 1 < hunks.size()
                    && hunks.get(last + 1).firstRow() - CONTEXT_LINES <= hunks.get(last).endRow() + CONTEXT_LINES) {
                last++;
            }
            int end = Math.min(rows.size(), hunks.get(last).endRow() + CONTEXT_LINES);
            lines.add(header(rows, start, end, hunk, headerSource, headerFromHead));
            int index = start;
            while (index < end) {
                Row row = rows.get(index);
                if (row.hunk() < 0) {
                    lines.add(new UnifiedLine(LineKind.CONTEXT, row.right().text(), row.left().line(),
                            row.right().line(), List.of(), -1, index));
                    index++;
                    continue;
                }
                Hunk change = hunks.get(row.hunk());
                for (int at = change.firstRow(); at < change.endRow(); at++) {
                    Side left = rows.get(at).left();
                    if (left != null) {
                        lines.add(new UnifiedLine(LineKind.REMOVED, left.text(), left.line(), 0, left.words(),
                                row.hunk(), at));
                    }
                }
                for (int at = change.firstRow(); at < change.endRow(); at++) {
                    Side right = rows.get(at).right();
                    if (right != null) {
                        lines.add(new UnifiedLine(LineKind.ADDED, right.text(), 0, right.line(), right.words(),
                                row.hunk(), at));
                    }
                }
                index = change.endRow();
            }
            hunk = last + 1;
        }
        return lines;
    }

    /** The git style header {@code @@ -a,b +c,d @@ name} of the rows {@code [start, end)}. */
    private static UnifiedLine header(List<Row> rows, int start, int end, int hunk, List<String> headerSource,
            boolean headerFromHead) {
        int oldCount = 0;
        int newCount = 0;
        int oldStart = 0;
        int newStart = 0;
        for (int index = start; index < end; index++) {
            Row row = rows.get(index);
            if (row.left() != null) {
                if (oldCount == 0) {
                    oldStart = row.left().line();
                }
                oldCount++;
            }
            if (row.right() != null) {
                if (newCount == 0) {
                    newStart = row.right().line();
                }
                newCount++;
            }
        }
        if (oldCount == 0) {
            oldStart = linesBefore(rows, start, true);
        }
        if (newCount == 0) {
            newStart = linesBefore(rows, start, false);
        }
        String text = "@@ -" + range(oldStart, oldCount) + " +" + range(newStart, newCount) + " @@";
        String name = headerFromHead
                ? enclosing(headerSource, oldCount == 0 ? oldStart : oldStart - 1)
                : enclosing(headerSource, newCount == 0 ? newStart : newStart - 1);
        return new UnifiedLine(LineKind.HEADER, name.isEmpty() ? text : text + " " + name, oldStart, newStart,
                List.of(), hunk, start);
    }

    private static int linesBefore(List<Row> rows, int start, boolean left) {
        for (int index = start - 1; index >= 0; index--) {
            Side side = left ? rows.get(index).left() : rows.get(index).right();
            if (side != null) {
                return side.line();
            }
        }
        return 0;
    }

    private static String range(int start, int count) {
        return count == 1 ? Integer.toString(start) : start + "," + count;
    }

    /**
     * The nearest method or type declaration above the line index {@code before}, trimmed and cut at its
     * opening brace, or an empty string. A bounded backward regex scan, so it stays cheap on large files.
     */
    private static String enclosing(List<String> lines, int before) {
        int stop = Math.max(0, before - MAX_HEADER_SCAN);
        for (int index = Math.min(before, lines.size()) - 1; index >= stop; index--) {
            String line = lines.get(index);
            Matcher method = METHOD.matcher(line);
            boolean declares = method.find() && !STATEMENTS.contains(method.group(1))
                    && !STATEMENTS.contains(line.strip().split("[\\s(]", 2)[0]);
            if (declares || TYPE.matcher(line).find() && !line.strip().startsWith("*")
                    && !line.strip().startsWith("/")) {
                int brace = line.indexOf('{');
                return (brace < 0 ? line : line.substring(0, brace)).strip();
            }
        }
        return "";
    }

    /** Splits a text into lines without their terminators, expanding tabs and replacing control characters. */
    static List<String> lines(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        List<String> lines = new ArrayList<>();
        for (String line : body.split("\n", -1)) {
            lines.add(printable(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line));
        }
        return lines;
    }

    private static String printable(String line) {
        StringBuilder out = new StringBuilder(line.length());
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '\t') {
                out.append(" ".repeat(TAB_WIDTH - out.length() % TAB_WIDTH));
            } else if (current < ' ' || current == 0x7f) {
                out.append('?');
            } else {
                out.append(current);
            }
        }
        return out.toString();
    }
}
