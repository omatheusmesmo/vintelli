package dev.omatheusmesmo.vintelli.tui.editor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dev.omatheusmesmo.vintelli.api.ShellEditorPalette;
import dev.tamboui.style.AnsiColor;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.syntax.SyntaxHighlighter;
import dev.tamboui.widgets.syntax.SyntaxTheme;
import dev.tamboui.widgets.syntax.TokenType;

/**
 * Incrementally cached Java syntax highlighter for the built-in editor. It
 * implements TamboUI's {@link SyntaxHighlighter} hook and provides:
 *
 * <ul>
 *   <li>a per-line token cache that reuses unchanged rows and realigns the
 *   common prefix and suffix when rows are inserted or removed;</li>
 *   <li>per-line end-of-line lexical state, so block comments and text blocks
 *   survive across lines and edits re-tokenize forward from the edited line
 *   until the end state matches the cached one;</li>
 *   <li>diagnostic background tint and keyboard selection tint, driven from
 *   state kept outside {@code TextAreaState}.</li>
 * </ul>
 *
 * <p>The {@link Diagnostic} map is keyed by logical document row. The editor
 * document owns diagnostic row remapping when edits insert or remove lines.
 *
 * <p>The tokenizer is a hand-rolled scanner with no regex and no reflection.
 * It covers the Java constructs the editor needs (comments, text blocks,
 * strings, chars, numbers, keywords, constants, capitalized types, call
 * detection before a left parenthesis, annotations, operators, punctuation).
 * Generics stay un-styled like in the built-in grammar.
 *
 * <p>This state is scoped to one editor document. Call {@link #reset()} when
 * the document is replaced; each tab retains its own highlighter instance.
 */
final class EditorSyntaxHighlighter implements SyntaxHighlighter {

