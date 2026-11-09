package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.tui.StatusBarLayout.Line;
import dev.omatheusmesmo.vintelli.tui.StatusBarLayout.Placement;
import dev.omatheusmesmo.vintelli.tui.StatusBarLayout.Segment;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.app.ToolkitTestRunner;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.pilot.Pilot;
import org.junit.jupiter.api.Test;

class StatusBarLayoutTest {

    private static final int STATUS_ROW = 5;

    private static final String DIVIDER = " › ";
    private static final String GAP = "  ";
    private static final String LEFT_FULL = "vintelli › vintelli-tui › VintelliShell.java";
    private static final String LEFT_WITHOUT_ROOT = "… › vintelli-tui › VintelliShell.java";
    private static final String RIGHT = "Ready  246:5  LF  UTF-8  4 spaces  RO";

    @Test
    void keepsTheBreadcrumbOnTheLeftAndTheEditorWidgetsOnTheRightAtOneHundredFiftyColumns() {
        Line line = StatusBarLayout.layout(crumbSegments(), DIVIDER, editorWidgets(), GAP, 150);

        String row = line.text();
        assertTrue(row.startsWith(LEFT_FULL), row);
        assertTrue(row.endsWith(RIGHT), row);
        assertEquals(150, row.length(), row);
    }

    @Test
    void dropsTheLeftmostBreadcrumbAncestorFirstAndKeepsTheFileAtEightyColumns() {
        Line line = StatusBarLayout.layout(crumbSegments(), DIVIDER, editorWidgets(), GAP, 80);

        String row = line.text();
        assertTrue(row.startsWith(LEFT_WITHOUT_ROOT), row);
        assertTrue(row.endsWith(RIGHT), row);
        assertEquals(80, row.length(), row);
        assertFalse(row.contains("vintelli ›"), row);
    }

    @Test
    void shrinksTheMessageToItsShortTextBeforeAnyWidgetIsDropped() {
        List<Segment> right = List.of(
                new Segment(StatusBarLayout.MESSAGE_ID, 80, "Vintelli ready and waiting", "Ready"),
                Segment.of("caret", 100, "1:1"),
                Segment.of("line-separator", 45, "LF"));

        Line line = StatusBarLayout.layout(List.of(), DIVIDER, right, GAP, 20);

        assertEquals("      Ready  1:1  LF", line.text());
    }

    @Test
    void dropsTheLeastImportantWidgetFirstWhenTheMessageCannotShrinkFurther() {
        List<Segment> right = List.of(
                new Segment(StatusBarLayout.MESSAGE_ID, 80, "Vintelli ready and waiting", "Ready"),
                Segment.of("caret", 100, "1:1"),
                Segment.of("line-separator", 45, "LF"));

        Line line = StatusBarLayout.layout(List.of(), DIVIDER, right, GAP, 12);

        assertEquals("  Ready  1:1", line.text());
    }

    @Test
    void neverOverlapsAndNeverExceedsTheWidthAtAnyWidth() {
        for (int width = 1; width <= 160; width++) {
            Line line = StatusBarLayout.layout(crumbSegments(), DIVIDER, editorWidgets(), GAP, width);

            int cells = 0;
            boolean caretDrawn = false;
            for (Placement placement : line.placements()) {
                if (placement.text().isEmpty()) {
                    continue;
                }
                assertTrue(placement.x() >= cells, "overlap at width " + width + ": " + line.text());
                assertTrue(placement.right() <= width, "overflow at width " + width + ": " + line.text());
                cells = placement.right();
                caretDrawn |= "caret".equals(placement.id());
            }
            assertTrue(Text.raw(line.text()).width() <= width, "width " + width + ": " + line.text());
            if (width >= 5) {
                assertTrue(caretDrawn, "the caret survives at width " + width + ": " + line.text());
            }
        }
    }

    @Test
    void truncatesWithinAWidgetWhenTheTerminalIsExtremelyNarrow() {
        assertEquals("…", StatusBarLayout.truncate("Tool window: Run", 1));
    }

    @Test
    void truncatesByTerminalCellsWithoutSplittingWideOrCombiningCharacters() {
        String emojiText = StatusBarLayout.truncate("A😀Z", 3);
        String combiningText = StatusBarLayout.truncate("é界Z", 2);

        assertTrue(Text.raw(emojiText).width() <= 3, emojiText);
        assertFalse(Character.isHighSurrogate(emojiText.charAt(emojiText.length() - 1)), emojiText);
        assertTrue(Text.raw(combiningText).width() <= 2, combiningText);
        assertFalse(Character.isLowSurrogate(combiningText.charAt(combiningText.length() - 1)), combiningText);
    }

    @Test
    void pilotDrawsTheBreadcrumbAndTheShellMessageOnOneRowAtEightyColumns() throws Exception {
        StatusBar bar = statusBar(() -> "Vintelli ready", breadcrumbs());
        try (ToolkitTestRunner test = ToolkitTestRunner.runTest(
                () -> new StatusBarElement(bar, index -> { }), new Size(80, 1))) {
            Pilot pilot = test.pilot();
            pilot.pause(Duration.ofMillis(100));

            assertTrue(pilot.hasElement("status-bar"));
            String row = render(test, bar, 80);
            assertTrue(row.startsWith(LEFT_FULL), row);
            assertTrue(row.endsWith("Vintelli ready"), row);
            assertEquals(80, row.length(), row);
        }
    }

