package dev.omatheusmesmo.vintelli.tui;

import static dev.omatheusmesmo.vintelli.tui.ToolWindowInteractionAssertions.assertActivationParity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.paragraph.Paragraph;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

class ToolWindowInteractionAssertionsTest {

    private static final Size VIEWPORT = new Size(90, 20);
    private static final String TOOL_ID = "interaction-contract-probe";
    private static final String PROBE_SELECT_NEXT = "interaction.select-next";
    private static final String PROBE_ACTIVATE = "interaction.activate";

    @Test
    void keyboardAndPointerActivateTheSameSelectionThroughNamedActions() throws Exception {
        ToolWindowInteractionAssertions.Observation expected = new ToolWindowInteractionAssertions.Observation(
                "Second", PROBE_ACTIVATE, TOOL_ID, 0, "Second");

        assertActivationParity("probe list activation", expected,
                () -> newSession(),
                session -> {
                    ToolWindowPilotFixture fixture = session.fixture();
                    fixture.press('1');
                    fixture.press(KeyCode.DOWN);
                    fixture.press(KeyCode.ENTER);
                },
                () -> newSession(),
                session -> {
                    ToolWindowPilotFixture fixture = session.fixture();
                    var pilot = fixture.pilot();
                    fixture.press('1');
                    Rect area = pilot.findElement("tool-" + TOOL_ID);
                    pilot.click(area.x() + 2, area.y() + 1);
                    pilot.click(area.x() + 20, area.y() + 2);
                });
    }

    @Test
    void aMissingPointerSelectionFailsForTheExpectedBehavior() throws Exception {
        ToolWindowInteractionAssertions.Observation expected = new ToolWindowInteractionAssertions.Observation(
                "Second", PROBE_ACTIVATE, TOOL_ID, 0, "Second");

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> assertActivationParity("probe list activation", expected,
                        () -> newSession(),
                        session -> {
                            ToolWindowPilotFixture fixture = session.fixture();
                            fixture.press('1');
                            fixture.press(KeyCode.DOWN);
                            fixture.press(KeyCode.ENTER);
                        },
                        () -> newSession(true),
                        session -> {
                            ToolWindowPilotFixture fixture = session.fixture();
                            var pilot = fixture.pilot();
                            fixture.press('1');
                            Rect area = pilot.findElement("tool-" + TOOL_ID);
                            pilot.click(area.x() + 2, area.y() + 1);
                            pilot.click(area.x() + 20, area.y() + 2);
                        }));

