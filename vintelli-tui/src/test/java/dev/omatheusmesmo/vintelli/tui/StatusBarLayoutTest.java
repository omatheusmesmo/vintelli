package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidget;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.layout.Size;
import dev.tamboui.layout.Rect;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.app.ToolkitTestRunner;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.tui.pilot.Pilot;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.List;

import org.junit.jupiter.api.Test;

class StatusBarLayoutTest {

    @Test
    void ordersWidgetsByOrderThenId() {
        List<StatusBarWidget> widgets = List.of(
                new StatusBarWidget("z-provider", 10, "Terminal: local", "T:local"),
                new StatusBarWidget("shell", 0, "Tool window: Run", "Tool: Run"),
                new StatusBarWidget("a-provider", 10, "Run: local", "R:local"));

        assertEquals("Tool window: Run │ Run: local │ Terminal: local", StatusBarLayout.render(widgets, 100));
    }

    @Test
    void keepsCompactProviderNamesVisibleAndTruncatesTheLastWidget() {
        List<StatusBarWidget> widgets = List.of(
                new StatusBarWidget("shell", 0, "Tool window: Run", "Tool: Run"),
                new StatusBarWidget("provider", 10, "Problems: jdtls (Java language server diagnostics provider)",
                        "P:jdtls"),
                new StatusBarWidget("next", 20, "VCS: git", "V:git"));

        String rendered = StatusBarLayout.render(widgets, 34);

        assertTrue(rendered.startsWith("Tool window: Run │ P:jdtls │ V:git"), rendered);
        assertTrue(rendered.length() <= 34, rendered);
    }

    @Test
    void keepsLongBranchAndProviderStatesVisibleWithCaretAtEightyColumns() {
        List<StatusBarWidget> widgets = List.of(
                new StatusBarWidget("problems", 13, "Problems: jdtls diagnostics", "P:jdtls"),
                new StatusBarWidget("read-file", 12, "Read File: backup-reader", "RF:backup…"),
                new StatusBarWidget("terminal", 11, "Terminal: local", "T:local"),
                new StatusBarWidget("run", 10, "Run: local", "R:local"),
                new StatusBarWidget("caret", 8, "Caret L12:C1", "L12:C1"),
                new StatusBarWidget("vcs-branch", 7, "Branch: feat/issue-121-impl", "Git:feat/issue-121-impl"),
                new StatusBarWidget("idea", 5, "IDEA MCP: disconnected", "IDEA:off"),
                new StatusBarWidget("shell", 0, "Project files loaded", "Ready"));

        String rendered = StatusBarLayout.render(widgets, 80);

        assertEquals("Ready │ IDEA:off │ Git:feat… │ L12:C1 │ R:local │ T:local │ RF:backup… │ P:jdtls",
                rendered);
        assertTrue(Text.raw(rendered).width() <= 80, rendered);
    }

    @Test
    void truncatesWithinAWidgetWhenTheTerminalIsExtremelyNarrow() {
        assertEquals("…", StatusBarLayout.render(
                List.of(new StatusBarWidget("shell", 0, "Tool window: Run", "Tool: Run")), 1));
    }

    @Test
    void compactWidgetsRespectCellWidthsAcrossSeparators() {
        List<StatusBarWidget> widgets = List.of(
                new StatusBarWidget("first", 0, "界界", "界界"),
                new StatusBarWidget("second", 1, "tail", "tail"));

        String rendered = StatusBarLayout.render(widgets, 8);

        assertTrue(Text.raw(rendered).width() <= 8, rendered);
    }

    @Test
    void truncatesByTerminalCellsWithoutSplittingWideOrCombiningCharacters() {
        String emojiText = StatusBarLayout.truncate("A😀Z", 3);
        String combiningText = StatusBarLayout.truncate("e\u0301界Z", 2);

        assertTrue(Text.raw(emojiText).width() <= 3, emojiText);
        assertFalse(Character.isHighSurrogate(emojiText.charAt(emojiText.length() - 1)), emojiText);
        assertTrue(Text.raw(combiningText).width() <= 2, combiningText);
        assertFalse(Character.isLowSurrogate(combiningText.charAt(combiningText.length() - 1)), combiningText);
    }

    @Test
    void pilotRendersOrderedCompactWidgetsWithinTheNarrowTerminalWidth() throws Exception {
        List<StatusBarWidget> widgets = List.of(
                new StatusBarWidget("vcs", 20, "VCS: git", "V:git"),
                new StatusBarWidget("shell", 0, "Tool window: Run", "Tool:Run"),
                new StatusBarWidget("problems", 10, "Problems: jdtls (Java diagnostics provider)", "P:jdtls"));
        try (ToolkitTestRunner test = ToolkitTestRunner.runTest(
                () -> new StatusBarElement(() -> widgets), new Size(34, 1))) {
            Pilot pilot = test.pilot();
            pilot.pause(Duration.ofMillis(100));

            assertTrue(pilot.hasElement("status-bar"));
            String terminalOutput = render(test, widgets, 34);
            assertTrue(terminalOutput.contains("Tool window: Run"), terminalOutput);
            assertTrue(terminalOutput.contains("P:jdtls"), terminalOutput);
            assertTrue(terminalOutput.contains("V:git"), terminalOutput);
        }
    }

    @Test
    void pilotReadsProviderUpdatesFromTheWidgetSourceWithoutRestarting() throws Exception {
        AtomicReference<List<StatusBarWidget>> widgets = new AtomicReference<>(List.of(
                new StatusBarWidget("run", 10, "Run: old-provider", "R:old-provider")));
        AtomicReference<List<StatusBarWidget>> lastRendered = new AtomicReference<>();

        try (ToolkitTestRunner test = ToolkitTestRunner.runTest(
                () -> new StatusBarElement(() -> {
                    List<StatusBarWidget> current = widgets.get();
                    lastRendered.set(current);
                    return current;
                }), new Size(40, 1))) {
            Pilot pilot = test.pilot();
            pilot.pause(Duration.ofMillis(100));
            assertTrue(render(test, lastRendered.get(), 40).contains("Run: old-provider"));

            widgets.set(List.of(new StatusBarWidget("run", 10, "Run: new-provider", "R:new-provider")));
            pilot.resize(39, 1);
            pilot.pause(Duration.ofMillis(100));

            assertTrue(render(test, lastRendered.get(), 40).contains("Run: new-provider"));
        }
    }

    private static String render(ToolkitTestRunner test, List<StatusBarWidget> widgets, int width) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        test.runner().runOnRenderThread(() -> {
            try {
                Rect area = new Rect(0, 0, width, 1);
                Buffer buffer = Buffer.empty(area);
                new StatusBarElement(() -> widgets).render(Frame.forTesting(buffer), area,
                        DefaultRenderContext.createEmpty());
                StringBuilder text = new StringBuilder();
                for (int x = 0; x < width; x++) {
                    text.append(buffer.get(x, 0).symbol());
                }
                result.complete(text.toString());
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.get(5, TimeUnit.SECONDS);
    }
}