    @Test
    void aClickAnswersTheBreadcrumbSegmentTheIndicatorOrTheOpenPopupOnly() {
        List<Integer> clicked = new ArrayList<>();
        StatusBarElement element = new StatusBarElement(degradedBar(() -> new StatusBar.Navigation(breadcrumbs(), 2,
                false)), clicked::add);
        Buffer buffer = drawOn(element, 80, 6);

        assertTrue(rowText(buffer, STATUS_ROW).contains("! 1 provider degraded"), rowText(buffer, STATUS_ROW));
        assertTrue(element.click(13, STATUS_ROW), "a click on the second breadcrumb segment is answered");
        assertEquals(List.of(1), clicked);

        assertTrue(element.click(rowText(buffer, STATUS_ROW).indexOf("provider"), STATUS_ROW),
                "a click on the indicator is answered");
        assertTrue(element.popupOpen(), "the indicator opens the popup");
        Buffer popupFrame = drawOn(element, 80, 6);
        assertTrue(rowText(popupFrame, 3).contains("Providers"), rowText(popupFrame, 3));

        assertTrue(element.click(79, 4), "a click inside the popup stays with the status bar");
        assertTrue(element.popupOpen(), "a click inside the popup keeps it open");
        assertFalse(element.click(0, 2), "a click outside the popup is not consumed");
        assertFalse(element.popupOpen(), "a click outside the popup closes it");
        assertEquals(List.of(1), clicked, "closing the popup never selects a segment");
    }

    @Test
    void escapeClosesAnOpenPopupAndLeavesOtherKeysAlone() {
        StatusBarElement element = new StatusBarElement(degradedBar(() -> StatusBar.Navigation.NONE), index -> { });
        Buffer buffer = drawOn(element, 80, 6);
        element.click(rowText(buffer, STATUS_ROW).indexOf("provider"), STATUS_ROW);

        assertFalse(element.escape(KeyEvent.ofChar('x')), "other keys are not taken");
        assertTrue(element.popupOpen());
        assertTrue(element.escape(KeyEvent.ofKey(KeyCode.ESCAPE)));
        assertFalse(element.popupOpen());
    }

    private static StatusBar degradedBar(Supplier<StatusBar.Navigation> navigation) {
        return new StatusBar(() -> "Ready", () -> false, FixedStatusRegistry.withStatuses(List.of(
                CapabilityStatus.unavailable(CapabilityType.TERMINAL, "pty is missing"))), null, List.of(),
                new StatusBar.Glyphs(false, IconMode.ASCII), navigation);
    }

    private static Buffer drawOn(StatusBarElement element, int width, int height) {
        Buffer buffer = Buffer.empty(new Rect(0, 0, width, height));
        element.render(Frame.forTesting(buffer), new Rect(0, height - 1, width, 1),
                DefaultRenderContext.createEmpty());
        return buffer;
    }

    private static String rowText(Buffer buffer, int row) {
        StringBuilder text = new StringBuilder();
        for (int x = 0; x < buffer.area().width(); x++) {
            text.append(buffer.get(x, row).symbol());
        }
        return text.toString();
    }

    @Test
    void pilotReadsMessageUpdatesFromTheSourceWithoutRestarting() throws Exception {
        AtomicReference<String> message = new AtomicReference<>("Run: old-provider");
        StatusBar bar = statusBar(message::get, List.of());
        try (ToolkitTestRunner test = ToolkitTestRunner.runTest(
                () -> new StatusBarElement(bar, index -> { }), new Size(40, 1))) {
            Pilot pilot = test.pilot();
            pilot.pause(Duration.ofMillis(100));
            assertTrue(render(test, bar, 40).contains("Run: old-provider"));

            message.set("Run: new-provider");
            pilot.resize(39, 1);
            pilot.pause(Duration.ofMillis(100));

            assertTrue(render(test, bar, 40).contains("Run: new-provider"));
        }
    }

    private static StatusBar statusBar(Supplier<String> message, List<StatusBar.Breadcrumb> crumbs) {
        return new StatusBar(message, () -> false, null, null, List.of(),
                new StatusBar.Glyphs(true, IconMode.NERD),
                () -> new StatusBar.Navigation(crumbs, crumbs.size() - 1, false));
    }

    private static List<StatusBar.Breadcrumb> breadcrumbs() {
        return List.of(
                new StatusBar.Breadcrumb("vintelli", Path.of("vintelli"), -1),
                new StatusBar.Breadcrumb("vintelli-tui", Path.of("vintelli", "vintelli-tui"), -1),
                new StatusBar.Breadcrumb("VintelliShell.java", Path.of("VintelliShell.java"), -1));
    }

    private static List<Segment> crumbSegments() {
        return List.of(
                Segment.of("crumb-0", 10, "vintelli"),
                Segment.of("crumb-1", 11, "vintelli-tui"),
                Segment.of("crumb-2", 90, "VintelliShell.java"));
    }

    private static List<Segment> editorWidgets() {
        return List.of(
                new Segment(StatusBarLayout.MESSAGE_ID, 80, "Ready", "Ready"),
                Segment.of("caret", 100, "246:5"),
                Segment.of("line-separator", 45, "LF"),
                Segment.of("encoding", 45, "UTF-8"),
                Segment.of("indent", 30, "4 spaces"),
                Segment.of("read-only", 40, "RO"));
    }

    private static String render(ToolkitTestRunner test, StatusBar bar, int width) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        test.runner().runOnRenderThread(() -> {
            try {
                Rect area = new Rect(0, 0, width, 1);
                Buffer buffer = Buffer.empty(area);
                new StatusBarElement(bar, index -> { }).render(Frame.forTesting(buffer), area,
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
