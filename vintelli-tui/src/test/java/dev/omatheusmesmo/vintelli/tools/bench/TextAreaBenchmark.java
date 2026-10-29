package dev.omatheusmesmo.vintelli.tools.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.AnsiColor;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.input.TextArea;
import dev.tamboui.widgets.input.TextAreaState;
import dev.tamboui.widgets.syntax.RegexSyntaxHighlighter;
import dev.tamboui.widgets.syntax.SyntaxHighlighter;
import dev.tamboui.widgets.syntax.SyntaxTheme;

/**
 * Reproduces the spike 30 number table (TamboUI {@code TextArea} limits) with a
 * committed harness, and adds cells for the prototype incremental highlighter
 * ({@link LineCachedSyntaxHighlighter}).
 *
 * <p>The measurements are renderer-only: each frame renders into a freshly
 * allocated {@link Buffer} at 100x30 cells, with no real terminal and no
 * backend involved, mirroring how the spike was run. Each cell reports the
 * median and the minimum of sorted wall-clock samples taken with
 * {@link System#nanoTime()} after warm-up iterations.
 *
 * <p>Run locally from the repository root:
 * <ul>
 *   <li>corpus checks and a reduced table:
 *   {@code ./mvnw test -Dtest=TextAreaBenchmark}</li>
 *   <li>the full table for 100, 1000 and 10000 lines:
 *   {@code ./mvnw test -Dtest=TextAreaBenchmark -Dbench.full=true}</li>
 * </ul>
 *
 * <p>The class name deliberately avoids the surefire {@code *Test} pattern, so
 * CI (which runs the default test set) never executes it; it is meant to be
 * re-run on demand, on an idle machine, and shared by hand.
 */
public final class TextAreaBenchmark {

    private static final int COLUMN_WIDTH = 100;

    private static final int ROW_HEIGHT = 30;

    private static final int[] SMOKE_SIZES = {100, 1000};

    private static final int[] FULL_SIZES = {100, 1000, 10000};

    private static final int TOUCHED_ROW = 4;

    private TextAreaBenchmark() {
    }

    /**
     * Prints the full number table for 100, 1000 and 10000 lines.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        System.out.println(runTable(FULL_SIZES, 5, 10));
    }

    /**
     * Standalone smoke run: corpus correctness assertions for the prototype
     * plus a reduced number table for 100 and 1000 lines. Only executed on
     * demand with {@code -Dtest=TextAreaBenchmark}; surefire does not pick
     * this class up by name.
     */
    @Test
    void validatesPrototypeAndMeasuresReducedTable() {
        String source = syntheticSource(500);
        LineCachedSyntaxHighlighter prototype = new LineCachedSyntaxHighlighter();
        prototype.highlight(source, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);
        Assertions.assertEquals(countLines(source), prototype.lastTokenizedLines());

        prototype.highlight(touchLine(source, TOUCHED_ROW, "x", 1), "java", Style.EMPTY, SyntaxTheme.DEFAULTS);
        Assertions.assertEquals(1, prototype.lastTokenizedLines());

        assertSpansReconstructLines(source, prototype);
        assertSelectionTint(source, prototype);
        assertDiagnosticTint(source, prototype);

        System.out.println(runTable(SMOKE_SIZES, 3, 10));
    }

    /**
     * On-demand full table run ({@code -Dbench.full=true}): the same cells
     * across 100, 1000 and 10000 lines like the original spike run.
     */
    @Test
    void measuresFullTable() {
        Assumptions.assumeTrue(Boolean.getBoolean("bench.full"), "run with -Dbench.full=true");
        System.out.println(runTable(FULL_SIZES, 5, 10));
    }

    private static String runTable(int[] sizes, int warmup, int iterations) {
        StringBuilder report = new StringBuilder("\nTextArea benchmark (renderer-only, 100x30, warm ")
            .append(warmup).append(", ").append(iterations).append(" iterations, median/min):\n");
        for (int size : sizes) {
            report.append("  lines ").append(size).append('\n');
            measureDocument(size, warmup, iterations, report);
        }
        return report.toString();
    }