    private static final Set<String> KEYWORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally",
        "float", "for", "if", "implements", "import", "instanceof", "int", "interface", "long",
        "native", "new", "package", "permits", "private", "protected", "public", "record",
        "return", "sealed", "short", "static", "strictfp", "super", "switch", "synchronized",
        "this", "throw", "throws", "transient", "try", "var", "void", "volatile", "while",
        "yield");

    private static final Set<String> CONSTANTS = Set.of("true", "false", "null");

    private static final String OPERATOR_CHARS = "+-*/%=<>!&|^~?:";

    private static final String PUNCTUATION_CHARS = "(){}[];,.";

    private enum LexState {
        NORMAL,
        BLOCK_COMMENT,
        TEXT_BLOCK
    }

    /**
     * A diagnostic attached to one logical document row.
     */
    public record Diagnostic(int row, Severity severity) {
    }

    /**
     * Diagnostic severity with the background tint the editor paints.
     */
    public enum Severity {
        ERROR(Color.ansi(AnsiColor.RED)),
        WARNING(Color.ansi(AnsiColor.YELLOW)),
        INFO(Color.ansi(AnsiColor.BLUE));

        private final Color background;

        Severity(Color background) {
            this.background = background;
        }
    }

    private record Tokenization(List<Span> spans, LexState endState) {
    }

    private static final class CachedLine {
        private final String text;
        private final LexState startState;
        private final LexState endState;
        private final List<Span> tokens;
        private final Line plainLine;
        private Line tintedLine;
        private int tintStamp = -1;

        private CachedLine(String text, LexState startState, LexState endState, Tokenization tokenization) {
            this.text = text;
            this.startState = startState;
            this.endState = endState;
            this.tokens = tokenization.spans();
            this.plainLine = Line.from(tokenization.spans());
            this.tintedLine = this.plainLine;
        }
    }

    private Style previousBase;
    private SyntaxTheme previousTheme;
    private ShellEditorPalette editorPalette;
    private SyntaxTheme editorTheme;
    private String previousCode;
    private List<Line> previousLines;
    private int previousTint = -1;

    private final List<CachedLine> cache = new ArrayList<>();

    private final Map<Integer, Diagnostic> diagnostics = new HashMap<>();

    private SelectionCursor selection = SelectionCursor.collapsed(0, 0);

    private int tintGeneration;

    private int lastTokenizedLines;

    void editorPalette(ShellEditorPalette palette) {
        if (!Objects.equals(editorPalette, palette)) {
            editorPalette = palette;
            editorTheme = palette == null ? null : SyntaxTheme.builder()
                    .token(TokenType.PLAIN, foreground(palette.text()))
                    .token(TokenType.COMMENT, foreground(palette.comment()))
                    .token(TokenType.KEYWORD, foreground(palette.keyword()))
                    .token(TokenType.TYPE, foreground(palette.text()))
                    .token(TokenType.STRING, foreground(palette.string()))
                    .token(TokenType.NUMBER, foreground(palette.number()))
                    .token(TokenType.CONSTANT, foreground(palette.constant()))
                    .token(TokenType.FUNCTION, foreground(palette.text()))
                    .token(TokenType.ANNOTATION, foreground(palette.keyword()))
                    .token(TokenType.OPERATOR, foreground(palette.text()))
                    .token(TokenType.PUNCTUATION, foreground(palette.text()))
                    .build();
        }
    }

    private static Style foreground(String color) {
        return Style.EMPTY.fg(Color.hex(color));
    }

    @Override
    public List<Line> highlight(String code, String language, Style base, SyntaxTheme theme) {
        if (!"java".equalsIgnoreCase(language)) {
            return plainLines(code);
        }
        SyntaxTheme activeTheme = editorTheme == null ? theme : editorTheme;
        if (!base.equals(previousBase) || activeTheme != previousTheme) {
            cache.clear();
            previousCode = null;
            previousBase = base;
            previousTheme = activeTheme;
        }
        if (code.equals(previousCode) && previousTint == tintGeneration) {
            lastTokenizedLines = 0;
            return previousLines;
        }
        String normalized = code.endsWith("\n") ? code.substring(0, code.length() - 1) : code;
        String[] lines = normalized.isEmpty() ? new String[] {""} : normalized.split("\n", -1);
        realign(cache, lines);
        List<Line> out = new ArrayList<>(lines.length);
        LexState state = LexState.NORMAL;
        int tokenized = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            CachedLine cached = cache.get(i);
            if (cached != null && cached.startState == state && cached.text.equals(line)) {
                state = cached.endState;
            } else {
                Tokenization tokenization = tokenize(line, state, base, activeTheme);
                cached = new CachedLine(line, state, tokenization.endState(), tokenization);
                cache.set(i, cached);
                state = tokenization.endState();
                tokenized++;
            }
            out.add(lineFor(i, cached));
        }
        lastTokenizedLines = tokenized;
        previousCode = code;
        previousLines = List.copyOf(out);
        previousTint = tintGeneration;
        return previousLines;
    }

    private static List<Line> plainLines(String code) {
        String normalized = code.endsWith("\n") ? code.substring(0, code.length() - 1) : code;
        String[] lines = normalized.isEmpty() ? new String[] { "" } : normalized.split("\n", -1);
        return Arrays.stream(lines).map(Line::from).toList();
    }

    /**
     * Maps the cached rows whose text still appears in the document onto their
     * new positions, leaving {@code null} only for the changed middle block so
     * the tokenization loop rebuilds just that block. It runs only when the
     * row count changed: a same-size pass keeps the cache in place because the
     * loop already compares every row text, so re-tokenization there stays
     * limited to the edited rows plus any rows whose start state changes.
     * Rows in the common prefix keep their indices; rows in the common suffix
     * keep their offset from the end. Suffix rows whose start state no longer
     * matches are re-tokenized by the loop until the end state converges.
     */
    private static void realign(List<CachedLine> cache, String[] lines) {
        int previousSize = cache.size();
        int newSize = lines.length;
        if (previousSize == newSize) {
            return;
        }
        int common = Math.min(previousSize, newSize);
        int prefix = 0;
        while (prefix < common && cache.get(prefix) != null && cache.get(prefix).text.equals(lines[prefix])) {
            prefix++;
        }
        int suffix = 0;
        int limit = common - prefix;
        while (suffix < limit && cache.get(previousSize - 1 - suffix) != null
            && cache.get(previousSize - 1 - suffix).text.equals(lines[newSize - 1 - suffix])) {
            suffix++;
        }
        List<CachedLine> shifted = new ArrayList<>(newSize);
        for (int i = 0; i < newSize; i++) {
            shifted.add(null);
        }
        for (int i = 0; i < prefix; i++) {
            shifted.set(i, cache.get(i));
        }
        for (int i = 0; i < suffix; i++) {
            shifted.set(newSize - 1 - i, cache.get(previousSize - 1 - i));
        }
        cache.clear();
        cache.addAll(shifted);
    }

    private Line lineFor(int row, CachedLine cached) {
        boolean selectionRow = selection != null && !selection.isEmpty()
            && row >= selection.startRow() && row <= selection.endRow();
        boolean hasDiagnostic = diagnostics.containsKey(row);
        if (selectionRow || hasDiagnostic) {
            if (cached.tintStamp != tintGeneration) {
                cached.tintedLine = tint(row, cached.tokens);
                cached.tintStamp = tintGeneration;
            }
            return cached.tintedLine;
        }
        return cached.plainLine;
    }

    private Line tint(int row, List<Span> tokens) {
        List<Span> styled = tokens;
        if (selection != null && !selection.isEmpty()
            && row >= selection.startRow() && row <= selection.endRow()) {
            int from = row == selection.startRow() ? selection.startCol() : 0;
            int to = row == selection.endRow() ? selection.endCol() : Integer.MAX_VALUE;
            if (to > from) {
                styled = splitSelection(styled, from, to);
            }
        }
        Diagnostic diagnostic = diagnostics.get(row);
        if (diagnostic != null) {
            styled = tintBackground(styled, diagnostic.severity().background);
        }
        return Line.from(styled);
    }

    private static List<Span> splitSelection(List<Span> spans, int selectionStart, int selectionEnd) {
        List<Span> out = new ArrayList<>();
        int cursor = 0;
        for (Span span : spans) {
            String content = span.content();
            int spanEnd = cursor + content.length();
            Style style = span.style();
            if (spanEnd <= selectionStart || cursor >= selectionEnd) {
                out.add(span);
                cursor = spanEnd;
                continue;
            }
            int selectedFrom = Math.max(cursor, selectionStart);
            int selectedTo = Math.min(spanEnd, selectionEnd);
            if (selectedFrom > cursor) {
                out.add(Span.styled(content.substring(0, selectedFrom - cursor), style));
            }
            out.add(Span.styled(content.substring(selectedFrom - cursor, selectedTo - cursor), style.reversed()));
            if (selectedTo < spanEnd) {
                out.add(Span.styled(content.substring(selectedTo - cursor), style));
            }
            cursor = spanEnd;
        }
        return out;
    }

    private static List<Span> tintBackground(List<Span> spans, Color background) {
        List<Span> out = new ArrayList<>(spans.size());
        for (Span span : spans) {
            out.add(Span.styled(span.content(), span.style().bg(background)));
        }
        return out;
    }

    private Tokenization tokenize(String line, LexState startState, Style base, SyntaxTheme theme) {
        List<Span> spans = new ArrayList<>();
        LexState state = startState;
        int i = 0;
        while (i < line.length()) {
            if (state == LexState.BLOCK_COMMENT) {
                int close = line.indexOf("*/", i);
                if (close < 0) {
                    emitToken(spans, line.substring(i), TokenType.COMMENT, base, theme);
                    i = line.length();
                } else {
                    emitToken(spans, line.substring(i, close + 2), TokenType.COMMENT, base, theme);
                    i = close + 2;
                    state = LexState.NORMAL;
                }
                continue;
            }
            if (state == LexState.TEXT_BLOCK) {
                int close = line.indexOf("\"\"\"", i);
                if (close < 0) {
                    emitToken(spans, line.substring(i), TokenType.STRING, base, theme);
                    i = line.length();
                } else {
                    emitToken(spans, line.substring(i, close + 3), TokenType.STRING, base, theme);
                    i = close + 3;
                    state = LexState.NORMAL;
                }
                continue;
            }
            char c = line.charAt(i);
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                emitToken(spans, line.substring(i), TokenType.COMMENT, base, theme);
                i = line.length();
                continue;
            }
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                int close = line.indexOf("*/", i + 2);
                if (close < 0) {
                    emitToken(spans, line.substring(i), TokenType.COMMENT, base, theme);
                    i = line.length();
                    state = LexState.BLOCK_COMMENT;
                } else {
                    emitToken(spans, line.substring(i, close + 2), TokenType.COMMENT, base, theme);
                    i = close + 2;
                }
                continue;
            }
            if (c == '"' && line.startsWith("\"\"\"", i)) {
                int close = line.indexOf("\"\"\"", i + 3);
                if (close < 0) {
                    emitToken(spans, line.substring(i), TokenType.STRING, base, theme);
                    i = line.length();
                    state = LexState.TEXT_BLOCK;
                } else {
                    emitToken(spans, line.substring(i, close + 3), TokenType.STRING, base, theme);
                    i = close + 3;
                }
                continue;
            }
            if (c == '"') {
                int end = scanQuoted(line, i + 1, '"');
                emitToken(spans, line.substring(i, end), TokenType.STRING, base, theme);
                i = end;
                continue;
            }
            if (c == '\'') {
                int end = scanQuoted(line, i + 1, '\'');
                emitToken(spans, line.substring(i, end), TokenType.STRING, base, theme);
                i = end;
                continue;
            }
            if (Character.isDigit(c)) {
                int j = i + 1;
                while (j < line.length() && (Character.isLetterOrDigit(line.charAt(j))
                    || line.charAt(j) == '.' || line.charAt(j) == '_')) {
                    j++;
                }
                emitToken(spans, line.substring(i, j), TokenType.NUMBER, base, theme);
                i = j;
                continue;
            }
            if (c == '@') {
                int j = i + 1;
                while (j < line.length() && Character.isLetterOrDigit(line.charAt(j))) {
                    j++;
                }
                emitToken(spans, line.substring(i, Math.min(j, line.length())), TokenType.ANNOTATION, base, theme);
                i = Math.min(j, line.length());
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '$') {
                int j = i + 1;
                while (j < line.length() && (Character.isLetterOrDigit(line.charAt(j))
                    || line.charAt(j) == '_' || line.charAt(j) == '$')) {
                    j++;
                }
                String word = line.substring(i, j);
                emitToken(spans, word, wordTokenType(word, line, j), base, theme);
                i = j;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\f' || c == '\r') {
                int j = i + 1;
                while (j < line.length() && (line.charAt(j) == ' ' || line.charAt(j) == '\t')) {
                    j++;
                }
                emitToken(spans, line.substring(i, j), TokenType.PLAIN, base, theme);
                i = j;
                continue;
            }
            if (OPERATOR_CHARS.indexOf(c) >= 0) {
                int j = i + 1;
                while (j < line.length() && OPERATOR_CHARS.indexOf(line.charAt(j)) >= 0) {
                    j++;
                }
                emitToken(spans, line.substring(i, j), TokenType.OPERATOR, base, theme);
                i = j;
                continue;
            }
            if (PUNCTUATION_CHARS.indexOf(c) >= 0) {
                emitToken(spans, line.substring(i, i + 1), TokenType.PUNCTUATION, base, theme);
                i++;
                continue;
            }
            emitToken(spans, line.substring(i, i + 1), TokenType.PLAIN, base, theme);
            i++;
        }
        return new Tokenization(spans, state);
    }

    private static int scanQuoted(String line, int from, char quote) {
        int j = from;
        while (j < line.length()) {
            char c = line.charAt(j);
            if (c == '\\' && j + 1 < line.length()) {
                j += 2;
                continue;
            }
            j++;
            if (c == quote) {
                break;
            }
        }
        return Math.min(j, line.length());
    }

    private static TokenType wordTokenType(String word, String line, int end) {
        if (KEYWORDS.contains(word)) {
            return TokenType.KEYWORD;
        }
        if (CONSTANTS.contains(word)) {
            return TokenType.CONSTANT;
        }
        if (isCall(line, end)) {
            return TokenType.FUNCTION;
        }
        if (Character.isUpperCase(word.charAt(0))) {
            return TokenType.TYPE;
        }
        return TokenType.PLAIN;
    }

    private static boolean isCall(String line, int from) {
        int j = from;
        while (j < line.length() && (line.charAt(j) == ' ' || line.charAt(j) == '\t')) {
            j++;
        }
        return j < line.length() && line.charAt(j) == '(';
    }

    private static void emitToken(List<Span> out, String content, TokenType type, Style base, SyntaxTheme theme) {
        if (content.isEmpty()) {
            return;
        }
        Style style = theme.style(type, base);
        if (!out.isEmpty()) {
            Span previous = out.get(out.size() - 1);
            if (previous.style().equals(style)) {
                out.set(out.size() - 1, Span.styled(previous.content() + content, style));
                return;
            }
        }
        out.add(Span.styled(content, style));
    }

    /**
     * Replaces the diagnostics painted over the document.
     *
     * <p>The rows are not re-keyed when lines are inserted or removed; keeping
     * them aligned with the logical document rows is the caller's job (see the
     * class Javadoc for the editor adapter of #31).
     *
     * @param diagnostics one diagnostic per logical row; rows without a
     *        diagnostic keep their plain token styles
     */
    public void setDiagnostics(List<Diagnostic> diagnostics) {
        this.diagnostics.clear();
        for (Diagnostic diagnostic : diagnostics) {
            this.diagnostics.put(diagnostic.row(), diagnostic);
        }
        tintGeneration++;
    }

    /**
     * Replaces the keyboard selection range. The selection anchor is kept in
     * this object instead of {@code TextAreaState}, which has no selection
     * concept; the editor owns the anchor and extends it from the cursor while
     * a selection modifier is held.
     *
     * @param selection the new selection, or a collapsed cursor to clear it
     */
    public void setSelection(SelectionCursor selection) {
        this.selection = selection;
        tintGeneration++;
    }

    /**
     * Number of lines re-tokenized by the last {@link #highlight} call; the
     * keystroke path should return a small count against a warm cache.
     *
     * @return the dirty line count of the last pass
     */
    public int lastTokenizedLines() {
        return lastTokenizedLines;
    }

    /**
     * Drops all cached lines, for example after opening a different document.
     */
    public void reset() {
        previousCode = null;
        previousBase = null;
        previousTheme = null;
        cache.clear();
        diagnostics.clear();
        selection = SelectionCursor.collapsed(0, 0);
        tintGeneration++;
    }
}