        assertTrue(failure.getMessage().contains("probe list activation pointer behavior"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Rendered buffer:"), failure.getMessage());
    }

    @Test
    void pointerHitTestingUsesTheResizedRenderedArea() throws Exception {
        ToolWindowInteractionAssertions.Observation expected = new ToolWindowInteractionAssertions.Observation(
                "Second", PROBE_ACTIVATE, TOOL_ID, 0, "Second");

        assertActivationParity("resized list activation", expected,
                () -> newSession(),
                session -> {
                    ToolWindowPilotFixture fixture = session.fixture();
                    fixture.press('1');
                    fixture.press(KeyCode.DOWN);
                    fixture.press(KeyCode.ENTER);
                },
                () -> newSession(),
                session -> {
                    ToolWindowPilotFixture fixture = session.fixture();
                    var pilot = fixture.pilot();
                    fixture.press('1');
                    fixture.resize(44, 12);
                    Rect area = pilot.findElement("tool-" + TOOL_ID);
                    pilot.click(area.x() + 2, area.y() + 1);
                    pilot.click(area.x() + 20, area.y() + 2);
                });
    }

    @Test
    void outOfBoundsPointerInputDoesNotSelectOrActivate() throws Exception {
        try (ToolWindowInteractionAssertions.Session session = newSession()) {
            ToolWindowPilotFixture fixture = session.fixture();
            Rect area = fixture.pilot().findElement("tool-" + TOOL_ID);
            fixture.pilot().click(area.x() - 1, area.y() + 1);
            fixture.pilot().pause();

            ToolWindowInteractionAssertions.Observation actual = session.observation().get();
            assertEquals("First", actual.selectedItem());
            assertEquals("", actual.namedAction());
        }
    }

    @Test
    void unsupportedRightButtonGestureHasNoSideEffects() throws Exception {
        try (ToolWindowInteractionAssertions.Session session = newSession()) {
            ToolWindowPilotFixture fixture = session.fixture();
            Rect area = fixture.pilot().findElement("tool-" + TOOL_ID);
            fixture.pilot().mousePress(MouseButton.RIGHT, area.x() + 2, area.y() + 2);
            fixture.pilot().mouseRelease(MouseButton.RIGHT, area.x() + 2, area.y() + 2);
            fixture.pilot().pause();

            ToolWindowInteractionAssertions.Observation actual = session.observation().get();
            assertEquals("First", actual.selectedItem());
            assertEquals("", actual.namedAction());
        }
    }

    @Test
    void wheelScrollingKeepsVisibleRowHitTestingAlignedWithTheRenderedList() throws Exception {
        ToolWindowInteractionAssertions.Observation expected = new ToolWindowInteractionAssertions.Observation(
                "Item 10", PROBE_ACTIVATE, TOOL_ID, 4, "Item 10");

        assertActivationParity("scrolled list activation", expected,
                () -> newScrollSession(),
                session -> {
                    ToolWindowPilotFixture fixture = session.fixture();
                    fixture.press('1');
                    for (int index = 0; index < 10; index++) {
                        fixture.press(KeyCode.DOWN);
                    }
                    fixture.press(KeyCode.ENTER);
                },
                () -> newScrollSession(),
                session -> {
                    ToolWindowPilotFixture fixture = session.fixture();
                    var pilot = fixture.pilot();
                    fixture.press('1');
                    Rect area = pilot.findElement("tool-" + TOOL_ID);
                    for (int index = 0; index < 4; index++) {
                        fixture.scrollDown(area.x() + 1, area.y() + 1);
                    }
                    pilot.click(area.x() + 1, area.y() + 6);
                    pilot.click(area.x() + 20, area.y() + area.height() - 1);
                });
    }

    private static ToolWindowInteractionAssertions.Session newSession() throws Exception {
        return newSession(false);
    }

    private static ToolWindowInteractionAssertions.Session newSession(boolean omitMouseSelection) throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        ContractProbeWindow window = new ContractProbeWindow(omitMouseSelection);
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState(), themes);
        ToolWindowPilotFixture fixture = ToolWindowPilotFixture.open(shell, VIEWPORT);
        fixture.pilot().pause(Duration.ofMillis(100));
        return new ToolWindowInteractionAssertions.Session(fixture, () -> {
            String rendered = fixture.renderedText();
            String renderedSelection = rendered.contains("› Second") ? "Second" : "First";
            return new ToolWindowInteractionAssertions.Observation(
                    window.selectedItem(), window.activationAction(), window.focusedWindow(), 0, renderedSelection,
                    window.diagnostic(fixture.pilot().findElement("tool-" + TOOL_ID), rendered));
        });
    }

    private static ToolWindowInteractionAssertions.Session newScrollSession() throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        ScrollProbeWindow window = new ScrollProbeWindow();
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState(), themes);
        ToolWindowPilotFixture fixture = ToolWindowPilotFixture.open(shell, VIEWPORT);
        fixture.pilot().pause(Duration.ofMillis(100));
        return new ToolWindowInteractionAssertions.Session(fixture, () -> {
            String selectedItem = window.selectedItem();
            String rendered = fixture.renderedText();
            String renderedSelection = rendered.contains("› " + selectedItem) ? selectedItem : "";
            return new ToolWindowInteractionAssertions.Observation(
                    selectedItem, window.activationAction(), window.focusedWindow(),
                    window.scrollOffset(), renderedSelection,
                    window.diagnostic(fixture.pilot().findElement("tool-" + TOOL_ID), rendered));
        });
    }

    private static final class ContractProbeWindow implements ToolWindow {

        private final boolean omitMouseSelection;
        private int selectedIndex;
        private String activationAction = "";
        private String focusedWindow = "";
        private Rect lastArea = Rect.ZERO;
        private final List<String> interactionTrace = new ArrayList<>();

        private ContractProbeWindow(boolean omitMouseSelection) {
            this.omitMouseSelection = omitMouseSelection;
        }

        @Override
        public String id() {
            return TOOL_ID;
        }

        @Override
        public String title() {
            return "Interaction Contract";
        }

        @Override
        public char shortcut() {
            return '1';
        }

        @Override
        public int ordinal() {
            return 0;
        }

        @Override
        public void render(Frame frame, Rect area) {
            lastArea = area;
            frame.renderWidget(Paragraph.builder()
                    .text(Text.from((selectedIndex == 0 ? "› First" : "  First")
                            + "\n" + (selectedIndex == 1 ? "› Second" : "  Second") + "\nRun selected"))
                    .build(), area);
        }

        @Override
        public boolean capturesInput() {
            return false;
        }

        @Override
        public boolean handleKey(KeyEvent event) {
            String action = actionForKey(event);
            return action != null && performAction(action, event);
        }

        @Override
        public String actionForKey(KeyEvent event) {
            interactionTrace.add("key " + event + " focus=" + focusedWindow);
            if (event.isKey(KeyCode.DOWN)) {
                return PROBE_SELECT_NEXT;
            }
            if (event.isKey(KeyCode.ENTER)) {
                return PROBE_ACTIVATE;
            }
            return null;
        }

        @Override
        public boolean shouldHandleKeyBeforeShell(KeyEvent event) {
            return actionForKey(event) != null;
        }

        @Override
        public String actionForMouse(MouseEvent event) {
            interactionTrace.add("mouse action " + event.kind() + " at " + event.x() + "," + event.y()
                    + " in " + lastArea);
            return inside(event) && event.isClick() && event.y() == lastArea.y() + 2 ? PROBE_ACTIVATE : null;
        }

        @Override
        public boolean handleMouse(MouseEvent event) {
            interactionTrace.add("mouse handler " + event.kind() + " at " + event.x() + "," + event.y()
                    + " in " + lastArea);
            if (omitMouseSelection || !inside(event) || !event.isClick() || event.y() != lastArea.y() + 1) {
                return false;
            }
            selectedIndex = 1;
            return true;
        }

        @Override
        public boolean performAction(String actionId) {
            return performAction(actionId, null);
        }

        @Override
        public boolean performAction(String actionId, KeyEvent sourceKey) {
            interactionTrace.add("perform " + actionId + " source=" + sourceKey);
            if (PROBE_SELECT_NEXT.equals(actionId)) {
                selectedIndex = Math.min(1, selectedIndex + 1);
                focusedWindow = id();
                return true;
            }
            if (PROBE_ACTIVATE.equals(actionId)) {
                activationAction = actionId;
                focusedWindow = id();
                return true;
            }
            return false;
        }

        private String selectedItem() {
            return selectedIndex == 1 ? "Second" : "First";
        }

        private String activationAction() {
            return activationAction;
        }

        private String focusedWindow() {
            return focusedWindow;
        }

        private String diagnostic(Rect pilotArea, String rendered) {
            return "Pilot area=" + pilotArea + ", render area=" + lastArea + "\n"
                    + String.join("\n", interactionTrace) + "\nRendered buffer:\n" + rendered;
        }

        private boolean inside(MouseEvent event) {
            return event.x() >= lastArea.x() && event.x() < lastArea.x() + lastArea.width()
                    && event.y() >= lastArea.y() && event.y() < lastArea.y() + lastArea.height();
        }
    }

    private static final class ScrollProbeWindow implements ToolWindow {

        private final List<String> items = new ArrayList<>();
        private int selectedIndex;
        private int scrollOffset;
        private Rect lastArea = Rect.ZERO;
        private String activationAction = "";
        private String focusedWindow = "";
        private final List<String> interactionTrace = new ArrayList<>();

        private ScrollProbeWindow() {
            for (int index = 0; index < 20; index++) {
                items.add("Item " + index);
            }
        }

        @Override public String id() { return TOOL_ID; }
        @Override public String title() { return "Scroll Contract"; }
        @Override public char shortcut() { return '1'; }
        @Override public int ordinal() { return 0; }

        @Override
        public boolean handleKey(KeyEvent event) {
            String action = actionForKey(event);
            return action != null && performAction(action, event);
        }

        @Override
        public void render(Frame frame, Rect area) {
            lastArea = area;
            int visibleRows = Math.max(0, area.height() - 1);
            StringBuilder lines = new StringBuilder();
            for (int row = 0; row < visibleRows && scrollOffset + row < items.size(); row++) {
                if (!lines.isEmpty()) {
                    lines.append('\n');
                }
                String item = items.get(scrollOffset + row);
                lines.append(scrollOffset + row == selectedIndex ? "› " : "  ").append(item);
            }
            if (!lines.isEmpty()) {
                lines.append('\n');
            }
            lines.append("Run selected");
            frame.renderWidget(Paragraph.builder().text(Text.from(lines.toString())).build(), area);
        }

        @Override
        public String actionForKey(KeyEvent event) {
            interactionTrace.add("key " + event + " focus=" + focusedWindow);
            if (event.isKey(KeyCode.DOWN)) {
                return PROBE_SELECT_NEXT;
            }
            if (event.isKey(KeyCode.ENTER)) {
                return PROBE_ACTIVATE;
            }
            return null;
        }

        @Override
        public boolean shouldHandleKeyBeforeShell(KeyEvent event) {
            return actionForKey(event) != null;
        }

        @Override
        public String actionForMouse(MouseEvent event) {
            interactionTrace.add("mouse action " + event.kind() + " at " + event.x() + "," + event.y()
                    + " in " + lastArea);
            return inside(event) && event.isClick() && event.y() == lastArea.y() + lastArea.height() - 1
                    ? PROBE_ACTIVATE : null;
        }

        @Override
        public boolean handleMouse(MouseEvent event) {
            interactionTrace.add("mouse handler " + event.kind() + " at " + event.x() + "," + event.y()
                    + " in " + lastArea);
            if (!inside(event)) {
                return false;
            }
            if (event.isScroll()) {
                if (event.kind() != MouseEventKind.SCROLL_UP && event.kind() != MouseEventKind.SCROLL_DOWN) {
                    return false;
                }
                int visibleRows = Math.max(1, lastArea.height() - 1);
                if (event.kind() == MouseEventKind.SCROLL_DOWN) {
                    scrollOffset = Math.min(items.size() - visibleRows, scrollOffset + 1);
                } else {
                    scrollOffset = Math.max(0, scrollOffset - 1);
                }
                return true;
            }
            int row = event.y() - lastArea.y();
            int visibleRows = Math.max(0, lastArea.height() - 1);
            if (event.isClick() && row >= 0 && row < visibleRows) {
                selectedIndex = Math.min(items.size() - 1, scrollOffset + row);
                return true;
            }
            return false;
        }

        @Override
        public boolean performAction(String actionId) {
            interactionTrace.add("perform " + actionId);
            if (!PROBE_ACTIVATE.equals(actionId)) {
                return false;
            }
            activationAction = actionId;
            focusedWindow = id();
            return true;
        }

        @Override
        public boolean performAction(String actionId, KeyEvent sourceKey) {
            interactionTrace.add("perform " + actionId + " source=" + sourceKey);
            if (PROBE_SELECT_NEXT.equals(actionId)) {
                selectedIndex = Math.min(items.size() - 1, selectedIndex + 1);
                int visibleRows = Math.max(1, lastArea.height() - 1);
                if (selectedIndex >= scrollOffset + visibleRows) {
                    scrollOffset = selectedIndex - visibleRows + 1;
                }
                focusedWindow = id();
                return true;
            }
            if (PROBE_ACTIVATE.equals(actionId)) {
                activationAction = actionId;
                focusedWindow = id();
                return true;
            }
            return false;
        }

        private String selectedItem() {
            return items.get(selectedIndex);
        }

        private String activationAction() {
            return activationAction;
        }

        private String focusedWindow() {
            return focusedWindow;
        }

        private int scrollOffset() {
            return scrollOffset;
        }

        private String diagnostic(Rect pilotArea, String rendered) {
            return "Pilot area=" + pilotArea + ", render area=" + lastArea + "\n"
                    + String.join("\n", interactionTrace) + "\nRendered buffer:\n" + rendered;
        }

        private boolean inside(MouseEvent event) {
            return event.x() >= lastArea.x() && event.x() < lastArea.x() + lastArea.width()
                    && event.y() >= lastArea.y() && event.y() < lastArea.y() + lastArea.height();
        }
    }
}