    private static void measureDocument(int lineCount, int warmup, int iterations, StringBuilder report) {
        String source = syntheticSource(lineCount);
        Rect area = new Rect(0, 0, COLUMN_WIDTH, ROW_HEIGHT);
        TextAreaState state = new TextAreaState(source);
        state.moveCursorToEnd();

        TextArea plain = TextArea.builder().build();
        TextArea builtin = TextArea.builder()
            .highlighter("java")
            .build();
        TextArea wrapped = TextArea.builder()
            .overflow(Overflow.WRAP_WORD)
            .build();
        LineCachedSyntaxHighlighter prototype = new LineCachedSyntaxHighlighter();
        TextArea prototypeEditor = TextArea.builder()
            .highlighter(prototype, "java")
            .build();
        SyntaxHighlighter builtInHighlighter = RegexSyntaxHighlighter.defaults();
        prototype.highlight(source, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);

        report.append(String.format("  document chars %d%n", source.length()));
        reportCell(report, "steady render plain",
            measure(() -> plain.render(area, Buffer.empty(area), state), warmup, iterations));
        reportCell(report, "steady render built-in highlighter",
            measure(() -> builtin.render(area, Buffer.empty(area), state), warmup, iterations));
        reportCell(report, "steady render prototype warm cache",
            measure(() -> prototypeEditor.render(area, Buffer.empty(area), state), warmup, iterations));
        reportCell(report, "steady render wrapped plain",
            measure(() -> wrapped.render(area, Buffer.empty(area), state), warmup, iterations));
        reportCell(report, "keystroke insert + render plain",
            measure(() -> keystroke(plain, state, area), warmup, iterations));
        reportCell(report, "keystroke insert + render built-in highlighter",
            measure(() -> keystroke(builtin, state, area), warmup, iterations));
        reportCell(report, "keystroke insert + render prototype warm cache",
            measure(() -> keystroke(prototypeEditor, state, area), warmup, iterations));
        reportCell(report, "scroll one row plain",
            measure(() -> {
                state.scrollDown(1, ROW_HEIGHT);
                plain.render(area, Buffer.empty(area), state);
            }, warmup, iterations));
        reportCell(report, "built-in full-document highlight alone",
            measure(() -> builtInHighlighter.highlight(source, "java", Style.EMPTY, SyntaxTheme.DEFAULTS),
                warmup, iterations));

        String[] variants = editVariants(source, warmup + iterations);
        AtomicInteger variantCursor = new AtomicInteger();
        reportCell(report, "prototype highlight alone, one dirty line",
            measure(() -> prototype.highlight(variants[variantCursor.getAndIncrement() % variants.length], "java",
                Style.EMPTY, SyntaxTheme.DEFAULTS), warmup, iterations));

        reportCell(report, "setText full rebuild (file open)",
            measure(() -> state.setText(source), warmup, iterations));
        state.moveCursorToEnd();

        report.append(String.format("  prototype dirty lines after last pass: %d of %d%n",
            prototype.lastTokenizedLines(), lineCount));
    }

    private static void reportCell(StringBuilder report, String label, long[] samples) {
        double median = samples[samples.length / 2] / 1_000_000.0;
        double min = samples[0] / 1_000_000.0;
        report.append(String.format("    %-48s median %10.3f ms   min %10.3f ms%n", label, median, min));
    }

    private static long[] measure(Runnable action, int warmup, int iterations) {
        for (int i = 0; i < warmup; i++) {
            action.run();
        }
        long[] samples = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            action.run();
            samples[i] = System.nanoTime() - start;
        }
        Arrays.sort(samples);
        return samples;
    }

    private static void keystroke(TextArea area, TextAreaState state, Rect rect) {
        state.insert('x');
        area.render(rect, Buffer.empty(rect), state);
    }

    private static String[] editVariants(String source, int count) {
        String[] variants = new String[count];
        for (int i = 0; i < count; i++) {
            variants[i] = touchLine(source, TOUCHED_ROW, "x", 1 + i);
        }
        return variants;
    }

    private static String touchLine(String source, int row, String token, int count) {
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
        int index = Math.min(row, lines.size() - 1);
        StringBuilder line = new StringBuilder(lines.get(index));
        for (int i = 0; i < count; i++) {
            line.append(token).append(i);
        }
        lines.set(index, line.toString());
        return String.join("\n", lines);
    }

    private static void assertSpansReconstructLines(String source, LineCachedSyntaxHighlighter prototype) {
        prototype.setDiagnostics(List.of());
        prototype.setSelection(SelectionCursor.collapsed(0, 0));
        List<Line> highlighted = prototype.highlight(source, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);
        String[] lines = source.split("\n", -1);
        Assertions.assertEquals(lines.length, highlighted.size());
        for (int i = 0; i < lines.length; i++) {
            StringBuilder joined = new StringBuilder();
            for (Span span : highlighted.get(i).spans()) {
                joined.append(span.content());
            }
            Assertions.assertEquals(lines[i], joined.toString(), "line " + i + " not reconstructed by spans");
        }
    }

    private static void assertSelectionTint(String source, LineCachedSyntaxHighlighter prototype) {
        prototype.setDiagnostics(List.of());
        prototype.setSelection(SelectionCursor.collapsed(0, 0));
        List<Line> plain = prototype.highlight(source, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);
        prototype.setSelection(SelectionCursor.collapsed(2, 4).extendTo(2, 9));
        List<Line> selected = prototype.highlight(source, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);
        prototype.setSelection(SelectionCursor.collapsed(0, 0));
        Assertions.assertTrue(hasReversedSpan(selected.get(2), spanStyles(plain.get(2))),
            "selection tint missing on row 2");
        Assertions.assertFalse(hasReversedSpan(selected.get(3), spanStyles(plain.get(3))),
            "selection tint leaked to row 3");
        Assertions.assertFalse(hasReversedSpan(selected.get(1), spanStyles(plain.get(1))),
            "selection tint leaked to row 1");
    }

    private static void assertDiagnosticTint(String source, LineCachedSyntaxHighlighter prototype) {
        prototype.setSelection(SelectionCursor.collapsed(0, 0));
        List<Line> plain = prototype.highlight(source, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);
        prototype.setDiagnostics(List.of(new LineCachedSyntaxHighlighter.Diagnostic(5,
            LineCachedSyntaxHighlighter.Severity.ERROR)));
        List<Line> tinted = prototype.highlight(source, "java", Style.EMPTY, SyntaxTheme.DEFAULTS);
        Color errorBackground = Color.ansi(AnsiColor.RED);
        Set<Style> expected = spanStyles(plain.get(5)).stream()
            .map(style -> style.bg(errorBackground))
            .collect(Collectors.toSet());
        Assertions.assertFalse(expected.isEmpty());
        Assertions.assertTrue(containsAnyStyle(tinted.get(5), expected), "diagnostic tint missing on row 5");
        Assertions.assertFalse(containsAnyStyle(tinted.get(6), expected), "diagnostic tint leaked to row 6");
        prototype.setDiagnostics(List.of());
    }

    private static boolean hasNonBlankContent(Line line) {
        for (Span span : line.spans()) {
            if (!span.content().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasReversedSpan(Line line, Set<Style> plainStyles) {
        Set<Style> reversed = plainStyles.stream()
            .map(Style::reversed)
            .collect(Collectors.toSet());
        return containsAnyStyle(line, reversed) && hasNonBlankContent(line);
    }

    private static boolean containsAnyStyle(Line line, Set<Style> expected) {
        for (Span span : line.spans()) {
            if (expected.contains(span.style())) {
                return true;
            }
        }
        return false;
    }

    private static Set<Style> spanStyles(Line line) {
        return line.spans().stream()
            .filter(span -> !span.content().isBlank())
            .map(Span::style)
            .collect(Collectors.toSet());
    }

    private static int countLines(String source) {
        return source.split("\n", -1).length;
    }

    private static String syntheticSource(int lineCount) {
        List<String> lines = new ArrayList<>();
        lines.add("package dev.omatheusmesmo.spike30.bench;");
        int index = 0;
        while (lines.size() < lineCount) {
            String name = "Generated" + (++index);
            lines.add("/** Generated class " + name + " for the benchmark corpus. */");
            lines.add("final class " + name + " {");
            lines.add("    private static final String LABEL_" + index + " = \"constant-" + index + "\";");
            lines.add("    /* block comment around the generated field */");
            lines.add("    private long field_" + index + " = " + index + "L;");
            lines.add("    long compute_" + index + "(int a, double b) {");
            lines.add("        if (field_" + index + " > 0 && b < 1.0) {");
            lines.add("            return (long) (a * b) + field_" + index + ";");
            lines.add("        }");
            lines.add("        return -1;");
            lines.add("    }");
            lines.add("}");
        }
        while (lines.size() > lineCount) {
            lines.remove(lines.size() - 1);
        }
        return String.join("\n", lines);
    }
}
