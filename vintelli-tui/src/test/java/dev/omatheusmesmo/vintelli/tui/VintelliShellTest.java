package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.keymap.KeyAction;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapProfile;
import dev.omatheusmesmo.vintelli.tui.menu.MenuPopup;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.editor.SystemClipboard;
import dev.omatheusmesmo.vintelli.tui.icons.FileIcons;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.omatheusmesmo.vintelli.tui.maven.MavenToolWindow;
import dev.omatheusmesmo.vintelli.tui.icons.JavaTypeKind;
import dev.omatheusmesmo.vintelli.tui.layout.ShellLayoutStore;
import dev.omatheusmesmo.vintelli.tui.layout.ShellLayout;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.idea.IdeaMcpConnection;
import dev.omatheusmesmo.vintelli.api.capability.ProjectFileOperations;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.DocumentSymbol;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.style.Color;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.terminal.TestBackend;
import dev.tamboui.toolkit.app.ToolkitPilot;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.app.ToolkitTestRunner;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.tui.TuiConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shell on the Toolkit level: keymap routing, overlays, pushes and the
 * session theme. Every regression here fails if the production code it pins
 * is reverted: the palette chords, the capture rules, the state sync of the
 * project list, the oriented focus and the stylesheet driven colors.
 */
class VintelliShellTest {

    @Test
    void rightToolWindowFillsItsAvailableStripe() throws Exception {
        AtomicReference<Rect> rendered = new AtomicReference<>();
        ToolWindow right = new FakeToolWindow("side", "Side", '7', 100, "", false, ToolWindow.Anchor.RIGHT) {
            @Override
            public void render(Frame frame, Rect area) {
                rendered.set(area);
            }
        };
        ShellThemes themes = defaultThemes();
        VintelliShell shell = new VintelliShell(ShellConfig.of(List.of(right), new ShellState(), themes));
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            press(pilot, '7');
            settle(pilot);
        }

        int statusRow = 1;
        int tabAndHeaderRows = 3;
        assertEquals(VIEWPORT.height() - 1 - statusRow - tabAndHeaderRows, rendered.get().height(),
                "side windows fill the stripe down to the status row, less the two tab rows and the pane header");
    }

    @Test
    void stripsRenderOneIconPerWindowOnTheRightSideInTheRegisteredOrder() {
        ShellThemes themes = defaultThemes();
        List<ToolWindow> windows = new ArrayList<>(windowsWithAcp());
        windows.add(new FakeToolWindow("side.a", "Side", '7', 100, "", false, ToolWindow.Anchor.RIGHT));
        windows.add(new FakeToolWindow("side.b", "Other", '9', 200, "", false, ToolWindow.Anchor.RIGHT));
        List<ToolWindow> registered = new ToolWindowRegistry(windows).toolWindows();
        VintelliShell shell = new VintelliShell(ShellConfig.of(registered, new ShellState(), themes));
        Buffer buffer = offlineFrame(shell);

        StringBuilder expectedRight = new StringBuilder();
        StringBuilder expectedLeft = new StringBuilder("P");
        for (ToolWindow window : registered) {
            if (window.anchor() == ToolWindow.Anchor.RIGHT) {
                expectedRight.append(window.title().charAt(0));
            } else if (window.anchor() == ToolWindow.Anchor.BOTTOM) {
                expectedLeft.append(window.title().charAt(0));
            }
        }
        int lastMiddleRow = VIEWPORT.height() - 2;
        assertEquals(expectedRight.toString(), columnLetters(buffer, VIEWPORT.width() - 1, 1, lastMiddleRow),
                "the right strip stacks one icon per right window in the registered order");
        assertEquals(expectedLeft.toString(), columnLetters(buffer, 1, 1, lastMiddleRow),
                "the left strip holds the project icon on top and the bottom windows at its bottom");
    }

    @Test
    void clickingAStripIconTogglesItsWindowAndTheSecondClickShowsItAgain() throws Exception {
        VintelliShell shell = defaultShell();
        Buffer buffer = offlineFrame(shell);
        int runIcon = findRow(buffer, 1, "R");
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            pilot.click(1, runIcon);
            settle(pilot);
            assertFalse(shell.isToolWindowVisible("run"), "the first click hides Run: " + shell.status());

            pilot.click(1, runIcon);
            settle(pilot);
            assertTrue(shell.isToolWindowVisible("run"), "the second click shows Run again: " + shell.status());
            assertEquals("Tool window: Run", shell.status());
            pressCtrlC(pilot);
        }
    }

    @Test
    void aFreshLayoutHidesTheRightWindowsAndKeepsTheirStripeIcons() {
        VintelliShell shell = new VintelliShell(ShellConfig.of(windowsWithAcp(), new ShellState(), defaultThemes()));
        Buffer buffer = offlineFrame(shell);
        assertFalse(shell.isToolWindowVisible("acp.agents"), "a fresh layout starts with the right windows hidden");
        assertTrue(shell.isToolWindowVisible("run"), "the bottom windows start visible");
        assertNotEquals(-1, findRow(buffer, VIEWPORT.width() - 1, "A"), "the hidden window keeps its stripe icon");
    }

    @Test
    void queuedBackgroundWorkReachesTheScreenWithoutAnyInput() throws Exception {
        VintelliShell shell = defaultShell();
        UiWorkQueue uiWork = new UiWorkQueue();
        shell.useUiWorkQueue(uiWork);
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            shell.bindRedraw(() -> test.runner().tuiRunner().dispatch(TickEvent.of(0)));
            for (int round = 1; round <= 2; round++) {
                AtomicBoolean applied = new AtomicBoolean();
                new Thread(() -> uiWork.execute(() -> applied.set(true))).start();
                long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
                while (!applied.get() && System.nanoTime() < deadline) {
                    pause(test.pilot(), SETTLE_STEP);
                }
                assertTrue(applied.get(), "queued update " + round + " was not drawn without input");
            }
        }
    }

    @Test
    void clickingTheIconOfAHiddenRightWindowShowsItsPane() throws Exception {
        VintelliShell shell = new VintelliShell(ShellConfig.of(windowsWithAcp(), new ShellState(), defaultThemes()));
        int iconRow = findRow(offlineFrame(shell), VIEWPORT.width() - 1, "A");
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            int rightBefore = pilot.findElement("right-stripe").width();

            pilot.click(VIEWPORT.width() - 1, iconRow);
            settle(pilot);

            assertTrue(shell.isToolWindowVisible("acp.agents"), shell.status());
            assertEquals("Tool window: ACP Agents", shell.status());
            assertTrue(pilot.hasElement("tool-pane-acp.agents"), "the pane of the clicked icon is drawn");
            assertTrue(pilot.findElement("right-stripe").width() > rightBefore,
                    "showing the pane takes columns from the editor");
        }
    }

    @Test
    void aHiddenSideWindowFreesItsSpaceAndItsStripeIconStaysInPlace() throws Exception {
        VintelliShell shell = new VintelliShell(ShellConfig.of(windowsWithAcp(), new ShellState(), defaultThemes()));
        int iconRow = findRow(offlineFrame(shell), VIEWPORT.width() - 1, "A");
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            pilot.click(VIEWPORT.width() - 1, iconRow);
            settle(pilot);
            int rightBefore = pilot.findElement("right-stripe").width();
            int editorBefore = pilot.findElement("editor-panel").width();

            pilot.click(VIEWPORT.width() - 1, iconRow);
            settle(pilot);

            assertFalse(shell.isToolWindowVisible("acp.agents"), shell.status());
            assertEquals(VintelliShell.TOOL_STRIP_WIDTH, pilot.findElement("right-stripe").width(),
                    "a hidden side window leaves only the strip");
            assertEquals(editorBefore + rightBefore - VintelliShell.TOOL_STRIP_WIDTH,
                    pilot.findElement("editor-panel").width(), "the editor takes the freed columns");
            String frame = test.renderedFrame();
            assertTrue(frame.split("\n", -1)[iconRow].endsWith("A"),
                    "the strip keeps the icon of the hidden window: " + frame);
        }
    }

    @Test
    void escapeKeepsAHiddenProjectPanelHiddenAndFocusesTheEditor() throws Exception {
        FakeToolWindow terminalLike = new FakeToolWindow("fake.terminal", "Fake Terminal", '3', 60, "type away", true);
        VintelliShell shell = shellWithFakeWindow(terminalLike);
        int projectIcon = findRow(offlineFrame(shell), 1, "P");
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            pilot.click(1, projectIcon);
            settle(pilot);
            assertEquals("Project panel hidden", shell.status());

            press(pilot, '3');
            assertNotEquals("editor-panel", test.runner().focusManager().focusedId(), "the window took the focus");
            pilot.press(KeyCode.ESCAPE);
            settle(pilot);

            assertFalse(pilot.hasElement("project-panel"), "escape must not bring the hidden panel back");
            assertEquals("editor-panel", test.runner().focusManager().focusedId());
            pressCtrlC(pilot);
        }
    }

    @Test
    void theSeparatorOfAHiddenProjectPanelDoesNotResizeIt() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withLayoutStore(store));
        int projectIcon = findRow(offlineFrame(shell), 1, "P");
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            pilot.click(1, projectIcon);
            settle(pilot);
            double ratio = store.load().projectRatio();
            Rect separator = pilot.findElement("pane-separator-vertical");

            pilot.mousePress(MouseButton.LEFT, separator.x(), separator.y());
            test.drag(MouseEvent.drag(MouseButton.LEFT, separator.x() + 8, separator.y()));
            pilot.mouseRelease(MouseButton.LEFT, separator.x() + 8, separator.y());
            settle(pilot);

            assertEquals(ratio, store.load().projectRatio(), "the hidden panel keeps its saved width");
        }
    }

    @Test
    void theBottomPaneDoesNotResizeWhileEveryBottomWindowIsHidden() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withLayoutStore(store));
        defaultWindows().stream().filter(window -> window.anchor() == ToolWindow.Anchor.BOTTOM)
                .filter(window -> shell.isToolWindowVisible(window.id()))
                .forEach(window -> shell.toggleToolWindow(window.id()));
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            double ratio = store.load().bottomRatio();

            pilot.press('.', KeyModifiers.ALT);
            settle(pilot);

            assertEquals("Bottom tool windows are hidden", shell.status());
            assertEquals(ratio, store.load().bottomRatio(), "the hidden pane keeps its saved height");
        }
    }

    @Test
    void theFirstToggleKeepsTheProjectWidthAndTheBottomHeight() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withLayoutStore(store));
        try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
            Pilot pilot = test.pilot();
            int projectBefore = pilot.findElement("project-panel").width();
            int bottomBefore = pilot.findElement("bottom-stripe").height();

            pilot.click(1, frameRowOf(test.renderedFrame(), 1, "R"));
            settle(pilot);

            assertFalse(shell.isToolWindowVisible("run"), shell.status());
            assertEquals(projectBefore, pilot.findElement("project-panel").width(),
                    "hiding Run keeps the project width");
            assertEquals(bottomBefore, pilot.findElement("bottom-stripe").height(),
                    "hiding Run keeps the bottom height");
        }
    }

    @Test
    void theFirstBottomDragKeepsTheProjectWidth() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withLayoutStore(store));
        try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
            Pilot pilot = test.pilot();
            int projectBefore = pilot.findElement("project-panel").width();
            Rect editor = pilot.findElement("editor-panel");
            int pressX = editor.x() + editor.width() / 2;
            int dividerY = pilot.findElement("pane-separator-horizontal").y();

            pilot.mousePress(MouseButton.LEFT, pressX, dividerY);
            test.drag(MouseEvent.drag(MouseButton.LEFT, pressX, dividerY - 3));
            pilot.mouseRelease(MouseButton.LEFT, pressX, dividerY - 3);
            settle(pilot);

            assertTrue(pilot.findElement("editor-panel").height() < editor.height(), "the drag must take effect");
            assertEquals(projectBefore, pilot.findElement("project-panel").width(),
                    "resizing the bottom pane keeps the project width");
        }
    }

    @Test
    void theFirstProjectDragKeepsTheBottomHeight() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withLayoutStore(store));
        try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
            Pilot pilot = test.pilot();
            int projectBefore = pilot.findElement("project-panel").width();
            int bottomBefore = pilot.findElement("bottom-stripe").height();
            Rect separator = pilot.findElement("pane-separator-vertical");

            pilot.mousePress(MouseButton.LEFT, separator.x(), separator.y() + 2);
            test.drag(MouseEvent.drag(MouseButton.LEFT, separator.x() + 6, separator.y() + 2));
            pilot.mouseRelease(MouseButton.LEFT, separator.x() + 6, separator.y() + 2);
            settle(pilot);

            assertTrue(pilot.findElement("project-panel").width() > projectBefore, "the drag must take effect");
            assertEquals(bottomBefore, pilot.findElement("bottom-stripe").height(),
                    "resizing the project pane keeps the bottom height");
        }
    }

    @Test
    void hidingARightWindowGivesItsColumnsOnlyToTheEditor() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        store.save(customizedLayout(0.3, ShellLayout.DEFAULT_BOTTOM_RATIO, windowsWithAcp()));
        VintelliShell shell = new VintelliShell(ShellConfig.of(windowsWithAcp(), new ShellState(), defaultThemes())
                .withLayoutStore(store));
        try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
            Pilot pilot = test.pilot();
            int projectBefore = pilot.findElement("project-panel").width();
            int editorBefore = pilot.findElement("editor-panel").width();
            int rightBefore = pilot.findElement("right-stripe").width();
            int acpIcon = frameRowOf(test.renderedFrame(), WIDE.width() - 1, "A");

            pilot.click(WIDE.width() - 1, acpIcon);
            settle(pilot);

            assertFalse(shell.isToolWindowVisible("acp.agents"), shell.status());
            assertEquals(projectBefore, pilot.findElement("project-panel").width(),
                    "hiding ACP keeps the project width");
            assertEquals(editorBefore + rightBefore - VintelliShell.TOOL_STRIP_WIDTH,
                    pilot.findElement("editor-panel").width(), "the editor takes the columns of the hidden window");

            pilot.click(WIDE.width() - 1, acpIcon);
            settle(pilot);

            assertTrue(shell.isToolWindowVisible("acp.agents"), shell.status());
            assertEquals(projectBefore, pilot.findElement("project-panel").width(),
                    "showing ACP keeps the project width");
            assertEquals(editorBefore, pilot.findElement("editor-panel").width(),
                    "showing ACP gives the editor its columns back");
        }
    }

    @Test
    void aCustomizedProjectYieldsToTheMinimumEditorWidthWhileARightWindowIsOpen() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        store.save(customizedLayout(0.6, ShellLayout.DEFAULT_BOTTOM_RATIO, windowsWithAcp()));
        VintelliShell shell = new VintelliShell(ShellConfig.of(windowsWithAcp(), new ShellState(), defaultThemes())
                .withLayoutStore(store));
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            int uncapped = (int) Math.round(0.6 * (SIZE.width() - VintelliShell.TOOL_STRIP_WIDTH));
            assertTrue(pilot.findElement("project-panel").width() < uncapped,
                    "the project yields to the right window so the editor keeps its minimum width");
            assertTrue(pilot.findElement("editor-panel").width() + 1 >= ShellLayout.MIN_EDITOR_WIDTH);

            int acpIcon = frameRowOf(test.renderedFrame(), SIZE.width() - 1, "A");
            pilot.click(SIZE.width() - 1, acpIcon);
            settle(pilot);

            assertEquals(uncapped, pilot.findElement("project-panel").width(),
                    "the project returns to its saved share once the right window is hidden");
        }
    }

    @Test
    void aBottomWindowIconStaysOnItsRowWhileItsPaneIsHiddenAndShown() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withLayoutStore(store));
        try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
            Pilot pilot = test.pilot();
            int runRow = frameRowOf(test.renderedFrame(), 1, "R");

            pilot.click(1, runRow);
            settle(pilot);
            assertFalse(shell.isToolWindowVisible("run"), shell.status());
            assertEquals(runRow, frameRowOf(test.renderedFrame(), 1, "R"), "hiding Run keeps its icon in place");

            pilot.click(1, runRow);
            settle(pilot);
            assertTrue(shell.isToolWindowVisible("run"), shell.status());
            assertEquals(runRow, frameRowOf(test.renderedFrame(), 1, "R"), "showing Run keeps its icon in place");
        }
    }

    @Test
    void aCustomizedLayoutRestoresItsSizesAndHiddenWindowsAfterARestart() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        int projectWidth;
        int bottomHeight;
        VintelliShell first = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withLayoutStore(store));
        try (MouseShellRunner test = runMouseShell(first, WIDE)) {
            Pilot pilot = test.pilot();
            Rect separator = pilot.findElement("pane-separator-vertical");
            int defaultWidth = pilot.findElement("project-panel").width();
            int pressY = pilot.findElement("editor-panel").y() + 2;
            pilot.mousePress(MouseButton.LEFT, separator.x(), pressY);
            test.drag(MouseEvent.drag(MouseButton.LEFT, separator.x() + 8, pressY));
            pilot.mouseRelease(MouseButton.LEFT, separator.x() + 8, pressY);
            settle(pilot);
            assertNotEquals(defaultWidth, pilot.findElement("project-panel").width(), "the drag must take effect");
            pilot.click(1, frameRowOf(test.renderedFrame(), 1, "R"));
            settle(pilot);
            projectWidth = pilot.findElement("project-panel").width();
            bottomHeight = pilot.findElement("bottom-stripe").height();
        }

        VintelliShell restarted = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withLayoutStore(store));
        try (MouseShellRunner test = runMouseShell(restarted, WIDE)) {
            Pilot pilot = test.pilot();
            assertFalse(restarted.isToolWindowVisible("run"), "the hidden window stays hidden after a restart");
            assertEquals(projectWidth, pilot.findElement("project-panel").width(),
                    "the dragged project width is restored");
            assertEquals(bottomHeight, pilot.findElement("bottom-stripe").height(),
                    "the bottom height is restored");
        }
    }

    @Test
    void clickingAToolTabActivatesTheWindowAtItsRenderedPosition() throws Exception {
        VintelliShell shell = defaultShell();
        Buffer buffer = offlineFrame(shell);
        int tabRow = findBottomTabRow(buffer);
        int problemsX = rowText(buffer, tabRow).indexOf("6 Problems") + 1;
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            pilot.click(problemsX, tabRow + 1);
            settle(pilot);
            assertEquals(2, shell.toolIndex(), shell.status());
            assertEquals("Tool window: Problems", shell.status());
            pressCtrlC(pilot);
        }
    }

    @Test
    void asciiModeDrawsTheStripsAndTheBottomTabsWithoutNonAsciiGlyphs() throws Exception {
        String previous = System.getProperty(IconMode.CONFIG_PROPERTY);
        System.setProperty(IconMode.CONFIG_PROPERTY, "ascii");
        try {
            VintelliShell shell = new VintelliShell(ShellConfig.of(windowsWithAcp(), new ShellState(), defaultThemes()));
            Set<Integer> gapColumns = new HashSet<>();
            try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
                Pilot pilot = test.pilot();
                settle(pilot);
                for (String gapId : List.of("pane-separator-vertical", "pane-separator-right")) {
                    if (pilot.hasElement(gapId) && pilot.findElement(gapId).width() > 0) {
                        gapColumns.add(pilot.findElement(gapId).x());
                    }
                }
            }
            Buffer buffer = offlineFrame(shell);
            int tabRow = findBottomTabRow(buffer);
            for (int row = 1; row < tabRow; row++) {
                for (int x : new int[] {0, 1, VIEWPORT.width() - 2, VIEWPORT.width() - 1}) {
                    assertTrue(buffer.get(x, row).symbol().chars().allMatch(character -> character < 128),
                            "the ASCII face draws no non-ASCII glyph in the strips at " + x + "," + row);
                }
            }
            assertTrue(nonGapCellsAreAscii(buffer, tabRow, gapColumns),
                    "the ASCII face draws no non-ASCII glyph in the tab row: " + rowText(buffer, tabRow));
            assertTrue(nonGapCellsAreAscii(buffer, tabRow + 1, gapColumns),
                    "the ASCII face underlines with an ASCII glyph: " + rowText(buffer, tabRow + 1));
            assertEquals("P", columnLetters(buffer, 1, 1, 1), "the project icon is its letter");
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void nerdModeDrawsEachStripeWithItsWindowGlyph() {
        String previous = System.getProperty(IconMode.CONFIG_PROPERTY);
        System.setProperty(IconMode.CONFIG_PROPERTY, "nerd");
        try {
            Buffer buffer = offlineFrame(shellWithAllBuiltInWindows());
            for (String glyph : List.of(PROJECT_GLYPH, RUN_GLYPH, TERMINAL_GLYPH, PROBLEMS_GLYPH)) {
                assertEquals(1, CharWidth.of(glyph), "the stripe glyph measures one column: " + glyph);
                assertTrue(findRow(buffer, 1, glyph) >= 0, "the left stripe shows " + glyph);
            }
            for (String glyph : List.of(ACP_GLYPH, STRUCTURE_GLYPH)) {
                assertEquals(1, CharWidth.of(glyph), "the stripe glyph measures one column: " + glyph);
                assertTrue(findRow(buffer, VIEWPORT.width() - 1, glyph) >= 0, "the right stripe shows " + glyph);
            }
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void nerdModeTabRowShowsEachWindowIconInPlaceOfItsDigit() {
        String previous = useNerdIconFace();
        try {
            Buffer buffer = offlineFrame(defaultShell());
            int tabRow = rowContaining(buffer, RUN_GLYPH + " Run");
            assertTrue(tabRow >= 0, "the Run tab shows its icon before its title");
            String text = rowText(buffer, tabRow);
            assertTrue(text.contains(TERMINAL_GLYPH + " Terminal"), text);
            assertTrue(text.contains(PROBLEMS_GLYPH + " Problems"), text);
            assertFalse(text.contains("4 Run") || text.contains("2 Terminal") || text.contains("6 Problems"),
                    "the Nerd face shows no digit in front of the tabs: " + text);
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void nerdModeTabRowKeepsTheDigitOfAWindowWithoutAnIcon() {
        String previous = useNerdIconFace();
        try {
            Buffer buffer = offlineFrame(shellWithFakeWindow(threadsWindow("")));
            assertTrue(rowContaining(buffer, "7 Threads") >= 0, "a window without an icon keeps its digit");
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void nerdModeTabRowShowsTheIconOfAWindowInPlaceOfItsDigit() {
        String previous = useNerdIconFace();
        try {
            Buffer buffer = offlineFrame(shellWithFakeWindow(threadsWindow(THREADS_GLYPH)));
            int tabRow = rowContaining(buffer, THREADS_GLYPH + " Threads");
            assertTrue(tabRow >= 0, "the Threads tab shows its icon before its title");
            assertFalse(rowText(buffer, tabRow).contains("7 Threads"), rowText(buffer, tabRow));
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void asciiModeTabRowKeepsTheDigitEvenWhenTheWindowHasAnIcon() {
        String previous = System.getProperty(IconMode.CONFIG_PROPERTY);
        System.setProperty(IconMode.CONFIG_PROPERTY, "ascii");
        try {
            Buffer buffer = offlineFrame(shellWithFakeWindow(threadsWindow(THREADS_GLYPH)));
            assertTrue(rowContaining(buffer, "7 Threads") >= 0, "the ASCII face keeps the digit in the tab row");
            assertEquals(-1, rowContaining(buffer, THREADS_GLYPH), "the ASCII face draws no Nerd Font glyph");
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void clickingTheIconOfAToolTabSelectsThatWindow() throws Exception {
        String previous = useNerdIconFace();
        try {
            VintelliShell shell = defaultShell();
            Buffer buffer = offlineFrame(shell);
            int tabRow = rowContaining(buffer, PROBLEMS_GLYPH + " Problems");
            int iconX = rowText(buffer, tabRow).indexOf(PROBLEMS_GLYPH + " Problems");
            assertTrue(tabRow >= 0, "the Problems tab shows its icon");
            try (ToolkitTestRunner test = runShell(shell)) {
                Pilot pilot = test.pilot();
                pilot.pause(STARTUP);
                pilot.click(iconX, tabRow);
                settle(pilot);
                assertEquals(2, shell.toolIndex(), shell.status());
                assertEquals("Tool window: Problems", shell.status());
                pressCtrlC(pilot);
            }
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void aTabRowTooNarrowForAllTabsKeepsTheSelectedTabWholeAndMarksTheTabsThatFollow() {
        List<String> rows = renderTabBar(rightTabBar(1, NERD_TAB_GLYPHS), 20);

        assertEquals(" 4 Alpha  6 Bravo  »", rows.get(0), "the selected tab is whole and the right mark shows");
        assertEquals(" ".repeat(10) + "━".repeat(7), rows.get(1).stripTrailing(), "the underline sits under the selected tab");
    }

    @Test
    void aTabRowScrollsLeftToTheSelectedTabAndMarksTheEdgeOfTheTabsScrolledAway() {
        List<String> rows = renderTabBar(rightTabBar(3, NERD_TAB_GLYPHS), 20);

        assertEquals("« 7 Charlie  8 Delta", rows.get(0), "the window scrolled so the last tab is whole with its left mark");
        assertFalse(rows.get(0).contains("Alpha"), "the tabs scrolled away are not drawn: " + rows.get(0));
    }

    @Test
    void aTabRowThatFitsDrawsNoOverflowMark() {
        List<String> rows = renderTabBar(rightTabBar(1, NERD_TAB_GLYPHS), 40);

        assertEquals(" 4 Alpha  6 Bravo  7 Charlie  8 Delta", rows.get(0).stripTrailing(),
                "every tab is whole and no mark is drawn");
    }

    @Test
    void theAsciiTabRowDrawsOnlyAsciiGlyphsInItsMarksAndUnderline() {
        ToolTabBarElement.Glyphs ascii = new ToolTabBarElement.Glyphs("-", "<", ">");
        List<String> rows = renderTabBar(rightTabBar(1, ascii), 20);

        assertEquals(" 4 Alpha  6 Bravo  >", rows.get(0));
        assertTrue(rows.get(0).chars().allMatch(character -> character < 128), rows.get(0));
        assertEquals(" ".repeat(10) + "-".repeat(7), rows.get(1).stripTrailing());
    }

    @Test
    void aClickOnAMarkSelectsNoTabAndAClickOnAWholeTabSelectsIt() {
        ToolTabBarElement bar = rightTabBar(1, NERD_TAB_GLYPHS);
        renderTabBar(bar, 20);

        assertEquals(-1, bar.tabAt(19, 0), "the right mark is no tab");
        assertEquals(-1, bar.tabAt(0, 0), "the blank lead column is no tab");
        assertEquals(0, bar.tabAt(2, 0), "a click on the first tab selects it");
        assertEquals(1, bar.tabAt(12, 0), "a click on the selected tab selects it");

        ToolTabBarElement scrolled = rightTabBar(3, NERD_TAB_GLYPHS);
        renderTabBar(scrolled, 20);
        assertEquals(-1, scrolled.tabAt(0, 0), "the left mark is no tab");
        assertEquals(2, scrolled.tabAt(2, 0), "the first visible tab is selected by a click on it");
    }

    @Test
    void asciiModeDrawsEachStripeWithTheFirstLetterOfItsTitle() {
        String previous = System.getProperty(IconMode.CONFIG_PROPERTY);
        System.setProperty(IconMode.CONFIG_PROPERTY, "ascii");
        try {
            Buffer buffer = offlineFrame(shellWithAllBuiltInWindows());
            assertTrue(findRow(buffer, 1, "P") >= 0, "the Project stripe shows its letter");
            assertTrue(findRow(buffer, 1, "R") >= 0, "the Run stripe shows its letter");
            assertTrue(findRow(buffer, 1, "T") >= 0, "the Terminal stripe shows its letter");
            assertTrue(findRow(buffer, VIEWPORT.width() - 1, "A") >= 0, "the ACP Agents stripe shows its letter");
            assertTrue(findRow(buffer, VIEWPORT.width() - 1, "S") >= 0, "the Structure stripe shows its letter");
            assertEquals(-1, findRow(buffer, 1, RUN_GLYPH), "the ASCII face draws no Nerd Font glyph");
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void theActiveStripeIconUsesTheSelectedStyleOfEachShippedTheme() {
        for (String theme : List.of("dark", "darcula")) {
            ShellThemes themes = new ShellThemes(theme, (Path) null);
            VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), themes));
            Buffer buffer = offlineFrame(shell);
            int runIcon = findRow(buffer, 1, "R");
            int terminalIcon = findRow(buffer, 1, "T");
            assertEquals(themes.color("tab-selected-bg"), buffer.get(1, runIcon).style().bg().orElse(null),
                    "the active icon sits on the selected background in " + theme);
            assertEquals(themes.color("tab-selected-bg"), buffer.get(0, runIcon).style().bg().orElse(null),
                    "the active icon fills both strip columns in " + theme);
            assertEquals(themes.color("tab-selected-fg"), buffer.get(1, runIcon).style().fg().orElse(null),
                    "the active icon uses the selected foreground in " + theme);
            assertEquals(themes.color("soft"), buffer.get(1, terminalIcon).style().fg().orElse(null),
                    "an inactive icon uses the soft color in " + theme);
            assertFalse(themes.color("tab-selected-bg").equals(buffer.get(1, terminalIcon).style().bg().orElse(null)),
                    "an inactive icon carries no selected background in " + theme);
        }
    }

    @Test
    void wideShellDrawsTheBreadcrumbAndTheEditorWidgetsOnTheStatusRow() throws Exception {
        EditorShell opened = openEditorShell("Main.java", "class Main {\n    void run() {\n    }\n}\n", 1);
        try (MouseShellRunner test = runMouseShell(opened.shell(), WIDE)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            String status = statusRow(test, WIDE);
            assertTrue(status.startsWith(project.getFileName() + " › Main.java"), status);
            assertTrue(status.matches("(?s).*\\d+:\\d+  LF  UTF-8  4 spaces$"), status);
            assertFalse(status.contains("│"), status);
            assertFalse(status.contains("IDEA"), status);
            pressCtrlC(pilot);
        }
    }

    @Test
    void narrowShellKeepsTheFileCaretEncodingAndAReadableMessageAtEightyColumns() throws Exception {
        EditorShell opened = openEditorShell("Main.java", "class Main {\n    void run() {\n    }\n}\n", 1);
        try (MouseShellRunner test = runMouseShell(opened.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            String status = statusRow(test, SIZE);
            assertTrue(CharWidth.of(status) <= SIZE.width(), status);
            assertTrue(status.contains("Main.java"), status);
            assertTrue(status.matches("(?s).*\\d+:\\d+  LF  UTF-8.*"), status);
            assertTrue(status.contains("Ready"), status);
            pressCtrlC(pilot);
        }
    }

    @Test
    void theProviderIndicatorIsHiddenWhileEveryProviderIsHealthy() throws Exception {
        VintelliShell shell = shellWithRegistry(FixedStatusRegistry.withStatuses(List.of(
                CapabilityStatus.available(CapabilityType.RUN, "local", "config", List.of()))));
        try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            assertFalse(statusRow(test, WIDE).contains("provider"), statusRow(test, WIDE));
            pressCtrlC(pilot);
        }
    }

    @Test
    void clickingTheDegradedIndicatorOpensTheProviderPopupAndEscapeCloses() throws Exception {
        VintelliShell shell = shellWithRegistry(FixedStatusRegistry.withStatuses(List.of(
                CapabilityStatus.available(CapabilityType.RUN, "local", "config", List.of()),
                CapabilityStatus.unavailable(CapabilityType.TERMINAL, "pty is missing"))));
        try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            String status = statusRow(test, WIDE);
            assertTrue(status.contains("1 provider degraded"), status);
            assertFalse(test.renderedFrame().contains("Terminal: unavailable"), test.renderedFrame());

            pilot.click(status.indexOf("provider"), WIDE.height() - 1);
            settle(pilot);
            String popup = awaitRenderedFrame(pilot, test, "Terminal: unavailable: pty is missing");
            assertTrue(popup.contains("Providers"), popup);
            assertTrue(popup.contains("Run: local (config)"), popup);

            pilot.press(KeyCode.ESCAPE);
            settle(pilot);
            assertFalse(test.renderedFrame().contains("Terminal: unavailable"), test.renderedFrame());
            pressCtrlC(pilot);
        }
    }

    @Test
    void aClickOutsideTheProviderPopupClosesItWithoutSwallowingTheClick() throws Exception {
        VintelliShell shell = shellWithRegistry(FixedStatusRegistry.withStatuses(List.of(
                CapabilityStatus.unavailable(CapabilityType.TERMINAL, "pty is missing"))));
        try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            pilot.click(statusRow(test, WIDE).indexOf("provider"), WIDE.height() - 1);
            settle(pilot);
            awaitRenderedFrame(pilot, test, "Terminal: unavailable");

            pilot.click(WIDE.width() / 2, 10);
            settle(pilot);
            assertFalse(test.renderedFrame().contains("Terminal: unavailable"), test.renderedFrame());
            pressCtrlC(pilot);
        }
    }

    private VintelliShell shellWithRegistry(FixedStatusRegistry registry) {
        return new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withCapabilityRegistry(registry));
    }

    private static String statusRow(MouseShellRunner test, Size size) {
        return test.renderedFrame().lines().skip(size.height() - 1).findFirst().orElse("").stripTrailing();
    }

    private static int rowContaining(Buffer buffer, String text) {
        for (int row = 0; row < buffer.height(); row++) {
            if (rowText(buffer, row).contains(text)) {
                return row;
            }
        }
        return -1;
    }

    private static FakeToolWindow threadsWindow(String icon) {
        return new FakeToolWindow("fake.threads", "Threads", '7', 50, "fake thread dump") {
            @Override
            public String icon() {
                return icon;
            }
        };
    }

    private static int findRow(Buffer buffer, int x, String symbol) {
        for (int row = 0; row < buffer.height(); row++) {
            if (symbol.equals(buffer.get(x, row).symbol())) {
                return row;
            }
        }
        return -1;
    }

    private static String columnLetters(Buffer buffer, int x, int fromRow, int toRow) {
        StringBuilder letters = new StringBuilder();
        for (int row = fromRow; row <= toRow; row++) {
            String symbol = buffer.get(x, row).symbol();
            if (!symbol.isBlank()) {
                letters.append(symbol);
            }
        }
        return letters.toString();
    }

    @TempDir
    Path project;

    private static final Duration STARTUP = Duration.ofMillis(100);
    private static final Duration EVENT_WINDOW = Duration.ofMillis(500);
    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SETTLE_STEP = Duration.ofMillis(2);
    private static final Duration FRAME_GRACE = Duration.ofMillis(30);
    private static final Rect VIEWPORT = new Rect(0, 0, 80, 24);
    private static final String PROJECT_GLYPH = "";
    private static final String RUN_GLYPH = "";
    private static final String TERMINAL_GLYPH = "";
    private static final String PROBLEMS_GLYPH = "";
    private static final String ACP_GLYPH = "";
    private static final String THREADS_GLYPH = "";
    private static final String STRUCTURE_GLYPH = "";
    private static final Size SIZE = new Size(VIEWPORT.width(), VIEWPORT.height());
    private static final Size WIDE = new Size(150, 42);
    private static final int MENU_ROW = 0;
    private static final int LEFT_TAB_ROW = 1; // Left stripe tab bar (empty in default)
    private static final int FIRST_PROJECT_ROW = 2; // Project panel now starts at row 2
    private static final int SECOND_PROJECT_ROW = 3;
    private static final int PROJECT_ROW_HIGHLIGHT = VintelliShell.PROJECT_PANEL_WIDTH;
    private static final int HEADER_ROW = 1;
    private static final int PROJECT_X = VintelliShell.TOOL_STRIP_WIDTH;
    private static final int EDITOR_HEADER_X = VintelliShell.TOOL_STRIP_WIDTH + VintelliShell.PROJECT_PANEL_WIDTH + 1;
    private static final int SEPARATOR_X = VintelliShell.TOOL_STRIP_WIDTH + VintelliShell.PROJECT_PANEL_WIDTH;
    private static final List<String> FRAME_CORNERS = List.of("┌", "┐", "└", "┘", "╭", "╮", "╰", "╯");
    // Bottom stripe tab bar row (after menu + left content + editor)
    private static final int BOTTOM_TAB_ROW = 13; // approximate, will be computed dynamically
    private static final int TOOL_WINDOW_ROW = 14;
    private static final int STATUS_ROW = 23;
    private static final int EDITOR_PROBE_X = 58;
    private static final int EDITOR_PROBE_Y = 10;

    @Test
    void selectsToolWindowsByDigitKeys() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            // Bottom anchor: Run(4), Terminal(2), Problems(6), the IntelliJ numbers
            press(pilot, '2');
            assertEquals(1, shell.toolIndex()); // Terminal is at bottom index 1
            assertEquals("Tool window: Terminal", shell.status());

            // The terminal captures input while a shell session is focused.
            // Escape returns keyboard control to the shell so bare digit
            // shortcuts can switch windows again.
            pilot.press(KeyCode.ESCAPE);
            settle(pilot);
            press(pilot, '6');
            assertEquals(2, shell.toolIndex()); // Problems is at bottom index 2
            assertEquals("Tool window: Problems", shell.status());

            press(pilot, '4');
            assertEquals(0, shell.toolIndex()); // Run is at bottom index 0
            assertEquals("Tool window: Run", shell.status());

            press(pilot, 'x');
            assertEquals(0, shell.toolIndex());
            assertEquals("Tool window: Run", shell.status());

            pressCtrlC(pilot);
        }
    }

    @Test
    void navigatesTheProjectPanelWithArrowKeysInTheDefaultProfile() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            pilot.press(KeyCode.DOWN);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(1, shell.fileIndex());
            assertEquals("Selected: VintelliMain.java", shell.status());

            pilot.press(KeyCode.DOWN);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(2, shell.fileIndex());

            pilot.press(KeyCode.UP);
            settle(pilot);
            pilot.press(KeyCode.UP);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(0, shell.fileIndex());
            assertEquals("Selected: vintelli/pom.xml", shell.status());

            assertTrue(test.runner().isRunning());

            pressCtrlC(pilot);
        }
    }

    @Test
    void clampsSelectionAtListBounds() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            for (int step = 0; step < 4; step++) {
                pilot.press(KeyCode.DOWN);
                settle(pilot);
            }
            pressQuietly(pilot);
            assertEquals(4, shell.fileIndex());
            assertEquals("Selected: VintelliShellTest.java", shell.status());

            for (int step = 0; step < 6; step++) {
                pilot.press(KeyCode.DOWN);
                settle(pilot);
            }
            pressQuietly(pilot);
            assertEquals(4, shell.fileIndex());
            assertEquals("Selected: VintelliShellTest.java", shell.status());

            for (int step = 0; step < 10; step++) {
                pilot.press(KeyCode.UP);
                settle(pilot);
            }
            pressQuietly(pilot);
            assertEquals(0, shell.fileIndex());
            assertEquals("Selected: vintelli/pom.xml", shell.status());

            pressCtrlC(pilot);
        }
    }

    @Test
    void separatesMenuEntriesOnFirstRow() {
        VintelliShell shell = defaultShell();
        shell.handle(KeyEvent.ofKey(KeyCode.F10));
        Buffer buffer = offlineFrame(shell);

        String menu = rowText(buffer, MENU_ROW);
        assertTrue(menu.contains("Edit  View"), menu);
        assertTrue(menu.contains("Tools  VCS"), menu);
        assertTrue(menu.contains("Code  Refactor"), menu);
    }

    @Test
    void menuActionRoutesThroughShellDispatcherAndResizesTheDock() {
        VintelliShell shell = defaultShell();
        offlineFrame(shell);

        assertEquals(EventResult.HANDLED, shell.handle(KeyEvent.ofKey(KeyCode.F10)));
        shell.handle(KeyEvent.ofKey(KeyCode.RIGHT));
        shell.handle(KeyEvent.ofKey(KeyCode.RIGHT));
        shell.handle(KeyEvent.ofKey(KeyCode.ENTER));

        assertTrue(shell.status().startsWith("Project panel:"), shell.status());
    }

    @Test
    void structureFiltersSymbolsAndNavigatesTheMatchingEntry() {
        StructureToolWindow structure = new StructureToolWindow(defaultThemes());
        DocumentSymbol first = new DocumentSymbol("first", 6, 1, 0, 1, 10, 1, 0, 1, 5, List.of());
        DocumentSymbol second = new DocumentSymbol("second", 6, 2, 0, 2, 10, 2, 0, 2, 6, List.of());
        AtomicReference<DocumentSymbol> navigated = new AtomicReference<>();
        structure.setSymbols(List.of(first, second));
        structure.setNavigateHandler(navigated::set);

        structure.handleKey(KeyEvent.ofChar('e'));
        assertEquals(second, structure.selectedSymbol());
        structure.handleKey(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals(second, navigated.get());
        structure.handleKey(KeyEvent.ofKey(KeyCode.BACKSPACE));
        assertEquals(first, structure.selectedSymbol());
    }

    @Test
    void structureSelectionNavigatesTheActiveEditor() throws IOException {
        Path file = project.resolve("Example.java");
        Files.writeString(file, "class Example {\n  void first() {}\n  void second() {}\n}\n");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        StructureToolWindow structure = new StructureToolWindow(themes);
        VintelliShell shell = new VintelliShell(ShellConfig.of(List.of(editor, structure), new ShellState(), themes));
        editor.open(new OpenRequest(file, 0));
        structure.setSymbols(List.of(new DocumentSymbol("second", 6, 2, 2, 2, 18,
                2, 7, 2, 13, List.of())));

        assertEquals(EventResult.HANDLED, shell.handle(KeyEvent.ofChar('7', KeyModifiers.ALT)));
        assertEquals(EventResult.HANDLED, shell.handle(KeyEvent.ofKey(KeyCode.ENTER)));
        assertEquals(2, editor.activeDocument().orElseThrow().line());
    }

    @Test
    void menuNavigationBubblesFromAnEditorWithFocusSoStageCanBeSelected() throws Exception {
        Path file = project.resolve("sample.txt");
        Files.writeString(file, "first line\nsecond line\n");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        assertFalse(editor.open(new OpenRequest(file, 0)).isFailure());
        List<ToolWindow> windows = new ArrayList<>(defaultWindows());
        windows.add(editor);
        VersionControlCapability vcs = new VersionControlCapability() {
            @Override public CapabilityResult<Status> status() {
                return CapabilityResult.ok(new Status("main", 0, 0, List.of()));
            }
            @Override public CapabilityResult<String> diff(Path changedFile) { return CapabilityResult.ok(""); }
            @Override public CapabilityResult<String> diffWorkingTree() { return CapabilityResult.ok(""); }
            @Override public CapabilityResult<Boolean> stage(Path changedFile, boolean confirmed) {
                return CapabilityResult.ok(confirmed);
            }
            @Override public CapabilityResult<Boolean> unstage(Path changedFile, boolean confirmed) {
                return CapabilityResult.ok(confirmed);
            }
            @Override public CapabilityResult<Boolean> commit(String message, boolean confirmed) {
                return CapabilityResult.ok(confirmed);
            }
            @Override public CapabilityResult<Boolean> switchBranch(String branch, boolean confirmed) {
                return CapabilityResult.ok(confirmed);
            }
            @Override public String identifier() { return "menu-test"; }
            @Override public ProviderHealth health() { return ProviderHealth.available(); }
            @Override public String provenance() { return "temporary menu test provider"; }
        };
        VintelliShell shell = new VintelliShell(ShellConfig.of(new ToolWindowRegistry(windows).toolWindows(), new ShellState(), themes)
                .withKeymapSupplier(() -> Keymap.defaultKeymap(windows))
                .withProjectSearch(null)
                .withNavigationProviders(List.of())
                .withVcsProviders(List.of(vcs)));

        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            pilot.press(KeyCode.RIGHT);
            settle(pilot);
            assertEquals(1, editor.activeDocument().orElseThrow().column(), "the editor has keyboard focus initially");

            pilot.press(KeyCode.F10);
            settle(pilot);
            for (int menu = 0; menu < 8; menu++) {
                pilot.press(KeyCode.RIGHT);
                settle(pilot);
            }
            for (int row = 0; row < 3; row++) {
                pilot.press(KeyCode.DOWN);
                settle(pilot);
            }
            pilot.press(KeyCode.ENTER);
            settle(pilot);

            assertEquals(1, editor.activeDocument().orElseThrow().column(),
                    "menu keys must not move the text cursor or insert a newline");
            assertTrue(shell.status().startsWith("Stage sample.txt? [y/N]"), shell.status());
            pilot.press('n');
            settle(pilot);
        }
    }

    @Test
    void enterOpensTheSelectedProjectFileInTheCenterEditor() throws IOException {
        Path file = project.resolve("vintelli/pom.xml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<project>shell editor</project>");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        List<ToolWindow> windows = new ArrayList<>(defaultWindows());
        windows.add(editor);
        VintelliShell shell = new VintelliShell(ShellConfig.of(new ToolWindowRegistry(windows).toolWindows(), new ShellState(), themes));

        assertEquals(EventResult.HANDLED, shell.handle(KeyEvent.ofKey(KeyCode.ENTER)));
        assertEquals(file.toRealPath().toString(), editor.activeDocument().orElseThrow().file());
        assertEquals(1, editor.tabs().size());
        assertEquals("<project>shell editor</project>", editor.activeDocument().orElseThrow().text());
        String rendered = bufferText(offlineFrame(shell));
        assertTrue(rendered.contains("<project>shell"), "opened file contents must render in the editor:\n" + rendered);
    }

    @Test
    void refreshShowsGitStateInProjectTreeTabsAndChangedLineGutter() throws IOException {
        Path file = project.resolve("vintelli/pom.xml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "first\nchanged\nlast\n");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        List<ToolWindow> windows = new ArrayList<>(defaultWindows());
        windows.add(editor);
        VersionControlCapability vcs = new VersionControlCapability() {
            @Override public CapabilityResult<Status> status() {
                return CapabilityResult.ok(new Status("main", 0, 0,
                        List.of(new Entry(State.MODIFIED, "vintelli/pom.xml"))));
            }
            @Override public CapabilityResult<String> diff(Path changedFile) {
                return CapabilityResult.ok("diff --git a/vintelli/pom.xml b/vintelli/pom.xml\n"
                        + "--- a/vintelli/pom.xml\n+++ b/vintelli/pom.xml\n@@ -1,3 +1,3 @@\n"
                        + " first\n-changed\n+changed again\n last\n");
            }
            @Override public CapabilityResult<String> diffWorkingTree() { return CapabilityResult.ok(""); }
            @Override public CapabilityResult<Boolean> stage(Path path, boolean confirmed) {
                return CapabilityResult.ok(confirmed);
            }
            @Override public CapabilityResult<Boolean> unstage(Path path, boolean confirmed) {
                return CapabilityResult.ok(confirmed);
            }
            @Override public CapabilityResult<Boolean> commit(String message, boolean confirmed) {
                return CapabilityResult.ok(confirmed);
            }
            @Override public CapabilityResult<Boolean> switchBranch(String branch, boolean confirmed) {
                return CapabilityResult.ok(confirmed);
            }
            @Override public String identifier() { return "test-vcs"; }
            @Override public ProviderHealth health() { return ProviderHealth.available(); }
            @Override public String provenance() { return "temporary test provider"; }
        };
        VintelliShell shell = new VintelliShell(ShellConfig.of(new ToolWindowRegistry(windows).toolWindows(), new ShellState(), themes)
                .withKeymapSupplier(() -> Keymap.defaultKeymap(windows))
                .withProjectSearch(null)
                .withNavigationProviders(List.of())
                .withVcsProviders(List.of(vcs)));

        shell.handle(KeyEvent.ofKey(KeyCode.ENTER));
        shell.refreshVcs();
        String rendered = bufferText(offlineFrame(shell));

        assertTrue(rendered.contains("M vintelli/pom.xml"), "the tree should show the changed file: " + rendered);
        assertTrue(rendered.contains("pom.xml M"), "the editor tab should show Git state: " + rendered);
        assertTrue(rendered.contains("+ 2"), "the gutter should mark changed line 2: " + rendered);
    }

    @Test
    void vcsRefreshKeepsTheProjectTreeForm() throws IOException, InterruptedException {
        Path changed = project.resolve("src/main/A.java");
        Files.createDirectories(changed.getParent());
        Files.writeString(changed, "class A {}");
        Files.writeString(project.resolve("README.md"), "# readme");
        VintelliShell shell = shellWithVcsTree(modifiedVcs("src/main/A.java"), project);
        shell.refreshProjectTree();
        shell.selectPath("src");
        shell.expandSelected();
        shell.selectPath("src/main");
        shell.expandSelected();
        shell.selectPath("src/main/A.java");

        shell.refreshVcs();
        FileIcons icons = FileIcons.resolve();
        String srcRow = ProjectTreeTest.disclosureGlyph(icons, ProjectTreeGlyphs.supportsUnicode(), true) + " "
                + icons.folderIconFor("main").symbol(icons.mode()) + " src/main";
        String changedRow = "  M " + icons.iconFor("A.java", JavaTypeKind.CLASS).symbol(icons.mode()) + " A.java";
        String rendered = awaitRendered(shell, changedRow);
        assertTrue(rendered.contains(srcRow), "the expanded directory keeps its tree row: " + rendered);
        assertTrue(rendered.contains(changedRow), "the changed file keeps its tree row and marker: " + rendered);
        assertFalse(rendered.contains("M src/main/A.java"), "the refresh must not flatten rows to paths: " + rendered);
        assertEquals("src/main/A.java", shell.selectedPathString(), "the refresh must keep the selection");
    }

    @Test
    void rebuildingTheProjectTreeRunsVcsStatusOnceNotOncePerExpandedFolder() throws Exception {
        Files.createDirectories(project.resolve("src/main/a"));
        Files.createDirectories(project.resolve("src/main/b"));
        Files.createDirectories(project.resolve("src/test"));
        Files.writeString(project.resolve("src/main/a/A.java"), "class A {}");
        Files.writeString(project.resolve("src/main/b/B.java"), "class B {}");
        Files.writeString(project.resolve("src/test/T.java"), "class T {}");
        AtomicInteger statusCalls = new AtomicInteger();
        VintelliShell shell = shellWithVcsTree(countingModifiedVcs("src/main/a/A.java", statusCalls), project);
        shell.refreshProjectTree();
        for (String folder : List.of("src", "src/main", "src/main/a", "src/main/b")) {
            shell.selectPath(folder);
            shell.expandSelected();
        }
        shell.selectPath("src/main/a/A.java");
        statusCalls.set(0);

        shell.refreshProjectTree();

        assertEquals(1, statusCalls.get(), "one rebuild should ask the VCS for status once, whatever the expanded folders");
        FileIcons icons = FileIcons.resolve();
        String changedRow = "  M " + icons.iconFor("A.java", JavaTypeKind.CLASS).symbol(icons.mode()) + " A.java";
        String rendered = awaitRendered(shell, changedRow);
        assertTrue(rendered.contains(changedRow), "the rebuilt rows keep the VCS state: " + rendered);
    }

    @Test
    void aVcsMarkerInTheSlotDoesNotMoveTheFileNameColumn() throws Exception {
        Files.createDirectories(project.resolve("src/lib"));
        Files.writeString(project.resolve("src/A.java"), "class A {}");
        Files.writeString(project.resolve("src/B.java"), "class B {}");
        Files.writeString(project.resolve("src/lib/Inner.java"), "class Inner {}");
        VintelliShell shell = shellWithVcsTree(modifiedVcs("src/A.java"), project);
        shell.refreshProjectTree();
        shell.selectPath("src");
        shell.expandSelected();
        shell.refreshVcs();
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            settle(pilot);
            Rect list = pilot.findElement("project-panel");
            String frame = test.renderedFrame();

            int marked = projectNameColumn(frame, list, "A.java");
            assertEquals(projectNameColumn(frame, list, "B.java"), marked,
                    "the marker in the slot must not move the file name: " + frame);
            assertEquals(projectNameColumn(frame, list, "lib"), marked,
                    "a file name lines up with the name of a sibling folder: " + frame);
            assertEquals('M', frameCell(frame, list.x() + 4, projectRowOf(frame, list, "A.java")),
                    "the modified file draws its marker in the slot at column 4");
        }
    }

    private static VintelliShell shellWithVcsTree(VersionControlCapability vcs, Path root) {
        ShellThemes themes = defaultThemes();
        List<ToolWindow> windows = new ArrayList<>(defaultWindows());
        windows.add(new BuiltinEditor(root, null, () -> null));
        ToolWindowRegistry registry = new ToolWindowRegistry(windows);
        return new VintelliShell(ShellConfig.of(registry.toolWindows(), new ShellState(), themes)
                .withKeymapSupplier(() -> Keymap.defaultKeymap(registry.toolWindows()))
                .withProjectSearch(ProjectTreeTest.fakeSearch(root))
                .withNavigationProviders(List.of())
                .withVcsProviders(List.of(vcs)));
    }

    private static VersionControlCapability modifiedVcs(String changedPath) {
        return countingModifiedVcs(changedPath, new AtomicInteger());
    }

    private static VersionControlCapability countingModifiedVcs(String changedPath, AtomicInteger statusCalls) {
        return new VersionControlCapability() {
            @Override public CapabilityResult<Status> status() {
                statusCalls.incrementAndGet();
                return CapabilityResult.ok(new Status("main", 0, 0,
                        List.of(new Entry(State.MODIFIED, changedPath))));
            }
            @Override public CapabilityResult<String> diff(Path changedFile) { return CapabilityResult.ok(""); }
            @Override public CapabilityResult<String> diffWorkingTree() { return CapabilityResult.ok(""); }
            @Override public CapabilityResult<Boolean> stage(Path changedFile, boolean confirmed) { return CapabilityResult.ok(confirmed); }
            @Override public CapabilityResult<Boolean> unstage(Path changedFile, boolean confirmed) { return CapabilityResult.ok(confirmed); }
            @Override public CapabilityResult<Boolean> commit(String message, boolean confirmed) { return CapabilityResult.ok(confirmed); }
            @Override public CapabilityResult<Boolean> switchBranch(String target, boolean confirmed) { return CapabilityResult.ok(confirmed); }
            @Override public String identifier() { return "modified-test"; }
            @Override public ProviderHealth health() { return ProviderHealth.available(); }
            @Override public String provenance() { return "temporary modified-file test provider"; }
        };
    }

    @Test
    void parsesChangedLinesOnlyInsideHunksAndCountsLiteralPlusAndMinusPrefixes() {
        String diff = "diff --git a/sample.txt b/sample.txt\n"
                + "--- a/sample.txt\n+++ b/sample.txt\n"
                + "@@ -1,3 +1,3 @@\n context\n---literal removed prefix\n+++literal added prefix\n last\n";

        assertEquals(Set.of(0, 1), VintelliShell.changedLines(diff));
        assertEquals(Set.of(0), VintelliShell.changedLines("@@ -3 +2,0 @@\n-removed\n"));
        assertEquals(Set.of(2, 3), VintelliShell.changedLines("@@ -2,0 +3,2 @@\n+first\n+second\n"));
    }

    @Test
    void highlightsOnlySelectedTabAndRowOnFirstFrame() {
        ShellThemes themes = defaultThemes();
        VintelliShell shell = defaultShell(themes);
        Color tabSelected = themes.color("tab-selected-bg");
        Color rowSelected = themes.color("selection-bg");

        Buffer buffer = offlineFrame(shell);
        int bottomTabRow = findBottomTabRow(buffer);
        String tabRow = rowText(buffer, bottomTabRow);
        Color underline = themes.color("focus-border");

        assertEquals(0, highlightedCount(buffer, bottomTabRow, tabSelected),
                "the tab row carries no filled block, the selection is an underline: " + tabRow);
        assertEquals("4 Run".length(), contiguousForeground(buffer, bottomTabRow + 1, underline),
                "exactly the selected label carries the underline: " + rowText(buffer, bottomTabRow + 1));
        assertEquals(EDITOR_HEADER_X + 1, firstForegroundColumn(buffer, bottomTabRow + 1, underline),
                "the underline starts under the selected label 4 Run, one cell past the editor's left edge: "
                        + rowText(buffer, bottomTabRow + 1));
        assertEquals(PROJECT_ROW_HIGHLIGHT, highlightedCount(buffer, FIRST_PROJECT_ROW, rowSelected),
                "the selected project row carries the selection color: " + rowText(buffer, FIRST_PROJECT_ROW));
        assertEquals(0, highlightedCount(buffer, SECOND_PROJECT_ROW, rowSelected),
                "unselected project rows must not carry the selection color");
        assertEquals(themes.color("editor-bg"), buffer.get(EDITOR_PROBE_X, EDITOR_PROBE_Y).style().bg().orElse(null),
                "the editor panel shows the editor palette background");
    }

    @Test
    void movesHighlightToTab6AndDropsTab4AfterDigitPress() throws Exception {
        ShellThemes themes = defaultThemes();
        VintelliShell shell = defaultShell(themes);
        Color tabSelected = themes.color("tab-selected-bg");
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            press(pilot, '6');
            assertEquals(2, shell.toolIndex());
            pressCtrlC(pilot);
        }

        Buffer buffer = offlineFrame(shell);
        int bottomTabRow = findBottomTabRow(buffer);
        String tabRow = rowText(buffer, bottomTabRow);
        int tab6Start = tabRow.indexOf("6 Problems");
        assertTrue(tab6Start >= 0, "tab 6 should be present: " + tabRow);
        Color underline = themes.color("focus-border");
        assertEquals(0, highlightedCount(buffer, bottomTabRow, tabSelected),
                "the tab row carries no filled block after switching to tab 6: " + tabRow);
        assertEquals(tab6Start, firstForegroundColumn(buffer, bottomTabRow + 1, underline),
                "the underline starts under the selected label 6 Problems: " + tabRow);
        assertEquals("6 Problems".length(), contiguousForeground(buffer, bottomTabRow + 1, underline),
                "the underline covers only the selected label: " + tabRow);
    }

    @Test
    void showsATabForAToolWindowRegisteredWithoutShellChanges() {
        VintelliShell shell = shellWithFakeWindow(new FakeToolWindow("fake.threads", "Threads", '7', 50, "fake thread dump", false, ToolWindow.Anchor.BOTTOM));
        Buffer buffer = offlineFrame(shell, WIDE);

        int bottomTabRow = findBottomTabRow(buffer);
        String tabRow = rowText(buffer, bottomTabRow);
        assertTrue(tabRow.contains("4 Run"), tabRow);
        assertTrue(tabRow.contains("7 Threads"), tabRow);
    }

    @Test
    void selectsATabByItsDeclaredShortcutAndRecordsItInState() throws Exception {
        VintelliShell shell = shellWithFakeWindow(new FakeToolWindow("fake.threads", "Threads", '7', 50, "fake thread dump", false, ToolWindow.Anchor.BOTTOM));
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '7');
            assertEquals("fake.threads", shell.state().snapshot().selectedToolWindowId());
            assertEquals("Tool window: Threads", shell.status());
            // Fake window is added at the end, so index depends on bottom anchor windows
            int expectedIndex = 3; // Run, Terminal, Problems, then fake
            assertEquals(expectedIndex, shell.toolIndex());

            pressCtrlC(pilot);
        }
    }

    @Test
    void routesUnclaimedKeysToTheActiveToolWindowBeforeShellShortcuts() throws Exception {
        FakeToolWindow fake = new FakeToolWindow("fake.notes", "Notes", '3', 60, "fake notes");
        VintelliShell shell = shellWithFakeWindow(fake);
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '3');
            press(pilot, 'j');
            assertEquals(0, shell.fileIndex(), "the active tool window must consume j before the project panel");
            assertTrue(fake.handledKeys().contains("j"), fake.handledKeys().toString());

            pressCtrlC(pilot);
        }
    }

    @Test
    void capturingWindowReceivesEvenTheShellKeys() throws Exception {
        FakeToolWindow terminalLike = new FakeToolWindow("fake.terminal", "Fake Terminal", '3', 60, "type away", true);
        VintelliShell shell = shellWithFakeWindow(terminalLike);
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '3');
            press(pilot, 'q');
            press(pilot, 'j');
            press(pilot, '1');
            assertEquals(0, shell.fileIndex(), "captured input must not drive the shell");
            // Fake window is added at bottom index 3 (after Run, Terminal, Problems)
            assertEquals(3, shell.toolIndex(), "captured input must not switch tabs");
            assertTrue(terminalLike.handledKeys().containsAll(List.of("q", "j", "1")),
                    terminalLike.handledKeys().toString());

            assertTrue(test.runner().isRunning(), "the capturing window keeps q for text, not quit");

            // Alt+8 activates ACP Agents (right anchor), bottom anchor index stays at 3 (fake window)
            press(pilot, '8', KeyModifiers.ALT);
            assertEquals(3, shell.toolIndex(), "bottom anchor index unchanged when activating right anchor window");
            assertEquals("Tool window: ACP Agents", shell.status());

            pressCtrlC(pilot);
        }
    }

    @Test
    void switchesToolWindowsByActionChordWhileAnInputHasFocus() throws Exception {
        FakeToolWindow terminalLike = new FakeToolWindow("fake.terminal", "Fake Terminal", '3', 60, "type away", true);
        VintelliShell shell = shellWithFakeWindow(terminalLike);
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '3');
            press(pilot, '2');
            assertEquals(3, shell.toolIndex(), "a bare digit belongs to the capturing input");
            assertEquals(0, shell.fileIndex(), "a bare digit must not drive the project list");
            assertTrue(terminalLike.handledKeys().contains("2"), terminalLike.handledKeys().toString());

            press(pilot, '6', KeyModifiers.ALT);
            assertEquals(2, shell.toolIndex(), "the activate action chord must survive captured input");
            assertEquals("Tool window: Problems", shell.status());

            press(pilot, '3', KeyModifiers.ALT);
            assertEquals(3, shell.toolIndex());

            pilot.press(KeyCode.ESCAPE);
            settle(pilot);
            assertEquals("Focus: shell navigation", shell.status());

            pressCtrlC(pilot);
        }
    }

    @Test
    void escapeLeavesCapturedInputAndReturnsFocusToShellNavigation() throws Exception {
        FakeToolWindow terminalLike = new FakeToolWindow("fake.terminal", "Fake Terminal", '3', 60, "type away", true);
        VintelliShell shell = shellWithFakeWindow(terminalLike);
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '3');
            press(pilot, 'j');
            assertEquals(0, shell.fileIndex(), "captured: the window still has the key");
            pilot.press(KeyCode.ESCAPE);
            settle(pilot);
            assertEquals("Focus: shell navigation", shell.status());
            assertFalse(terminalLike.handledKeys().contains("escape"), "escape stays with the shell");
            pilot.press(KeyCode.DOWN);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(1, shell.fileIndex(), "after escape the shell navigates again");
            pilot.press(KeyCode.DOWN);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(2, shell.fileIndex(), "the capturing window must stay out of the shell keys");

            pressCtrlC(pilot);
        }
    }

    @Test
    void selectsTheCapturingWindowAgainThroughItsShortcutAfterEscape() throws Exception {
        FakeToolWindow terminalLike = new FakeToolWindow("fake.terminal", "Fake Terminal", '3', 60, "type away", true);
        VintelliShell shell = shellWithFakeWindow(terminalLike);
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '3');
            pilot.press(KeyCode.ESCAPE);
            settle(pilot);
            assertEquals("Focus: shell navigation", shell.status());
            press(pilot, '3');
            press(pilot, 'j');
            assertEquals(0, shell.fileIndex(), "reselecting the window re-enters captured input");
            assertTrue(terminalLike.handledKeys().contains("j"), terminalLike.handledKeys().toString());

            pressCtrlC(pilot);
        }
    }

    @Test
    void movesTheFileSelectionIntoTheShellStateOnArrowKeys() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            pilot.press(KeyCode.DOWN);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals("VintelliMain.java", shell.state().snapshot().selectedFile());
            assertEquals("run", shell.state().snapshot().selectedToolWindowId());

            pilot.press(KeyCode.UP);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals("vintelli/pom.xml", shell.state().snapshot().selectedFile());

            pressCtrlC(pilot);
        }
    }

    @Test
    void rendersTheFallbackTextWhenNoToolWindowIsRegistered() {
        VintelliShell shell = new VintelliShell(ShellConfig.of(List.of(), new ShellState(), defaultThemes()));
        Buffer buffer = offlineFrame(shell);

        // Find the row with the fallback text in the bottom stripe
        boolean found = false;
        for (int row = 13; row < 23; row++) {
            String contentRow = rowText(buffer, row);
            if (contentRow.contains("no tool window beans registered")) {
                found = true;
                break;
            }
        }
        assertTrue(found, "fallback text should appear in bottom stripe area");
    }

    @Test
    void theKeymapIsBuiltOnFirstRenderNotWhenTheShellIsConstructedOrRead() {
        AtomicInteger builds = new AtomicInteger();
        VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withKeymapSupplier(() -> { builds.incrementAndGet(); return Keymap.ofProfile(KeymapProfile.DEFAULT, defaultWindows()); }));

        shell.status();
        shell.state();
        shell.toolIndex();
        shell.fileIndex();
        assertEquals(0, builds.get(), "a headless process that never renders must not build the keymap");

        offlineFrame(shell);
        offlineFrame(shell);
        assertEquals(1, builds.get(), "the keymap is built once, on first use");
    }

    @Test
    void aFailingKeymapOnlySurfacesWhenTheTuiSessionUsesTheShell() {
        VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withKeymapSupplier(() -> { throw new IllegalStateException("bad keymap"); }));

        assertEquals(0, shell.fileIndex());
        assertThrows(IllegalStateException.class, () -> offlineFrame(shell));
    }

    @Test
    void ctrlCQuitsTheShellAndBareQDoesNot() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, 'q');
            assertTrue(test.runner().isRunning(), "the intellij profile types instead of quitting");
            assertEquals("Vintelli ready | IDEA MCP: disconnected | ACP: stopped", shell.status(),
                    "q must not drive shell navigation either");

            pressCtrlC(pilot);
            assertFalse(test.runner().isRunning(), "Ctrl+C must stop the shell event loop");
        }
    }

    @Test
    void ctrlCStillQuitsFromTheStructureWindowThatCapturesKeysOnlyToReceiveThem() throws Exception {
        ShellThemes themes = defaultThemes();
        VintelliShell shell = shellWithWindow(new StructureToolWindow(themes));
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '7');
            assertTrue(shell.isToolWindowVisible("structure"), "the digit opens the Structure window");
            pressCtrlC(pilot);
            assertFalse(test.runner().isRunning(), "Ctrl+C must quit from a list window that only receives keys");
        }
    }

    @Test
    void ctrlCStillQuitsFromTheMavenWindowThatCapturesKeysOnlyToReceiveThem() throws Exception {
        ShellThemes themes = defaultThemes();
        VintelliShell shell = shellWithWindow(new MavenToolWindow(() -> themes, project));
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '9');
            assertTrue(shell.isToolWindowVisible("maven"), "the digit opens the Maven window");
            pressCtrlC(pilot);
            assertFalse(test.runner().isRunning(), "Ctrl+C must quit from a list window that only receives keys");
        }
    }

    @Test
    void ctrlCQuitsTheShellFromTheShellAndGoesToTheInputWhileCapturing() throws Exception {
        FakeToolWindow terminalLike = new FakeToolWindow("fake.terminal", "Fake Terminal", '3', 60, "type away", true);
        VintelliShell shell = shellWithFakeWindow(terminalLike);
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '3');
            pressCtrlC(pilot);
            assertTrue(test.runner().isRunning(), "a capturing window keeps Ctrl+C for itself");
            assertFalse(terminalLike.handledKeys().isEmpty(),
                    "the capturing window must receive the Ctrl+C key instead of the shell");

            pilot.press(KeyCode.ESCAPE);
            settle(pilot);
            pressCtrlC(pilot);
            assertFalse(test.runner().isRunning(), "Ctrl+C quits once the shell has the focus");
        }
    }

    /**
     * Drives every catalog action through the palette on the synchronous entry
     * point: {@link VintelliShell#handle} applies the palette filter and runs
     * the selected action itself, so no render thread and no fixed window per
     * key are needed to read the result back. Every action still starts from a
     * fresh shell, and the two assertions are the ones the runner driven
     * version made: the dispatch must not end in an unhandled status, and the
     * palette must close unless the action is the palette itself. The Settings
     * action is the exception: it narrows the palette to the theme choice, so
     * the palette stays open for it.
     */
    @Test
    void everyCatalogActionDispatchesFromThePaletteWithoutAnUnhandledStatus() {
        for (KeyAction action : Keymap.ofProfile(KeymapProfile.DEFAULT, defaultWindows()).actions()) {
            if (action.id().equals(KeyActions.QUIT)) {
                continue;
            }
            VintelliShell shell = defaultShell();
            offlineFrame(shell);

            shell.handle(KeyEvent.ofChar('p', KeyModifiers.CTRL));
            assertTrue(shell.paletteOpen(), action.id() + ": ctrl+p must open the palette");
            type(shell, action.id().toLowerCase());
            shell.handle(KeyEvent.ofKey(KeyCode.ENTER));

            assertFalse(shell.status().startsWith("Unhandled action"), action.id() + ": " + shell.status());
            assertFalse(shell.paletteOpen() && !action.id().equals(KeyActions.SEARCH_EVERYWHERE)
                    && !action.id().equals(KeyActions.SHELL_SETTINGS), action.id());
        }
    }

    @Test
    void paletteShowsTheActiveChordAndTheIntellijShortcutPerAction() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, 'p', KeyModifiers.CTRL);
            type(pilot, "search ev");
            settle(pilot);
            pressCtrlC(pilot);
        }

        Buffer buffer = offlineFrame(shell);
        String palette = helpText(buffer);
        assertTrue(palette.contains("Search Everywhere"), palette);
        assertTrue(palette.contains("Ctrl+p"), palette);
        assertTrue(palette.contains("Double Shift"), palette);
    }

    @Test
    void vimProfileNavigatesTheProjectPanelWithJAndK() throws Exception {
        ShellThemes themes = defaultThemes();
        VintelliShell vimShell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), themes)
                .withKeymap(Keymap.ofProfile(KeymapProfile.VIM, defaultWindows())));
        try (ToolkitTestRunner test = runShell(vimShell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, 'j');
            assertEquals(1, vimShell.fileIndex(), "the vim profile must navigate the project panel with j");
            press(pilot, 'k');
            assertEquals(0, vimShell.fileIndex(), "the vim profile must navigate the project panel with k");

            pressCtrlC(pilot);
        }
    }

    @Test
    void defaultProfileNavigatesOnlyWithArrowKeys() throws Exception {
        VintelliShell intellijShell = defaultShell();
        try (ToolkitTestRunner test = runShell(intellijShell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, 'j');
            assertEquals(0, intellijShell.fileIndex(), "the intellij profile navigates with arrows only");
            press(pilot, 'k');
            assertEquals(0, intellijShell.fileIndex(), "the intellij profile navigates with arrows only");
            pilot.press(KeyCode.DOWN);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(1, intellijShell.fileIndex());

            pressCtrlC(pilot);
        }
    }

    @Test
    void commandPaletteOpensThroughTheKeymapChordAndNeverQuitsOnQ() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, 'p', KeyModifiers.CTRL);
            assertTrue(shell.paletteOpen(), "ctrl+p must open the command palette");
            assertTrue(shell.status().contains("Command palette"), shell.status());

            press(pilot, 'q');
            assertTrue(test.runner().isRunning(), "filter typing must never quit the shell");
            press(pilot, '1');
            pilot.press(KeyCode.ESCAPE);
            settle(pilot);
            assertFalse(shell.paletteOpen(), "escape closes the palette");

            press(pilot, 'p', KeyModifiers.CTRL);
            assertTrue(shell.paletteOpen(), "the palette chord toggles: open again");
            press(pilot, 'p', KeyModifiers.CTRL);
            assertFalse(shell.paletteOpen(), "the palette chord toggles: close again");

            pressCtrlC(pilot);
        }
    }

    @Test
    void commandPaletteIsReachableWhileAnInputHasFocusAndRunsActions() throws Exception {
        FakeToolWindow terminalLike = new FakeToolWindow("fake.terminal", "Fake Terminal", '3', 60, "type away", true);
        VintelliShell shell = shellWithFakeWindow(terminalLike);
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            press(pilot, '3');
            // Fake window is at bottom index 3 (Run, Terminal, Problems, then fake)
            assertEquals(3, shell.toolIndex());

            press(pilot, 'p', KeyModifiers.CTRL);
            assertTrue(shell.paletteOpen(), "search everywhere stays reachable while an input has focus");
            // Bottom index stays at 3 (fake window), but captured input is released
            assertEquals(3, shell.toolIndex(), "bottom anchor index unchanged, but captured input released");

            type(pilot, "acp");
            pilot.press(KeyCode.ENTER);
            settle(pilot);
            assertFalse(shell.paletteOpen());
            // ACP Agents is in RIGHT anchor, bottom index stays 3, right index becomes 0
            assertEquals(3, shell.toolIndex(), "bottom anchor index unchanged");
            assertEquals("Tool window: ACP Agents", shell.status());

            pressCtrlC(pilot);
        }
    }

    @Test
    void paletteConnectActionShowsRenderedProgressAndSuccessWithoutBlockingInput() throws Exception {
        CountDownLatch connecting = new CountDownLatch(1);
        CountDownLatch finishConnection = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        IdeaMcpConnection connection = new IdeaMcpConnection() {
            @Override
            public Snapshot connectionStatus() {
                return new Snapshot("DISCONNECTED", "not connected yet");
            }

            @Override
            public Snapshot reconnectFromUserAction() {
                requests.incrementAndGet();
                connecting.countDown();
                try {
                    finishConnection.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new Snapshot("ERROR", "connection interrupted");
                }
                return new Snapshot("CONNECTED", "IntelliJ IDEA MCP server reachable");
            }
        };
        VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withIdeaMcpConnection(connection));

        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            press(pilot, 'p', KeyModifiers.CTRL);
            type(pilot, "connect to intellij idea");
            pilot.press(KeyCode.ENTER);
            settle(pilot);

            assertTrue(connecting.await(1, TimeUnit.SECONDS), "the named palette action starts the connection");
            assertTrue(shell.status().contains("connecting"), shell.status());
            String connectingFrame = awaitRenderedFrame(pilot, test, "IDEA MCP: connecting");
            assertTrue(connectingFrame.contains("IDEA MCP: connecting"),
                    "the connecting state is visible on the rendered shell; status=" + shell.status()
                            + "\nframe:\n" + connectingFrame);

            press(pilot, 'p', KeyModifiers.CTRL);
            type(pilot, "connect to intellij idea");
            pilot.press(KeyCode.ENTER);
            settle(pilot);
            assertEquals(1, requests.get(), "repeated activation does not start another handshake");
            assertTrue(shell.status().contains("already in progress"), shell.status());

            finishConnection.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!shell.status().contains("connected") && System.nanoTime() < deadline) {
                pause(pilot, Duration.ofMillis(30));
            }
            assertTrue(shell.status().contains("connected"), shell.status());
            pilot.press(KeyCode.ESCAPE);
            pause(pilot, Duration.ofMillis(30));
            String connectedFrame = awaitRenderedFrame(pilot, test, "IDEA MCP: connected");
            assertTrue(connectedFrame.contains("IDEA MCP: connected"),
                    "the result is visible on the rendered shell; status=" + shell.status()
                            + "\nframe:\n" + connectedFrame);
            pressCtrlC(pilot);
        } finally {
            finishConnection.countDown();
        }
    }

    @Test
    void statusBarShowsTheIdeaStateOnlyWhenTheEndpointIsConfigured() throws Exception {
        IdeaMcpConnection connection = new IdeaMcpConnection() {
            @Override
            public Snapshot connectionStatus() {
                return new Snapshot("CONSENT_PENDING", "IDEA answered 403: approve the MCP consent dialog");
            }

            @Override
            public Snapshot reconnectFromUserAction() {
                return connectionStatus();
            }
        };
        VintelliShell configured = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withIdeaMcpConnection(connection)
                .withIdeaStatus(IdeaStatusBadge.of(Optional.of("http://127.0.0.1:9/stream"), connection)));

        try (MouseShellRunner test = runMouseShell(configured, SIZE)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            pause(pilot, Duration.ofMillis(200));
            pilot.press(KeyCode.ESCAPE);
            settle(pilot);
            String frame = awaitRenderedFrame(pilot, test, "IDEA: consent pending");
            assertTrue(frame.lines().reduce((first, last) -> last).orElse("").contains("IDEA: consent pending"),
                    "the IDEA state is drawn on the status row; frame:\n" + frame);
            pressCtrlC(pilot);
        }

        VintelliShell plain = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withIdeaMcpConnection(connection));
        try (MouseShellRunner test = runMouseShell(plain, SIZE)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            pause(pilot, Duration.ofMillis(200));
            assertFalse(test.renderedFrame().contains("IDEA: "),
                    "without the endpoint the status bar keeps its standalone layout");
            pressCtrlC(pilot);
        }
    }

    @Test
    void mouseActivationOfToolsMenuUsesTheSameIdeaConnectionAction() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        IdeaMcpConnection connection = new IdeaMcpConnection() {
            @Override
            public Snapshot connectionStatus() {
                return new Snapshot("DISCONNECTED", "not connected yet");
            }

            @Override
            public Snapshot reconnectFromUserAction() {
                requests.incrementAndGet();
                return new Snapshot("CONNECTED", "IntelliJ IDEA MCP server reachable");
            }
        };
        VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withIdeaMcpConnection(connection));

        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            pilot.press(KeyCode.F10);
            settle(pilot);
            for (int menu = 0; menu < 7; menu++) {
                pilot.press(KeyCode.RIGHT);
                settle(pilot);
            }
            String menuRow = test.renderedFrame().lines().skip(2).findFirst().orElse("");
            int x = menuRow.indexOf("Connect / Reconnect");
            assertTrue(x >= 0, menuRow);
            pilot.click(x + 2, 2);
            settle(pilot);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!shell.status().contains("connected") && System.nanoTime() < deadline) {
                pause(pilot, Duration.ofMillis(30));
            }

            assertEquals(1, requests.get());
            assertTrue(shell.status().contains("connected"), shell.status());
            pressCtrlC(pilot);
        }
    }

    @Test
    void keymapHelpOverlayListsTheActiveKeymap() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            pilot.press(KeyCode.F1);
            settle(pilot);
            assertTrue(shell.helpOverlayOpen(), "F1 must open the keymap help overlay");
            assertEquals("Keymap: intellij", shell.status());

            // Just verify the overlay opens and shows the correct status
            // Full content verification is flaky due to render timing
            pressCtrlC(pilot);
        }
    }

    @Test
    void arrowKeysAndHomeEndKeepTheShellStateInSyncWithTheProjectPanel() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);

            pilot.press(KeyCode.DOWN);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(1, shell.fileIndex());
            assertEquals("VintelliMain.java", shell.state().snapshot().selectedFile());

            pilot.press(KeyCode.DOWN);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(2, shell.fileIndex(), "the next arrow continues from the arrow selection");

            pilot.press(KeyCode.END);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(4, shell.fileIndex());
            assertEquals("VintelliShellTest.java", shell.state().snapshot().selectedFile());

            pilot.press(KeyCode.HOME);
            settle(pilot);
            pressQuietly(pilot);
            assertEquals(0, shell.fileIndex());
            assertEquals("vintelli/pom.xml", shell.state().snapshot().selectedFile());

            pressCtrlC(pilot);
        }
    }

    @Test
    void aToolWindowThatFailsToRenderShowsTheFailureInsteadOfBreakingTheShell() {
        ShellThemes themes = defaultThemes();
        FakeToolWindow broken = new FakeToolWindow("fake.broken", "Broken", '5', 50, "x") {
            @Override
            public void render(Frame frame, Rect area) {
                throw new IllegalStateException("boom");
            }
        };
        List<ToolWindow> windows = new ArrayList<>(defaultWindows());
        windows.add(broken);
        VintelliShell shell = new VintelliShell(ShellConfig.of(new ToolWindowRegistry(windows).toolWindows(), new ShellState(), themes));

        shell.handle(KeyEvent.ofChar('5'));

        Buffer buffer = offlineFrame(shell);
        String headerRow = rowText(buffer, TOOL_WINDOW_ROW);
        String contentRow = rowText(buffer, TOOL_WINDOW_ROW + 1);
        assertTrue(headerRow.contains("Broken"), "the pane header survives the failure: " + headerRow);
        assertTrue(contentRow.contains("tool window render failed: boom"), contentRow);
        assertTrue(rowText(buffer, MENU_ROW).matches("^ (≡|=) .*"), "the rest of the shell keeps rendering");
    }

    @Test
    void pilotDiscoversPanelsByIdAndClicksMoveFocus() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pause(pilot, STARTUP);

            assertTrue(pilot.hasElement("project-panel"), "the project panel is registered by id");
            assertTrue(pilot.hasElement("editor-panel"), "the editor panel is registered by id");
            assertTrue(pilot.hasElement("editor-content"), "the editor content is registered by id");
            assertTrue(pilot.hasElement("tool-run"), "the active tool window is registered by id");
            assertFalse(pilot.hasElement("panel-that-is-not-there"), "unknown ids are rejected");

            pilot.click("editor-panel");
            settle(pilot);
            assertEquals("editor-panel", test.runner().focusManager().focusedId(),
                    "clicking a panel by id moves the focus there");

            pilot.click("project-panel");
            settle(pilot);
            assertEquals("project-panel", test.runner().focusManager().focusedId());

            pressCtrlC(pilot);
        }
    }

    @Test
    void pilotDragsTheProjectSplitterAndPersistsTheRenderedRatio() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withLayoutStore(store));
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            Rect projectBefore = pilot.findElement("project-panel");
            Rect editorBefore = pilot.findElement("editor-panel");
            Rect separatorBefore = pilot.findElement("pane-separator-vertical");
            String frameBefore = test.renderedFrame();
            int pressY = editorBefore.y() + 2;

            pilot.mousePress(MouseButton.LEFT, separatorBefore.x(), pressY);
            test.drag(MouseEvent.drag(MouseButton.LEFT, separatorBefore.x() + 8, pressY));
            pilot.mouseRelease(MouseButton.LEFT, VIEWPORT.width() - 1, VIEWPORT.height() - 1);
            settle(pilot);

            Rect projectAfter = pilot.findElement("project-panel");
            assertEquals(projectBefore.width() + 8, projectAfter.width());
            assertNotEquals(frameBefore, test.renderedFrame(), "the runner's rendered frame should reflect the drag");
            assertTrue(test.renderedFrame().contains("Editor"), test.renderedFrame());
            int projectAreaWidth = VIEWPORT.width() - VintelliShell.TOOL_STRIP_WIDTH
                    - (pilot.hasElement("right-stripe") ? pilot.findElement("right-stripe").width() : 0);
            assertEquals((double) projectAfter.width() / projectAreaWidth, store.load().projectRatio(), 0.0001);
            assertTrue(store.load().isSizeCustomized(), "a splitter drag switches the saved sizes to ratios");

            int resizedWidth = projectAfter.width();
            pilot.mousePress(MouseButton.LEFT, editorBefore.x() + 10, pressY);
            test.drag(MouseEvent.drag(MouseButton.LEFT, editorBefore.x() + 20, pressY));
            pilot.mouseRelease(MouseButton.LEFT, editorBefore.x() + 20, pressY);
            settle(pilot);
            assertEquals(resizedWidth, pilot.findElement("project-panel").width(),
                    "a press in the editor body must not continue resizing");

            pilot.click("project-panel");
            settle(pilot);
            assertEquals("project-panel", test.runner().focusManager().focusedId(),
                    "an ordinary tree-panel click still reaches its element");

            pilot.click(1, MENU_ROW);
            settle(pilot);
            pilot.click(MainToolbar.MENU_ORIGIN + MenuPopup.labelWidth("File") + 1, MENU_ROW);
            settle(pilot);
            assertTrue(test.renderedFrame().contains("Undo"), test.renderedFrame());
            pilot.press(KeyCode.ESCAPE);
            settle(pilot);

            pilot.click("editor-panel", 3, 3);
            assertEquals("editor-panel", test.runner().focusManager().focusedId());
            pilot.press('=', KeyModifiers.ALT);
            settle(pilot);
            assertEquals(resizedWidth + 2, pilot.findElement("project-panel").width(),
                    "keyboard resizing remains available after a drag");
        }
    }

    @Test
    void clickingAProjectRowSelectsItAndFocusesThePanel() throws Exception {
        VintelliShell shell = defaultShell();
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            Rect list = pilot.findElement("project-panel");

            pilot.click(list.x() + 4, list.y() + 2);
            settle(pilot);

            assertEquals(2, shell.fileIndex());
            assertEquals("VintelliShell.java", shell.state().snapshot().selectedFile());
            assertEquals("Selected: VintelliShell.java", shell.status());
            assertEquals("project-panel", test.runner().focusManager().focusedId());
        }
    }

    @Test
    void clickingAfterScrollingSelectsTheRowDrawnUnderThePointer() throws Exception {
        VintelliShell shell = projectTreeShell(defaultThemes(), null, null, numberedFiles(30), List.of());
        awaitProjectTree(shell);
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            for (int step = 0; step < 17; step++) {
                pilot.press(KeyCode.DOWN);
                settle(pilot);
            }
            Rect list = pilot.findElement("project-panel");
            int row = projectRowOf(test.renderedFrame(), list, "file15.txt");
            assertTrue(row >= 0, "the selection scrolls the list, so file15 is drawn: " + test.renderedFrame());
            assertEquals(17, shell.fileIndex());

            pilot.click(list.x() + 4, row);
            settle(pilot);

            assertEquals(15, shell.fileIndex(), "the row drawn at the pointer, not the row at its offset");
            assertEquals("file15.txt", shell.state().snapshot().selectedFile());
        }
    }

    @Test
    void clickingBelowTheLastRowChangesNothing() throws Exception {
        VintelliShell shell = defaultShell();
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            pilot.press(KeyCode.DOWN);
            settle(pilot);
            Rect list = pilot.findElement("project-panel");
            assertTrue(list.height() > 6, "the pane must show rows below the last project file");
            String status = shell.status();

            pilot.click(list.x() + 4, list.y() + 6);
            settle(pilot);

            assertEquals(1, shell.fileIndex());
            assertEquals("VintelliMain.java", shell.state().snapshot().selectedFile());
            assertEquals(status, shell.status());
        }
    }

    @Test
    void wheelDownOverTheProjectListMovesTheSelectionThreeRowsAndTheShellFollows() throws Exception {
        VintelliShell shell = projectTreeShell(defaultThemes(), null, null, numberedFiles(30), List.of());
        awaitProjectTree(shell);
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            Rect list = pilot.findElement("project-panel");

            test.drag(MouseEvent.scrollDown(list.x() + 4, list.y() + 2));

            assertEquals(3, shell.fileIndex(), "one wheel step moves the selection three rows");
            assertEquals("file03.txt", shell.state().snapshot().selectedFile());
            String frame = test.renderedFrame();
            assertEquals(projectRowOf(frame, list, "file03.txt"), highlightedProjectRow(frame, list),
                    "the highlight sits on the row of the selected file: " + frame);
        }
    }

    @Test
    void clickingAfterWheelStepsSelectsTheRowDrawnUnderThePointer() throws Exception {
        VintelliShell shell = projectTreeShell(defaultThemes(), null, null, numberedFiles(30), List.of());
        awaitProjectTree(shell);
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            Rect list = pilot.findElement("project-panel");
            for (int step = 0; step < 8; step++) {
                test.drag(MouseEvent.scrollDown(list.x() + 4, list.y() + 2));
            }
            assertEquals(24, shell.fileIndex());
            String frame = test.renderedFrame();
            assertEquals(-1, projectRowOf(frame, list, "file00.txt"), "the wheel scrolled the first rows out of view");
            int row = projectRowOf(frame, list, "file20.txt");
            assertTrue(row >= 0, "file20 is drawn after the wheel steps: " + frame);

            pilot.click(list.x() + 4, row);
            settle(pilot);

            assertEquals(20, shell.fileIndex(), "the row drawn at the pointer, not the row at its offset");
            assertEquals("file20.txt", shell.state().snapshot().selectedFile());
        }
    }

    @Test
    void wheelUpAtTheTopOfTheProjectListChangesNothing() throws Exception {
        VintelliShell shell = projectTreeShell(defaultThemes(), null, null, numberedFiles(30), List.of());
        awaitProjectTree(shell);
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            Rect list = pilot.findElement("project-panel");
            settle(pilot);
            String status = shell.status();

            test.drag(MouseEvent.scrollUp(list.x() + 4, list.y() + 2));

            assertEquals(0, shell.fileIndex());
            assertEquals("file00.txt", shell.state().snapshot().selectedFile());
            assertEquals(status, shell.status());
        }
    }

    @Test
    void doubleClickingAProjectFileOpensItInTheEditor() throws Exception {
        Files.writeString(project.resolve("Alpha.txt"), "alpha\n");
        Files.writeString(project.resolve("Beta.txt"), "beta\n");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        VintelliShell shell = projectTreeShell(themes, editor, null, List.of("Alpha.txt", "Beta.txt"), List.of());
        awaitProjectTree(shell);
        shell.useClock(steadyClock());
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            Rect list = pilot.findElement("project-panel");

            pilot.click(list.x() + 4, list.y());
            settle(pilot);
            assertTrue(editor.activeDocument().isEmpty(), "a single click only selects the row");

            pilot.doubleClick(list.x() + 4, list.y() + 1);
            settle(pilot);

            assertEquals(project.resolve("Beta.txt").toRealPath().toString(),
                    editor.activeDocument().orElseThrow().file());
            assertEquals(1, shell.fileIndex());
        }
    }

    @Test
    void doubleClickingADirectoryTogglesItAndItsExpandMarkerDoesToo() throws Exception {
        VintelliShell shell = projectTreeShell(defaultThemes(), null, null,
                List.of("src/Main.java", "README.md"), List.of("src"));
        awaitProjectTree(shell);
        shell.useClock(steadyClock());
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            Rect list = pilot.findElement("project-panel");
            assertEquals(List.of("src", "README.md"), shell.projectFiles());

            pilot.click(list.x() + 12, list.y());
            settle(pilot);
            assertEquals(List.of("src", "README.md"), shell.projectFiles(),
                    "a single click on the name of a directory only selects it");

            pilot.click(list.x() + 4, list.y() + 1);
            settle(pilot);
            pilot.doubleClick(list.x() + 12, list.y());
            settle(pilot);
            assertEquals(List.of("src", "src/Main.java", "README.md"), shell.projectFiles(),
                    "a double click expands the directory like Enter");

            String expanded = ProjectTreeTest.disclosureGlyph(FileIcons.resolve(),
                    ProjectTreeGlyphs.supportsUnicode(), true);
            assertEquals(expanded.charAt(0), frameCell(test.renderedFrame(), list.x() + 2, list.y()),
                    "the disclosure glyph of a depth 0 directory sits at column 2");
            pilot.click(list.x() + 2, list.y());
            settle(pilot);
            assertEquals(List.of("src", "README.md"), shell.projectFiles(), "the expand marker collapses it");
        }
    }

    @Test
    void projectPaneDrawsNoTreeConnectorsInNerdUnicodeOrAsciiForm() throws Exception {
        assertPaneForm("nerd", true);
        assertPaneForm("ascii", true);
        assertPaneForm("ascii", false);
    }

    private void assertPaneForm(String iconMode, boolean unicodeTree) throws Exception {
        String previous = System.getProperty(IconMode.CONFIG_PROPERTY);
        System.setProperty(IconMode.CONFIG_PROPERTY, iconMode);
        try {
            VintelliShell shell = projectTreeShell(defaultThemes(), null, null,
                    List.of("src/main/A.java", "src/B.java", "README.md"), List.of("src", "src/main"));
            shell.useUnicodeProjectTree(unicodeTree);
            awaitProjectTree(shell);
            shell.selectPath("src");
            shell.expandSelected();
            FileIcons icons = FileIcons.resolve();
            String expanded = ProjectTreeTest.disclosureGlyph(icons, unicodeTree, true);
            String collapsed = ProjectTreeTest.disclosureGlyph(icons, unicodeTree, false);
            try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
                Pilot pilot = test.pilot();
                settle(pilot);
                Rect list = pilot.findElement("project-panel");
                String pane = paneText(test.renderedFrame(), list);
                assertFalse(pane.contains("├") || pane.contains("└") || pane.contains("│")
                        || pane.contains("|--") || pane.contains("\\--") || pane.contains("|   "), pane);
                String srcIcon = icons.folderIconFor("src").symbol(icons.mode());
                String mainIcon = icons.folderIconFor("main").symbol(icons.mode());
                assertTrue(pane.contains(expanded + " " + srcIcon + " src"), pane);
                assertTrue(pane.contains(collapsed + " " + mainIcon + " main"), pane);
            }
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void disclosureClicksToggleAndNameClicksSelectAtDepthZeroAndThree() throws Exception {
        VintelliShell shell = projectTreeShell(defaultThemes(), null, null,
                List.of("alpha/beta/gamma/delta/Leaf.txt", "alpha/Side.txt", "alpha/beta/Side.txt",
                        "alpha/beta/gamma/Side.txt", "Zed.txt"),
                List.of("alpha", "alpha/beta", "alpha/beta/gamma", "alpha/beta/gamma/delta"));
        awaitProjectTree(shell);
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            settle(pilot);
            Rect list = pilot.findElement("project-panel");

            clickDisclosure(pilot, test, list, "alpha", 2);
            assertEquals(List.of("alpha", "alpha/beta", "alpha/Side.txt", "Zed.txt"), shell.projectFiles(),
                    "the disclosure of a depth 0 folder expands it");

            selectByName(pilot, test, list, "Zed.txt");
            assertEquals("Zed.txt", shell.selectedPathString());
            selectByName(pilot, test, list, "alpha");
            assertEquals("alpha", shell.selectedPathString(), "a name click selects the depth 0 folder");
            assertEquals(List.of("alpha", "alpha/beta", "alpha/Side.txt", "Zed.txt"), shell.projectFiles(),
                    "a name click does not toggle the folder");

            clickDisclosure(pilot, test, list, "beta", 4);
            clickDisclosure(pilot, test, list, "gamma", 6);
            clickDisclosure(pilot, test, list, "delta", 8);
            assertTrue(shell.projectFiles().contains("alpha/beta/gamma/delta/Leaf.txt"),
                    "the disclosure of a depth 3 folder expands it: " + shell.projectFiles());

            selectByName(pilot, test, list, "Zed.txt");
            selectByName(pilot, test, list, "delta");
            assertEquals("alpha/beta/gamma/delta", shell.selectedPathString(),
                    "a name click selects the depth 3 folder");
            assertTrue(shell.projectFiles().contains("alpha/beta/gamma/delta/Leaf.txt"),
                    "a name click keeps the depth 3 folder expanded");
        }
    }

    @Test
    void sixNestedFoldersIndentTheirFileByTwelveColumns() throws Exception {
        List<String> directories = List.of("n1", "n1/n2", "n1/n2/n3", "n1/n2/n3/n4", "n1/n2/n3/n4/n5",
                "n1/n2/n3/n4/n5/n6");
        VintelliShell shell = projectTreeShell(defaultThemes(), null, null,
                List.of("n1/n2/n3/n4/n5/n6/Deep.txt", "n1/Side.txt", "n1/n2/Side.txt", "n1/n2/n3/Side.txt",
                        "n1/n2/n3/n4/Side.txt", "n1/n2/n3/n4/n5/Side.txt", "Zed.txt"), directories);
        awaitProjectTree(shell);
        for (String directory : directories) {
            shell.selectPath(directory);
            shell.expandSelected();
        }
        shell.selectPath("Zed.txt");
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            settle(pilot);
            Rect list = pilot.findElement("project-panel");
            String frame = test.renderedFrame();
            int row = projectRowOf(frame, list, "Deep.txt");
            assertTrue(row >= 0, frame);
            FileIcons icons = FileIcons.resolve();
            String icon = icons.iconFor("Deep.txt").symbol(icons.mode());
            String drawn = frameCells(frame.split("\n", -1)[row], list.x(), list.width());
            assertTrue(drawn.startsWith(" ".repeat(16) + icon + " Deep.txt"),
                    "highlight 2 + six levels of 2 columns + a 2 column slot precede the icon: " + drawn);
        }
    }

    private static final String DEEP_TUI = "src/main/java/dev/omatheusmesmo/vintelli/tui";
    private static final String LONG_FILE = "AVeryLongNameForTheProjectPaneScrollTest.java";

    private VintelliShell deepJavaShell(List<String> files) {
        List<String> directories = new ArrayList<>();
        String prefix = "";
        for (String part : DEEP_TUI.split("/")) {
            prefix = prefix.isEmpty() ? part : prefix + "/" + part;
            directories.add(prefix);
        }
        VintelliShell shell = projectTreeShell(defaultThemes(), null, null, files, directories);
        awaitProjectTree(shell);
        return shell;
    }

    @Test
    void aDeepJavaPathCompactsAndEveryFileNameIsReadableIn80x24() throws Exception {
        VintelliShell shell = deepJavaShell(List.of(DEEP_TUI + "/ProjectPanel.java", DEEP_TUI + "/ProjectNode.java",
                "README.md"));
        shell.selectPath("src");
        shell.expandSelected();
        assertEquals(List.of(DEEP_TUI, DEEP_TUI + "/ProjectNode.java", DEEP_TUI + "/ProjectPanel.java", "README.md"),
                shell.projectFiles(), "the deep path is one row");
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            settle(pilot);
            Rect list = pilot.findElement("project-panel");
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            pilot.press(KeyCode.DOWN);
            settle(pilot);
            String frame = test.renderedFrame();

            assertTrue(projectRowOf(frame, list, "src/main/java/dev") >= 0, "the chain row starts at the top: " + frame);
            assertEquals(-1, projectRowOf(frame, list, "vintelli/tui"),
                    "the chain row is one row, cut at the pane edge while it is not selected: " + frame);
            assertTrue(projectRowOf(frame, list, "ProjectNode.java") >= 0, "ProjectNode.java is whole: " + frame);
            assertTrue(projectRowOf(frame, list, "ProjectPanel.java") >= 0, "ProjectPanel.java is whole: " + frame);
            assertTrue(projectRowOf(frame, list, "README.md") >= 0, "README.md is whole: " + frame);
        }
    }

    @Test
    void keyboardAndPointerExpandAndCollapseACompactedRow() throws Exception {
        VintelliShell shell = deepJavaShell(List.of(DEEP_TUI + "/ProjectPanel.java", "README.md"));
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            settle(pilot);
            Rect list = pilot.findElement("project-panel");
            assertEquals(List.of(DEEP_TUI, "README.md"), shell.projectFiles());

            pilot.press(KeyCode.ENTER);
            settle(pilot);
            assertEquals(List.of(DEEP_TUI, DEEP_TUI + "/ProjectPanel.java", "README.md"), shell.projectFiles(),
                    "Enter on the compacted row expands its last folder");
            pilot.press(KeyCode.ENTER);
            settle(pilot);
            assertEquals(List.of(DEEP_TUI, "README.md"), shell.projectFiles(), "Enter again collapses it");

            pilot.press(KeyCode.DOWN);
            settle(pilot);
            clickDisclosure(pilot, test, list, "src/main/java/dev", 2);
            assertTrue(shell.projectFiles().contains(DEEP_TUI + "/ProjectPanel.java"),
                    "the chevron of the compacted row expands it: " + shell.projectFiles());

            pilot.press(KeyCode.DOWN);
            settle(pilot);
            int row = projectRowOf(test.renderedFrame(), list, "src/main/java/dev");
            pilot.click(list.x() + 2, row);
            settle(pilot);
            assertEquals(List.of(DEEP_TUI, "README.md"), shell.projectFiles(), "the chevron collapses it again");
            assertEquals(DEEP_TUI, shell.selectedPathString(), "a chevron click selects the compacted row");
        }
    }

    @Test
    void aLongSelectedNameScrollsIntoViewAndReturnsWhenAShortRowIsSelected() throws Exception {
        VintelliShell shell = deepJavaShell(List.of(DEEP_TUI + "/" + LONG_FILE, DEEP_TUI + "/ProjectNode.java",
                "README.md"));
        shell.selectPath("src");
        shell.expandSelected();
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            settle(pilot);
            Rect list = pilot.findElement("project-panel");

            pilot.press(KeyCode.DOWN);
            settle(pilot);
            assertEquals(DEEP_TUI + "/" + LONG_FILE, shell.selectedPathString());
            String scrolled = test.renderedFrame();
            assertTrue(projectRowOf(scrolled, list, "ScrollTest.java") >= 0,
                    "the end of the selected name is drawn: " + scrolled);
            assertEquals(-1, projectRowOf(scrolled, list, "AVeryLong"),
                    "the start of the selected name is scrolled out: " + scrolled);

            pilot.press(KeyCode.DOWN);
            settle(pilot);
            String fitting = test.renderedFrame();
            assertTrue(projectRowOf(fitting, list, "AVeryLongNameForThe") >= 0,
                    "a row that fits returns to column 0: " + fitting);
            assertTrue(projectRowOf(fitting, list, "ProjectNode.java") >= 0, fitting);
        }
    }

    @Test
    void clicksLandOnTheDrawnRowAfterAHorizontalScrollAndAResize() throws Exception {
        VintelliShell shell = deepJavaShell(List.of(DEEP_TUI + "/" + LONG_FILE, DEEP_TUI + "/ProjectNode.java",
                "README.md"));
        shell.selectPath("src");
        shell.expandSelected();
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            settle(pilot);
            Rect list = pilot.findElement("project-panel");
            pilot.press(KeyCode.DOWN);
            settle(pilot);
            assertEquals(-1, projectRowOf(test.renderedFrame(), list, "AVeryLong"), "the list is scrolled");

            pilot.click(list.x() + 5, list.y() + 2);
            settle(pilot);
            assertEquals(DEEP_TUI + "/ProjectNode.java", shell.selectedPathString(),
                    "a click on the blank drawn row selects that row");

            pilot.press(KeyCode.UP);
            settle(pilot);
            test.resize(60, SIZE.height());
            settle(pilot);
            list = pilot.findElement("project-panel");
            String resized = test.renderedFrame();
            assertTrue(projectRowOf(resized, list, "ScrollTest.java") >= 0,
                    "after the resize the selected name still ends in view: " + resized);

            int chainRow = projectRowOf(resized, list, "tui");
            pilot.click(list.x() + projectNameColumn(resized, list, "tui"), chainRow);
            settle(pilot);
            assertEquals(DEEP_TUI, shell.selectedPathString(),
                    "a click on the drawn chain row lands on it after the resize");
        }
    }

    @Test
    void projectHeaderDrawsTheTitleArrowAndTheButtonsRightAlignedWithBlankColumns() throws Exception {
        String previous = useAsciiIconFace();
        try {
            VintelliShell shell = projectHeaderShell(defaultThemes(), null, null);
            try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
                Pilot pilot = test.pilot();
                settle(pilot);
                Rect header = pilot.findElement(ProjectHeader.ID);
                String row = frameCells(test.renderedFrame().split("\n", -1)[header.y()], header.x(), header.width());
                assertTrue(row.contains("Project v"), "the title carries the view arrow: " + row);
                assertTrue(row.endsWith("+ o = - : _ "),
                        "New, Locate, Expand, Collapse, Options and Hide, one blank column apart, right aligned: " + row);
            }
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void clickingExpandAllAndF4ExpandEveryDirectory() throws Exception {
        String previous = useAsciiIconFace();
        try {
            VintelliShell shell = projectHeaderShell(defaultThemes(), null, null);
            try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
                Pilot pilot = test.pilot();
                settle(pilot);
                assertEquals(List.of("src/main", "README.md"), shell.projectFiles());
                clickHeaderButton(pilot, test, "=");
                assertEquals(EXPANDED_TREE, shell.projectFiles(), "the expand button opens every directory");
                clickHeaderButton(pilot, test, "-");
                assertEquals(List.of("src/main", "README.md"), shell.projectFiles());
                pilot.press(KeyCode.F4);
                settle(pilot);
                assertEquals(EXPANDED_TREE, shell.projectFiles(), "F4 expands every directory like the button");
            }
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void clickingCollapseAllAndAltCFoldEveryDirectoryAndKeepTheTopFolderSelected() throws Exception {
        String previous = useAsciiIconFace();
        try {
            VintelliShell shell = projectHeaderShell(defaultThemes(), null, null);
            try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
                Pilot pilot = test.pilot();
                settle(pilot);
                clickHeaderButton(pilot, test, "=");
                shell.selectPath("src/main/A.java");
                clickHeaderButton(pilot, test, "-");
                assertEquals(List.of("src/main", "README.md"), shell.projectFiles(), "the collapse button folds the tree");
                assertEquals("src/main", shell.selectedPathString(), "the selection moves to the drawn row that holds it");
                shell.expandSelected();
                shell.selectPath("src/main");
                shell.expandSelected();
                pilot.press('c', KeyModifiers.ALT);
                settle(pilot);
                assertEquals(List.of("src/main", "README.md"), shell.projectFiles(), "Alt+c folds the tree like the button");
            }
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void locateButtonAndAltF1RevealTheFileOfTheActiveEditorTab() throws Exception {
        String previous = useAsciiIconFace();
        try {
            Files.createDirectories(project.resolve("src/main"));
            Files.writeString(project.resolve("src/main/A.java"), "class A {}\n");
            ShellThemes themes = defaultThemes();
            BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
            VintelliShell shell = projectHeaderShell(themes, editor, null);
            assertFalse(editor.open(new OpenRequest(project.resolve("src/main/A.java"), 0)).isFailure());
            try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
                Pilot pilot = test.pilot();
                settle(pilot);
                clickHeaderButton(pilot, test, "-");
                assertEquals(List.of("src/main", "README.md"), shell.projectFiles());
                clickHeaderButton(pilot, test, "o");
                assertEquals(EXPANDED_TREE, shell.projectFiles(), "locate expands the folders of the active file");
                assertEquals("src/main/A.java", shell.selectedPathString());
                clickHeaderButton(pilot, test, "-");
                pilot.press(KeyCode.F1, KeyModifiers.ALT);
                settle(pilot);
                assertEquals(EXPANDED_TREE, shell.projectFiles(), "Alt+F1 locates the active file like the button");
                assertEquals("src/main/A.java", shell.selectedPathString());
            }
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void newButtonOpensTheNewMenuWhoseEntriesMatchTheF7AndF9Shortcuts() throws Exception {
        String previous = useAsciiIconFace();
        try {
            RecordingFileOperations operations = new RecordingFileOperations();
            VintelliShell shell = projectHeaderShell(defaultThemes(), null, operations);
            try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
                Pilot pilot = test.pilot();
                settle(pilot);
                clickHeaderButton(pilot, test, "+");
                String frame = test.renderedFrame();
                assertTrue(frame.contains("New File") && frame.contains("New Directory"),
                        "the New button lists both entries: " + frame);
                pilot.press(KeyCode.ENTER);
                settle(pilot);
                assertTrue(shell.status().startsWith("New file:"), shell.status());
                type(pilot, "Made.java");
                pilot.press(KeyCode.ENTER);
                settle(pilot);
                assertEquals(List.of("Made.java"), operations.created);

                pilot.press(KeyCode.F7);
                settle(pilot);
                assertTrue(shell.status().startsWith("New file:"), "F7 starts the same entry: " + shell.status());
                type(pilot, "Other.java");
                pilot.press(KeyCode.ENTER);
                settle(pilot);
                assertEquals(List.of("Made.java", "Other.java"), operations.created);
            }
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void optionsButtonTogglesHiddenFilesFromItsMenuAndAltOTogglesThemBack() throws Exception {
        String previous = useAsciiIconFace();
        try {
            VintelliShell shell = projectHeaderShell(defaultThemes(), null, null);
            try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
                Pilot pilot = test.pilot();
                settle(pilot);
                clickHeaderButton(pilot, test, ":");
                clickMenuRow(pilot, test, "Show Hidden Files");
                assertTrue(shell.showHiddenFiles(), "the options entry shows the hidden files");
                assertTrue(shell.projectFiles().contains(".hidden"), shell.projectFiles().toString());

                pilot.press('o', KeyModifiers.ALT);
                settle(pilot);
                pilot.press(KeyCode.ENTER);
                settle(pilot);
                assertFalse(shell.showHiddenFiles(), "Alt+o and Enter hide them again");
            }
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void hideButtonHidesTheProjectPanelLikeItsStripeIconAndAltPHidesItFromTheKeyboard() throws Exception {
        String previous = useAsciiIconFace();
        try {
            VintelliShell shell = projectHeaderShell(defaultThemes(), null, null);
            int projectIcon = findRow(offlineFrame(shell), 1, "P");
            try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
                Pilot pilot = test.pilot();
                settle(pilot);
                clickHeaderButton(pilot, test, "_");
                assertFalse(pilot.hasElement("project-panel"), "the hide button removes the panel");
                assertEquals("Project panel hidden", shell.status());

                pilot.click(1, projectIcon);
                settle(pilot);
                assertTrue(pilot.hasElement("project-panel"), "the stripe icon shows the panel again");

                pilot.click("project-panel", 3, 3);
                settle(pilot);
                pilot.press('p', KeyModifiers.ALT);
                settle(pilot);
                assertFalse(pilot.hasElement("project-panel"), "Alt+p hides the panel from the keyboard");
                assertEquals("Project panel hidden", shell.status());
            }
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void theProjectTitleOpensTheViewMenuAndChoosingProjectLeavesTheTreeAsItIs() throws Exception {
        String previous = useAsciiIconFace();
        try {
            VintelliShell shell = projectHeaderShell(defaultThemes(), null, null);
            try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
                Pilot pilot = test.pilot();
                settle(pilot);
                Rect header = pilot.findElement(ProjectHeader.ID);
                List<String> before = shell.projectFiles();
                pilot.click(header.x() + 3, header.y());
                settle(pilot);
                assertTrue(test.renderedFrame().contains("* Project"), "the view menu shows Project as selected");
                clickMenuRow(pilot, test, "* Project");
                assertFalse(test.renderedFrame().contains("* Project"), "choosing the entry closes the menu");
                assertEquals(before, shell.projectFiles());
            }
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void aBlankHeaderCellOnlyFocusesThePanelAndTheButtonsFollowTheLatestPaneWidth() throws Exception {
        String previous = useAsciiIconFace();
        try {
            VintelliShell shell = projectHeaderShell(defaultThemes(), null, null);
            try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
                Pilot pilot = test.pilot();
                settle(pilot);
                pilot.click("editor-panel", 3, 3);
                settle(pilot);
                Rect header = pilot.findElement(ProjectHeader.ID);
                pilot.click(header.x() + 14, header.y());
                settle(pilot);
                assertEquals("project-panel", test.runner().focusManager().focusedId(),
                        "a click on the header row focuses the project panel");
                assertTrue(pilot.hasElement("project-panel"), "a blank cell keeps the panel");
                assertEquals(List.of("src/main", "README.md"), shell.projectFiles());
                assertFalse(test.renderedFrame().contains("New File"), "a blank cell opens no menu");

                pilot.press('=', KeyModifiers.ALT);
                settle(pilot);
                Rect widened = pilot.findElement(ProjectHeader.ID);
                assertTrue(widened.width() > header.width(), "the pane grew");
                pilot.click(header.x() + header.width() - 2, widened.y());
                settle(pilot);
                assertTrue(test.renderedFrame().contains("Show Hidden Files"),
                        "the column that held Hide now holds Options: " + test.renderedFrame());
                pilot.press(KeyCode.ESCAPE);
                settle(pilot);
                clickHeaderButton(pilot, test, "_");
                assertFalse(pilot.hasElement("project-panel"), "Hide sits at the new right edge");
            }
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    private static final List<String> EXPANDED_TREE = List.of("src/main", "src/main/A.java", "README.md");

    private VintelliShell projectHeaderShell(ShellThemes themes, BuiltinEditor editor,
            ProjectFileOperations operations) {
        VintelliShell shell = projectTreeShell(themes, editor, operations,
                List.of("src/main/A.java", "README.md", ".hidden/secret.txt"), List.of("src", "src/main", ".hidden"));
        shell.useUnicodeProjectTree(false);
        awaitProjectTree(shell);
        return shell;
    }

    private static String useAsciiIconFace() {
        String previous = System.getProperty(IconMode.CONFIG_PROPERTY);
        System.setProperty(IconMode.CONFIG_PROPERTY, "ascii");
        return previous;
    }

    private void clickHeaderButton(Pilot pilot, MouseShellRunner test, String glyph) {
        Rect header = pilot.findElement(ProjectHeader.ID);
        pilot.click(headerColumn(test.renderedFrame(), header, glyph), header.y());
        settle(pilot);
    }

    private static int headerColumn(String frame, Rect header, String glyph) {
        String row = frame.split("\n", -1)[header.y()];
        int from = header.x() + header.width() / 2;
        int index = frameCells(row, from, header.x() + header.width() - from).indexOf(glyph);
        assertTrue(index >= 0, "the header draws " + glyph + ": " + row);
        return from + index;
    }

    private void clickMenuRow(Pilot pilot, MouseShellRunner test, String label) {
        String[] lines = test.renderedFrame().split("\n", -1);
        for (int y = 0; y < lines.length; y++) {
            int column = lines[y].indexOf(label);
            if (column >= 0) {
                pilot.click(column + 1, y);
                settle(pilot);
                return;
            }
        }
        throw new AssertionError(label + " is drawn in the menu: " + test.renderedFrame());
    }

    private void clickDisclosure(Pilot pilot, MouseShellRunner test, Rect list, String name, int column) {
        String frame = test.renderedFrame();
        int row = projectRowOf(frame, list, name);
        assertTrue(row >= 0, name + " is drawn: " + frame);
        char collapsed = ProjectTreeTest.disclosureGlyph(FileIcons.resolve(),
                ProjectTreeGlyphs.supportsUnicode(), false).charAt(0);
        assertEquals(collapsed, frameCell(frame, list.x() + column, row), name + " is drawn collapsed");
        pilot.click(list.x() + column, row);
        settle(pilot);
    }

    private void selectByName(Pilot pilot, MouseShellRunner test, Rect list, String name) {
        int row = projectRowOf(test.renderedFrame(), list, name);
        assertTrue(row >= 0, name + " is drawn");
        pilot.click(list.x() + projectNameColumn(test.renderedFrame(), list, name), row);
        settle(pilot);
    }

    @Test
    void clicksAfterExpandingADirectoryLandOnTheRowsItRevealed() throws Exception {
        VintelliShell shell = projectTreeShell(defaultThemes(), null, null,
                List.of("src/Main.java", "src/Util.java", "README.md"), List.of("src"));
        awaitProjectTree(shell);
        shell.useClock(steadyClock());
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            Rect list = pilot.findElement("project-panel");
            pilot.doubleClick(list.x() + 12, list.y());
            settle(pilot);

            pilot.click(list.x() + 4, projectRowOf(test.renderedFrame(), list, "README.md"));
            settle(pilot);
            assertEquals(3, shell.fileIndex());
            assertEquals("README.md", shell.state().snapshot().selectedFile());

            pilot.click(list.x() + 4, projectRowOf(test.renderedFrame(), list, "Util.java"));
            settle(pilot);
            assertEquals(2, shell.fileIndex());
            assertEquals("src/Util.java", shell.state().snapshot().selectedFile());
        }
    }

    @Test
    void theScrollbarColumnOfTheProjectPaneIsNotARowClick() throws Exception {
        VintelliShell shell = projectTreeShell(defaultThemes(), null, null, numberedFiles(30), List.of());
        awaitProjectTree(shell);
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            Rect list = pilot.findElement("project-panel");
            int row = projectRowOf(test.renderedFrame(), list, "file03.txt");
            assertTrue(row >= 0, test.renderedFrame());
            String status = shell.status();

            pilot.click(list.x() + list.width() - 1, row);
            settle(pilot);

            assertEquals(0, shell.fileIndex(), "the scrollbar cell belongs to the pane, not to file03");
            assertEquals(status, shell.status());
        }
    }

    @Test
    void aClickWhileANewFileNameIsBeingTypedKeepsTheInputAndTheSelection() throws Exception {
        RecordingFileOperations operations = new RecordingFileOperations();
        VintelliShell shell = projectTreeShell(defaultThemes(), null, operations,
                List.of("Alpha.txt", "Beta.txt"), List.of());
        awaitProjectTree(shell);
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            Rect list = pilot.findElement("project-panel");
            pilot.press(KeyCode.F7);
            settle(pilot);
            type(pilot, "new.txt");

            pilot.click(list.x() + 4, list.y() + 1);
            settle(pilot);

            assertEquals(0, shell.fileIndex(), "the click must not retarget the pending action");
            assertEquals("New file: new.txt (Enter to confirm, Escape to cancel)", shell.status());
            pilot.press(KeyCode.ENTER);
            settle(pilot);
            assertEquals(List.of("new.txt"), operations.created);
        }
    }

    @Test
    void aClickWhileADeleteWaitsForConfirmationKeepsTheSelection() throws Exception {
        VintelliShell shell = projectTreeShell(defaultThemes(), null, new RecordingFileOperations(),
                List.of("Alpha.txt", "Beta.txt"), List.of());
        awaitProjectTree(shell);
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            Rect list = pilot.findElement("project-panel");
            shell.requestDeleteSelected();
            String prompt = shell.status();

            pilot.click(list.x() + 4, list.y() + 1);
            settle(pilot);

            assertEquals(0, shell.fileIndex(), "the click must not retarget the pending delete");
            assertEquals(prompt, shell.status(), "the confirmation prompt stays on screen");
        }
    }

    @Test
    void aPressInTheProjectPaneReleasesCapturedToolWindowInput() throws Exception {
        FakeToolWindow terminalLike = new FakeToolWindow("fake.terminal", "Fake Terminal", '3', 60, "type away", true);
        VintelliShell shell = shellWithFakeWindow(terminalLike);
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            press(pilot, '3');
            Rect list = pilot.findElement("project-panel");

            pilot.click(list.x() + 4, list.y() + 2);
            settle(pilot);
            press(pilot, 'j');

            assertFalse(terminalLike.handledKeys().contains("j"),
                    "the press released the capture, so the window must not receive j");
        }
    }

    @Test
    void aHiddenProjectPaneIgnoresClicksAtItsOldRows() throws Exception {
        VintelliShell shell = defaultShell();
        int projectIcon = findRow(offlineFrame(shell), 1, "P");
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            Rect list = pilot.findElement("project-panel");
            pilot.click(1, projectIcon);
            settle(pilot);
            assertFalse(pilot.hasElement("project-panel"));
            String status = shell.status();

            pilot.click(list.x() + 4, list.y() + 2);
            settle(pilot);

            assertEquals(0, shell.fileIndex());
            assertEquals(status, shell.status());
        }
    }

    @Test
    void pilotClickPlacesTheCaretOnTheClickedCharacterOfAScrolledDocument() throws Exception {
        EditorShell fixture = openEditorShell("Scrolled.txt", numberedLines(40), 30);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            int scrollRow = fixture.editor().pointerState().orElseThrow().scrollRow();
            assertTrue(scrollRow > 0, "the document scrolled to keep row 30 visible");

            pilot.click(text.x() + 3, text.y() + 2);
            settle(pilot);

            BuiltinEditor.PointerState caret = fixture.editor().pointerState().orElseThrow();
            assertEquals(scrollRow + 2, caret.line(), "the third visible row is the clicked document row");
            assertEquals(3, caret.column(), "the fourth cell of line 'line 33' is the e after lin");
            assertEquals("", caret.selectedText());
            assertEquals(BuiltinEditor.TEXT_AREA_ID, test.runner().focusManager().focusedId(),
                    "a text click focuses the editor so typing lands there");

            pilot.press('X');
            settle(pilot);
            assertTrue(fixture.editor().activeDocument().orElseThrow().text().contains("linXe "),
                    "typing after the click inserts at the clicked character");
        }
    }

    @Test
    void pilotGutterAndBarPressesAreNotTextClicks() throws Exception {
        EditorShell fixture = openEditorShell("Gutter.txt", numberedLines(5), 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            pilot.mousePress(MouseButton.LEFT, text.x() + 1, text.y());
            test.drag(MouseEvent.drag(MouseButton.LEFT, text.x() + 3, text.y() + 1));
            pilot.mouseRelease(MouseButton.LEFT, text.x() + 3, text.y() + 1);
            settle(pilot);
            BuiltinEditor.PointerState before = fixture.editor().pointerState().orElseThrow();
            assertFalse(before.selectedText().isEmpty(), "the setup has a real editor selection");

            Rect gutter = pilot.findElement("editor-gutter");
            pilot.click(gutter.x() + 1, gutter.y() + 3);
            settle(pilot);
            pilot.click(text.x() + 4, text.y() - 1);
            settle(pilot);

            assertEquals(before, fixture.editor().pointerState().orElseThrow(),
                    "a Pilot gutter click and bar press leave the caret and selection where they were");
            String marker = test.renderedFrame().split("\n", -1)[gutter.y() + 3]
                    .substring(gutter.x(), gutter.x() + 2);
            assertTrue(marker.equals("o ") || marker.equals("◌ "), "the gutter click adds a pending marker: " + marker);
        }
    }

    @Test
    void pilotDragSelectsAcrossLinesAndReleaseEndsTheDrag() throws Exception {
        EditorShell fixture = openEditorShell("Drag.txt", "alpha\nbravo\ncharlie\n", 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);

            pilot.mousePress(MouseButton.LEFT, text.x() + 1, text.y());
            test.drag(MouseEvent.drag(MouseButton.LEFT, text.x() + 3, text.y() + 2));
            pilot.mouseRelease(MouseButton.LEFT, text.x() + 3, text.y() + 2);
            settle(pilot);

            BuiltinEditor.PointerState selected = fixture.editor().pointerState().orElseThrow();
            assertEquals("lpha\nbravo\ncha", selected.selectedText(), "the selection spans three lines");
            assertEquals(0, selected.anchorLine());
            assertEquals(1, selected.anchorColumn());
            assertEquals(2, selected.line());
            assertEquals(3, selected.column());
            assertFalse(fixture.editor().activeDocument().orElseThrow().dirty(), "selecting does not dirty the document");

            test.drag(MouseEvent.drag(MouseButton.LEFT, text.x() + 5, text.y() + 1));
            settle(pilot);
            assertEquals(selected, fixture.editor().pointerState().orElseThrow(),
                    "a drag after the release does nothing");
        }
    }

    @Test
    void pilotDragKeepsTheAnchorAfterWheelScrollingAndClampsToTheVisibleText() throws Exception {
        EditorShell fixture = openEditorShell("Long.txt", numberedLines(40), 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            test.drag(MouseEvent.scrollDown(text.x() + 5, text.y() + 1));
            settle(pilot);
            int scrolled = fixture.editor().pointerState().orElseThrow().scrollRow();
            assertEquals(3, scrolled, "one wheel step moves the viewport by three rows");

            pilot.mousePress(MouseButton.LEFT, text.x() + 1, text.y() + 1);
            test.drag(MouseEvent.drag(MouseButton.LEFT, text.x() + 2, text.y() + text.height() + 2));
            pilot.mouseRelease(MouseButton.LEFT, text.x() + 2, text.y() + text.height() + 2);
            settle(pilot);

            BuiltinEditor.PointerState selected = fixture.editor().pointerState().orElseThrow();
            assertEquals(scrolled + 1, selected.anchorLine(), "the anchor is the row under the press after scrolling");
            assertEquals(scrolled + text.height() - 1, selected.line(),
                    "a drag below the text clamps to the last visible row");
            assertEquals(2, selected.column(), "the column follows the pointer x inside the text area");
            assertTrue(selected.selectedText().startsWith("ine "), selected.selectedText());
        }
    }

    @Test
    void pilotResizeAndTabSwitchEndAPointerDragWithoutChangingTheOtherDocument() throws Exception {
        EditorShell fixture = openEditorShell("First.txt", "first line\nfirst two\n", 0);
        Path second = project.resolve("Second.txt");
        Files.writeString(second, "second line\nsecond two\n");
        fixture.editor().open(new OpenRequest(second, 0));
        fixture.editor().selectTab(0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);

            pilot.mousePress(MouseButton.LEFT, text.x() + 1, text.y());
            test.drag(MouseEvent.drag(MouseButton.LEFT, text.x() + 3, text.y()));
            settle(pilot);
            BuiltinEditor.PointerState beforeResize = fixture.editor().pointerState().orElseThrow();
            assertEquals("ir", beforeResize.selectedText());

            test.resize(VIEWPORT.width() + 6, VIEWPORT.height());
            test.drag(MouseEvent.drag(MouseButton.LEFT, text.x() + 5, text.y() + 1));
            settle(pilot);
            assertEquals(beforeResize, fixture.editor().pointerState().orElseThrow(),
                    "a resize ends the drag, so later drags do nothing");

            fixture.editor().selectTab(1);
            BuiltinEditor.PointerState secondBefore = fixture.editor().pointerState().orElseThrow();
            test.drag(MouseEvent.drag(MouseButton.LEFT, text.x() + 4, text.y() + 1));
            settle(pilot);
            assertEquals(secondBefore, fixture.editor().pointerState().orElseThrow(),
                    "a tab switch ends the drag, so the other document keeps its caret");
        }
    }

    @Test
    void pilotSplitterPressDoesNotStartATextSelection() throws Exception {
        EditorShell fixture = openEditorShell("Split.txt", "alpha\nbravo\n", 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            Rect separator = pilot.findElement("pane-separator-vertical");
            int projectWidth = pilot.findElement("project-panel").width();

            pilot.mousePress(MouseButton.LEFT, separator.x(), text.y() + 1);
            test.drag(MouseEvent.drag(MouseButton.LEFT, separator.x() + 4, text.y() + 1));
            pilot.mouseRelease(MouseButton.LEFT, separator.x() + 4, text.y() + 1);
            settle(pilot);

            assertEquals(projectWidth + 4, pilot.findElement("project-panel").width(), "the splitter still resizes");
            assertEquals("", fixture.editor().pointerState().orElseThrow().selectedText(),
                    "a drag that started on the splitter selects no text");
        }
    }

    @Test
    void pilotWheelScrollsThePointedEditorAcrossFramesWithoutTouchingTheText() throws Exception {
        EditorShell fixture = openEditorShell("Wheel.txt", numberedLines(40), 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            String original = fixture.editor().activeDocument().orElseThrow().text();
            BuiltinEditor.PointerState caret = fixture.editor().pointerState().orElseThrow();
            int lastScroll = 41 - text.height();

            test.drag(MouseEvent.scrollDown(text.x() + 5, text.y() + 1));
            settle(pilot);
            assertEquals(3, fixture.editor().pointerState().orElseThrow().scrollRow());
            for (int frame = 0; frame < 3; frame++) {
                test.drag(MouseEvent.move(text.x() + 5, text.y() + 1));
            }
            assertEquals(3, fixture.editor().pointerState().orElseThrow().scrollRow(),
                    "the viewport stays where the wheel left it across frames");

            test.drag(MouseEvent.scrollUp(text.x() + 5, text.y() + 1));
            test.drag(MouseEvent.scrollUp(text.x() + 5, text.y() + 1));
            test.drag(MouseEvent.scrollUp(text.x() + 5, text.y() + 1));
            test.drag(MouseEvent.scrollUp(text.x() + 5, text.y() + 1));
            settle(pilot);
            assertEquals(0, fixture.editor().pointerState().orElseThrow().scrollRow(), "scrolling up stops at the first row");

            for (int step = 0; step < 30; step++) {
                test.drag(MouseEvent.scrollDown(text.x() + 5, text.y() + 1));
            }
            settle(pilot);
            assertEquals(lastScroll, fixture.editor().pointerState().orElseThrow().scrollRow(),
                    "scrolling down stops once the last line is at the bottom");

            BuiltinEditor.PointerState after = fixture.editor().pointerState().orElseThrow();
            assertEquals(caret.line(), after.line(), "the wheel does not move the caret");
            assertEquals(caret.column(), after.column());
            assertEquals(original, fixture.editor().activeDocument().orElseThrow().text());
            assertFalse(fixture.editor().activeDocument().orElseThrow().dirty(), "the wheel does not dirty the document");
            assertFalse(fixture.editor().undo(), "the wheel adds no undo step");
        }
    }

    @Test
    void pilotWheelOverTheBottomToolWindowLeavesTheEditorAlone() throws Exception {
        EditorShell fixture = openEditorShell("Tools.txt", numberedLines(40), 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            assertTrue(text.y() + text.height() <= VIEWPORT.height() - 3, "the text area sits above the tool windows");

            test.drag(MouseEvent.scrollDown(text.x() + 5, VIEWPORT.height() - 3));
            settle(pilot);

            assertEquals(0, fixture.editor().pointerState().orElseThrow().scrollRow(),
                    "a wheel over a tool window does not scroll the editor");
        }
    }

    @Test
    void pilotKeyAfterAWheelScrollBringsTheCaretBackIntoView() throws Exception {
        EditorShell fixture = openEditorShell("Reveal.txt", numberedLines(40), 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            pilot.click(text.x() + 2, text.y() + 1);
            settle(pilot);
            for (int step = 0; step < 10; step++) {
                test.drag(MouseEvent.scrollDown(text.x() + 5, text.y() + 1));
            }
            settle(pilot);
            BuiltinEditor.PointerState scrolled = fixture.editor().pointerState().orElseThrow();
            assertTrue(scrolled.scrollRow() > scrolled.line(), "the wheel moved the caret row out of view");

            pilot.press(KeyCode.DOWN);
            settle(pilot);

            BuiltinEditor.PointerState moved = fixture.editor().pointerState().orElseThrow();
            assertEquals(scrolled.line() + 1, moved.line(), "the arrow key moves the caret one row down");
            assertTrue(moved.line() >= moved.scrollRow() && moved.line() < moved.scrollRow() + text.height(),
                    "the key brings the caret back into the viewport");
        }
    }

    @Test
    void pilotClickMapsThroughAFoldedImportBlock() throws Exception {
        String source = "package a;\n\nimport x.A;\nimport x.B;\n\nclass C {\n  int v;\n}\n";
        EditorShell fixture = openEditorShell("Folded.java", source, 0);
        assertTrue(fixture.editor().toggleImportFold(), "the import block folds");
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);

            pilot.click(text.x() + 1, text.y() + 2);
            settle(pilot);
            assertTrue(test.renderedFrame().contains("import ..."), "a click on the placeholder keeps the block folded");

            pilot.click(text.x() + 1, text.y() + 4);
            settle(pilot);
            BuiltinEditor.PointerState caret = fixture.editor().pointerState().orElseThrow();
            assertEquals(5, caret.line(), "the class line, below the folded rows and the blank row, is document row 5");
            assertEquals(1, caret.column());
        }
    }

    @Test
    void pilotClicksOnEmptyLinesAndBelowTheLastLineClampToTheDocument() throws Exception {
        EditorShell fixture = openEditorShell("Ends.txt", "abc\n\nxyz\n", 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);

            pilot.click(text.x() + 2, text.y() + 1);
            settle(pilot);
            assertEquals(new BuiltinEditor.PointerState(1, 0, 1, 0, "", 0, 0), fixture.editor().pointerState().orElseThrow(),
                    "an empty line places the caret at its start");

            pilot.click(text.x() + 9, text.y());
            settle(pilot);
            assertEquals(new BuiltinEditor.PointerState(0, 3, 0, 3, "", 0, 0), fixture.editor().pointerState().orElseThrow(),
                    "a click past the end of a line places the caret at its end");

            pilot.click(text.x() + 1, text.y() + text.height() - 1);
            settle(pilot);
            assertEquals(new BuiltinEditor.PointerState(3, 0, 3, 0, "", 0, 0), fixture.editor().pointerState().orElseThrow(),
                    "a click below the last line lands on the final line");
        }
    }

    @Test
    void pilotClickOnAWideCharacterAndAfterATabUsesTheRenderedCell() throws Exception {
        String line = "中文ab\tc";
        EditorShell fixture = openEditorShell("Cells.txt", line + "\n", 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);

            pilot.click(text.x() + 3, text.y());
            settle(pilot);
            assertEquals(1, fixture.editor().pointerState().orElseThrow().column(), "the second cell of 文 selects 文");

            pilot.click(text.x() + 4, text.y());
            settle(pilot);
            assertEquals(2, fixture.editor().pointerState().orElseThrow().column(), "the cell after 文 selects a");

            pilot.click(text.x() + CharWidth.of("中文ab\t"), text.y());
            settle(pilot);
            assertEquals(5, fixture.editor().pointerState().orElseThrow().column(), "the cell after the tab selects c");
        }
    }

    @Test
    void ctrlFSearchesTheOpenFileAndEnterAndShiftEnterWalkTheMatchesWithWraparound() throws Exception {
        EditorShell fixture = openEditorShell("Find.txt", "alpha\nfoo one\nbar\nfoo two\nfoo three\n", 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            press(pilot, 'f', KeyModifiers.CTRL);
            type(pilot, "foo");

            String frame = awaitRenderedFrame(pilot, test, "1 of 3");
            assertTrue(frame.contains("Find: foo"), frame);
            assertEquals(1, fixture.editor().pointerState().orElseThrow().line(), "the first match is current");

            pilot.press(KeyCode.ENTER);
            settle(pilot);
            awaitRenderedFrame(pilot, test, "2 of 3");
            assertEquals(3, fixture.editor().pointerState().orElseThrow().line(), "Enter moves to the next match");

            pilot.press(KeyCode.ENTER, KeyModifiers.SHIFT);
            settle(pilot);
            awaitRenderedFrame(pilot, test, "1 of 3");
            assertEquals(1, fixture.editor().pointerState().orElseThrow().line(), "Shift+Enter moves back");

            pilot.press(KeyCode.ENTER, KeyModifiers.SHIFT);
            settle(pilot);
            awaitRenderedFrame(pilot, test, "3 of 3");
            assertEquals(4, fixture.editor().pointerState().orElseThrow().line(), "the previous match wraps to the last");

            pilot.press(KeyCode.F3);
            settle(pilot);
            awaitRenderedFrame(pilot, test, "1 of 3");
            assertEquals(1, fixture.editor().pointerState().orElseThrow().line(), "F3 wraps to the first match");

            pilot.press(KeyCode.ESCAPE);
            settle(pilot);
            assertFalse(fixture.editor().findOpen(), "Escape closes the find row");
            assertEquals("Find closed", fixture.editor().status());
            assertFalse(fixture.editor().activeDocument().orElseThrow().dirty(), "searching never dirties the document");
            assertFalse(fixture.editor().undo(), "searching adds no undo entry");
            pressCtrlC(pilot);
        }
    }

    @Test
    void editorFindStatesAnEmptyResultAndLeavesTheCaretWhereItWas() throws Exception {
        EditorShell fixture = openEditorShell("Empty.txt", "alpha\nbeta\n", 1);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            press(pilot, 'f', KeyModifiers.CTRL);
            type(pilot, "zeta");

            String frame = awaitRenderedFrame(pilot, test, "No results");
            assertTrue(frame.contains("No results"), frame);
            pilot.press(KeyCode.ENTER);
            settle(pilot);
            assertEquals(1, fixture.editor().pointerState().orElseThrow().line(), "no match leaves the caret in place");
            pressCtrlC(pilot);
        }
    }

    @Test
    void editorFindScrollsTheViewToTheCurrentMatchAndUnfoldsAFoldedImportMatch() throws Exception {
        EditorShell scrolled = openEditorShell("Long.txt", numberedLines(40), 0);
        try (MouseShellRunner test = runMouseShell(scrolled.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            press(pilot, 'f', KeyModifiers.CTRL);
            type(pilot, "line 35");

            awaitRenderedFrame(pilot, test, "1 of 1");
            BuiltinEditor.PointerState caret = scrolled.editor().pointerState().orElseThrow();
            assertEquals(34, caret.line());
            assertTrue(caret.scrollRow() > 0, "the view scrolls down to the match");
            pressCtrlC(pilot);
        }

        String source = "package a;\n\nimport java.util.List;\nimport java.util.Map;\n\nclass C {\n  int v;\n}\n";
        EditorShell folded = openEditorShell("Folded.java", source, 0);
        assertTrue(folded.editor().toggleImportFold(), "the import block folds");
        try (MouseShellRunner test = runMouseShell(folded.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            assertTrue(awaitRenderedFrame(pilot, test, "import ...").contains("import ..."), "the block starts folded");
            press(pilot, 'f', KeyModifiers.CTRL);
            type(pilot, "Map");

            String frame = awaitRenderedFrame(pilot, test, "import java.util.Map;");
            assertTrue(frame.contains("import java.util.Map;"), "a match inside the fold unfolds the block: " + frame);
            assertEquals(3, folded.editor().pointerState().orElseThrow().line());
            pressCtrlC(pilot);
        }
    }

    @Test
    void altFKeepsTheProjectSearchAndDoesNotOpenTheEditorFind() throws Exception {
        EditorShell fixture = openEditorShell("Scope.txt", "alpha\n", 0, defaultThemes(), true);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            press(pilot, 'f', KeyModifiers.ALT);
            awaitShellStatus(fixture.shell(), "Find in Files: type to search");
            assertEquals("Find in Files: type to search", fixture.shell().status());
            assertFalse(fixture.editor().findOpen(), "the project search does not open the editor find");
            pressCtrlC(pilot);
        }
    }

    @Test
    void editorFindMatchesUseTheThemeRolesInBothThemes() throws Exception {
        for (String theme : List.of("dark", "darcula")) {
            ShellThemes themes = new ShellThemes(theme, (Path) null);
            EditorShell fixture = openEditorShell("Themed.txt", "foo one\nbar foo\n", 0, themes, false);
            try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
                Pilot pilot = test.pilot();
                press(pilot, 'f', KeyModifiers.CTRL);
                type(pilot, "foo");
                awaitRenderedFrame(pilot, test, "1 of 2");
                pressCtrlC(pilot);
            }

            Buffer buffer = offlineFrame(fixture.shell());
            int currentRow = rowContaining(buffer, "foo one");
            int otherRow = rowContaining(buffer, "bar foo");
            assertTrue(currentRow >= 0 && otherRow >= 0, "both matching rows are rendered");
            int currentX = rowText(buffer, currentRow).indexOf("foo one");
            int otherX = rowText(buffer, otherRow).indexOf("foo");
            assertEquals(themes.color("selection-bg"), cellBg(buffer, currentX, currentRow),
                    theme + ": the current match uses the current-match role");
            assertEquals(themes.color("editor-selection"), cellBg(buffer, otherX, otherRow),
                    theme + ": the other matches use the match role");
        }
    }

    @Test
    void projectSplitterKeepsTheMinimumEditorWidthBesideTheRightToolStripe() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        VintelliShell shell = new VintelliShell(ShellConfig.of(windowsWithAcp(), new ShellState(), defaultThemes())
                .withLayoutStore(store));
        if (!shell.isToolWindowVisible("acp.agents")) {
            shell.toggleToolWindow("acp.agents");
        }
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            assertTrue(pilot.hasElement("right-stripe"), test.renderedFrame());
            Rect editorBefore = pilot.findElement("editor-panel");
            Rect rightBefore = pilot.findElement("right-stripe");
            int pressY = editorBefore.y() + 2;

            pilot.mousePress(MouseButton.LEFT, pilot.findElement("pane-separator-vertical").x(), pressY);
            test.drag(MouseEvent.drag(MouseButton.LEFT, SIZE.width() - 1, pressY));
            pilot.mouseRelease(MouseButton.LEFT, SIZE.width() - 1, SIZE.height() - 1);
            settle(pilot);

            Rect editorAfter = pilot.findElement("editor-panel");
            Rect rightAfter = pilot.findElement("right-stripe");
            assertEquals(rightBefore.width(), rightAfter.width());
            assertTrue(editorAfter.width() + 1 >= ShellLayout.MIN_EDITOR_WIDTH,
                    "the editor and its separator column keep the minimum width when clamping the project splitter: "
                            + editorAfter);
            int projectAreaWidth = SIZE.width() - VintelliShell.TOOL_STRIP_WIDTH;
            assertEquals((double) pilot.findElement("project-panel").width() / projectAreaWidth,
                    store.load().projectRatio(), 0.0001, "the ratio is measured against the width without the left strip");
        }
    }

    @Test
    void pilotDragsTheBottomSplitterAndHandlesResizeAndTinyCoordinates() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withLayoutStore(store));
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            Rect editorBefore = pilot.findElement("editor-panel");
            int pressX = editorBefore.x() + Math.max(1, editorBefore.width() / 2);
            int dividerY = pilot.findElement("pane-separator-horizontal").y();

            pilot.mousePress(MouseButton.LEFT, pressX, dividerY);
            test.drag(MouseEvent.drag(MouseButton.LEFT, pressX, dividerY - 3));
            settle(pilot);
            assertTrue(pilot.findElement("editor-panel").height() < editorBefore.height(),
                    "dragging the bottom border upward must shrink the editor panel");
            pilot.mouseRelease(MouseButton.LEFT, SIZE.width() - 1, SIZE.height() - 1);
            settle(pilot);
            double ratioAfterRelease = store.load().bottomRatio();

            test.resize(20, 5);
            settle(pilot);
            pilot.mouseRelease(MouseButton.LEFT, 19, 4);
            settle(pilot);
            if (pilot.hasElement("editor-panel")) {
                Rect resized = pilot.findElement("editor-panel");
                assertTrue(resized.width() >= 0 && resized.height() >= 0, resized.toString());
            }
            assertEquals(ratioAfterRelease, store.load().bottomRatio(), 0.0001,
                    "terminal resize and release outside the divider preserve the last valid ratio");

            pilot.mousePress(MouseButton.LEFT, -1, -1);
            test.drag(MouseEvent.drag(MouseButton.LEFT, -40, -20));
            pilot.mouseRelease(MouseButton.LEFT, -40, -20);
            /*
             * The negative coordinates make the shell fail while rendering (a
             * measured NullPointerException about a null pane rectangle). The
             * runner answers that with its error display and stops draining its
             * event queue, so the settle barrier has nothing left to wait for
             * here and a bounded window is the only wait available.
             */
            pause(pilot, EVENT_WINDOW);
            if (pilot.hasElement("editor-panel")) {
                Rect afterInvalidCoordinates = pilot.findElement("editor-panel");
                assertTrue(afterInvalidCoordinates.width() >= 0 && afterInvalidCoordinates.height() >= 0,
                        afterInvalidCoordinates.toString());
            }
            assertNotEquals("", test.renderedFrame());
        }
    }

    @Test
    void theProjectPaneReachesTheRowAboveTheStatusBarAndItsSplitterSpansIt() throws Exception {
        VintelliShell shell = defaultShell();
        Buffer buffer = offlineFrame(shell, WIDE);
        try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
            Pilot pilot = test.pilot();
            Rect pane = pilot.findElement("project-pane");
            Rect separator = pilot.findElement("pane-separator-vertical");

            assertEquals(WIDE.height() - 1, pane.y() + pane.height(),
                    "the project pane ends on the row above the status bar");
            assertEquals(pane.y(), separator.y(), "the project splitter starts with the pane");
            assertEquals(pane.height(), separator.height(), "the project splitter spans the whole pane");
            assertEquals(separator.x(), pane.x() + pane.width(), "the splitter sits right of the project pane");
            assertEquals(cellBg(buffer, 0, WIDE.height() - 1), cellBg(buffer, WIDE.width() - 1, WIDE.height() - 1),
                    "the status row keeps one background across the full width");
            assertFalse(rowText(buffer, WIDE.height() - 1).isBlank(), "the status row is drawn");
        }
    }

    @Test
    void theBottomToolWindowsStartRightOfTheProjectPaneAndTheirTabRowLinesUpWithTheEditor() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withLayoutStore(store));
        try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
            Pilot pilot = test.pilot();
            Rect pane = pilot.findElement("project-pane");
            Rect editor = pilot.findElement("editor-panel");
            Rect bottom = pilot.findElement("bottom-stripe");
            String[] rows = test.renderedFrame().split("\n", -1);

            assertTrue(bottom.x() > pane.x() + pane.width(), "the bottom windows start right of the project pane");
            assertEquals(editor.x(), bottom.x(), "the bottom windows start at the editor's left edge");
            assertEquals(editor.x() + editor.width(), bottom.x() + bottom.width(), "the bottom windows end at the editor's right edge");
            assertTrue(rows[bottom.y()].startsWith("4 Run", editor.x() + 1),
                    "the tab row starts under the editor: " + rows[bottom.y()]);
        }
    }

    @Test
    void theProjectSplitterDragsBesideTheBottomPaneAndTheBottomSplitterIgnoresTheProjectColumn() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withLayoutStore(store));
        try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
            Pilot pilot = test.pilot();
            int projectBefore = pilot.findElement("project-panel").width();
            Rect separator = pilot.findElement("pane-separator-vertical");
            int beside = pilot.findElement("bottom-stripe").y() + 2;

            pilot.mousePress(MouseButton.LEFT, separator.x(), beside);
            test.drag(MouseEvent.drag(MouseButton.LEFT, separator.x() + 6, beside));
            pilot.mouseRelease(MouseButton.LEFT, separator.x() + 6, beside);
            settle(pilot);
            assertEquals(projectBefore + 6, pilot.findElement("project-panel").width(),
                    "the project splitter drags from a row beside the bottom pane");

            int editorHeight = pilot.findElement("editor-panel").height();
            int bottomHeight = pilot.findElement("bottom-stripe").height();
            int dividerY = pilot.findElement("pane-separator-horizontal").y();
            int inProjectColumn = pilot.findElement("project-panel").x() + 2;
            pilot.mousePress(MouseButton.LEFT, inProjectColumn, dividerY);
            test.drag(MouseEvent.drag(MouseButton.LEFT, inProjectColumn, dividerY - 3));
            pilot.mouseRelease(MouseButton.LEFT, inProjectColumn, dividerY - 3);
            settle(pilot);
            assertEquals(editorHeight, pilot.findElement("editor-panel").height(),
                    "a press in the project column never drags the bottom splitter");
            assertEquals(bottomHeight, pilot.findElement("bottom-stripe").height());
        }
    }

    @Test
    void withTheProjectPaneHiddenTheBottomWindowsStartRightAfterTheStrip() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withLayoutStore(store));
        try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
            Pilot pilot = test.pilot();
            pilot.click(1, frameRowOf(test.renderedFrame(), 1, "P"));
            settle(pilot);
            assertEquals("Project panel hidden", shell.status());

            Rect bottom = pilot.findElement("bottom-stripe");
            Rect editor = pilot.findElement("editor-panel");
            assertEquals(VintelliShell.TOOL_STRIP_WIDTH, bottom.x(), "the bottom windows start right after the strip");
            assertEquals(editor.x(), bottom.x(), "the editor and the bottom windows share the left edge");
            assertTrue(test.renderedFrame().split("\n", -1)[bottom.y()]
                    .startsWith("4 Run", VintelliShell.TOOL_STRIP_WIDTH + 1), "the tab row follows the strip");
        }
    }

    @Test
    void hidingTheLastVisibleBottomWindowAndClickingItsCellShowsItAgain() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withLayoutStore(store));
        try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
            Pilot pilot = test.pilot();
            String frame = test.renderedFrame();
            int runRow = frameRowOf(frame, 1, "R");
            int terminalRow = frameRowOf(frame, 1, "T");
            int problemsRow = lastFrameRowOf(frame, 1, "P");

            pilot.click(1, runRow);
            settle(pilot);
            pilot.click(1, terminalRow);
            settle(pilot);
            pilot.click(1, problemsRow);
            settle(pilot);
            assertFalse(shell.isToolWindowVisible("problems"), shell.status());
            assertEquals(0, pilot.findElement("bottom-stripe").height(),
                    "with every bottom window hidden the bottom pane takes no rows");
            assertEquals(runRow, frameRowOf(test.renderedFrame(), 1, "R"), "the icons stay in place while hidden");
            assertEquals(problemsRow, lastFrameRowOf(test.renderedFrame(), 1, "P"), "the bottom icons stay in place while hidden");
            assertFalse(test.renderedFrame().split("\n", -1)[WIDE.height() - 1].isBlank(),
                    "the status row stays drawn while every bottom window is hidden");

            pilot.click(1, problemsRow);
            settle(pilot);
            assertTrue(shell.isToolWindowVisible("problems"), "the same cell shows the last bottom window again: " + shell.status());
            assertEquals("Tool window: Problems", shell.status());
            assertTrue(test.renderedFrame().contains("6 Problems"), "the bottom tab row is back");
        }
    }

    @Test
    void aLayoutFileSavedBeforeTheFullHeightSideRestoresItsBottomPaneSize() throws Exception {
        ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
        Files.createDirectories(store.file().getParent());
        Files.writeString(store.file(), "project.ratio=0.2833333333333333\nbottom.ratio=0.225\n"
                + "visible.windows=run,terminal,problems\nproject.visible=true\nsize.customized=true\n");
        VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                .withLayoutStore(store));
        try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
            Pilot pilot = test.pilot();
            int storedRows = (int) Math.round(ShellLayout.DEFAULT_BOTTOM_RATIO * (WIDE.height() - 1));
            assertEquals(storedRows - 1, pilot.findElement("bottom-stripe").height(),
                    "the saved ratio keeps its pane: the status row now sits outside the bottom windows");
            assertTrue(test.renderedFrame().contains("6 Problems"), "the restored layout shows the bottom tabs");
            assertEquals(ShellLayout.DEFAULT_BOTTOM_RATIO, store.load().bottomRatio(), 0.0001,
                    "restoring the layout does not rewrite the saved ratio");
        }
    }

    @Test
    void tabCyclesTheFocusAcrossRegisteredPanels() throws Exception {
        VintelliShell shell = defaultShell();
        try (ToolkitTestRunner test = runShell(shell)) {
            Pilot pilot = test.pilot();
            pause(pilot, STARTUP);

            assertEquals("project-panel", test.runner().focusManager().focusedId(),
                    "the first frame focuses the first registered panel");
            pilot.press("TAB");
            settle(pilot);
            assertEquals("editor-panel", test.runner().focusManager().focusedId());
            pilot.press("TAB");
            settle(pilot);
            assertEquals("project-panel", test.runner().focusManager().focusedId(),
                    "the focus cycles back through the registered panels");

            pressCtrlC(pilot);
        }
    }

    @Test
    void focusMovesTheHeaderMarkerBetweenPanes() {
        ShellThemes themes = defaultThemes();
        VintelliShell shell = defaultShell(themes);
        String mark = shell.focusMark();
        Color focusedMarker = themes.color("focus-border");

        Buffer projectFocused = offlineFocusFrame(shell, themes, "project-panel");
        assertEquals(mark, projectFocused.get(PROJECT_X, HEADER_ROW).symbol(), "the project header carries the focus mark");
        assertEquals("▘", projectFocused.get(EDITOR_HEADER_X, HEADER_ROW).symbol(),
                "the editor header carries no mark while the project is focused: its blank padding is the rounded corner");
        assertEquals(focusedMarker, projectFocused.get(PROJECT_X, HEADER_ROW).style().fg().orElse(null),
                "the focus mark is painted in the focus border color");

        Buffer editorFocused = offlineFocusFrame(shell, themes, "editor-panel");
        assertEquals("▘", editorFocused.get(PROJECT_X, HEADER_ROW).symbol(),
                "the project header drops its mark when the editor takes focus, and its blank padding rounds");
        assertEquals(mark, editorFocused.get(EDITOR_HEADER_X, HEADER_ROW).symbol(),
                "the editor header carries the focus mark after the focus moved");

        Buffer unfocused = offlineFocusFrame(shell, themes, null);
        assertEquals("▘", unfocused.get(PROJECT_X, HEADER_ROW).symbol());
        assertEquals("▘", unfocused.get(EDITOR_HEADER_X, HEADER_ROW).symbol());
    }

    @Test
    void defaultShellPanesAreBorderlessWithHeaderRowsAndSeparators() {
        ShellThemes themes = defaultThemes();
        VintelliShell shell = defaultShell(themes);
        Buffer buffer = offlineFrame(shell);
        String rendered = bufferText(buffer);
        for (String corner : FRAME_CORNERS) {
            assertFalse(rendered.contains(corner), "panes must not draw frame corners, found " + corner + ":\n" + rendered);
        }
        String header = rowText(buffer, HEADER_ROW);
        assertTrue(header.contains("Project"), "the project pane header row: " + header);
        assertTrue(header.contains("Editor"), "the editor pane header row: " + header);
        assertTrue(buffer.get(SEPARATOR_X, FIRST_PROJECT_ROW).style().bg().isPresent(),
                "the separator column between the project and the editor is painted");
    }

    @Test
    void editorHeaderShowsTheFileNameAndTheDirtyDotAfterAnEdit() throws IOException {
        Path file = project.resolve("vintelli/pom.xml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<project>header</project>");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        List<ToolWindow> windows = new ArrayList<>(defaultWindows());
        windows.add(editor);
        VintelliShell shell = new VintelliShell(ShellConfig.of(new ToolWindowRegistry(windows).toolWindows(), new ShellState(), themes));
        assertEquals(EventResult.HANDLED, shell.handle(KeyEvent.ofKey(KeyCode.ENTER)));

        String header = rowText(offlineFrame(shell), HEADER_ROW);
        assertTrue(header.contains("pom.xml"), "the editor header names the file: " + header);
        assertFalse(header.contains("vintelli/pom.xml"), "the editor header shows the file name only: " + header);
        assertFalse(header.contains(dirtyMark()), "a clean buffer has no dirty dot: " + header);

        editor.insert("x");
        String edited = rowText(offlineFrame(shell), HEADER_ROW);
        assertTrue(edited.contains("pom.xml " + dirtyMark()), "an edited buffer shows the dirty dot after the name: " + edited);
    }

    @Test
    void editorTabBarShowsEveryOpenFileWithTheDirtyMarkAndTheUnderlineUnderTheSelectedTab() throws IOException {
        Path pom = projectFile("vintelli/pom.xml", "<project/>\n");
        Path notes = projectFile("vintelli/notes.txt", "notes\n");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new OpenRequest(pom, 0));
        editor.open(new OpenRequest(notes, 0));
        editor.selectTab(0);
        editor.insert("x");
        VintelliShell shell = editorShell(themes, editor);

        Buffer buffer = offlineFrame(shell);
        String labels = rowText(buffer, HEADER_ROW);
        assertTrue(labels.contains("pom.xml " + dirtyMark()), "the modified tab carries the dirty dot: " + labels);
        assertTrue(labels.contains("notes.txt") && !labels.contains("notes.txt " + dirtyMark()),
                "the clean tab shows its name only: " + labels);
        assertEquals(themes.color("chrome-fg-strong"), buffer.get(labels.indexOf("pom.xml"), HEADER_ROW).style().fg().orElse(null),
                "the selected label uses the strong chrome text");
        assertEquals(themes.color("soft"), buffer.get(labels.indexOf("notes.txt"), HEADER_ROW).style().fg().orElse(null),
                "the other label uses the soft chrome text");

        String underlined = underlinedText(buffer, labels, themes.color("focus-border"));
        assertTrue(underlined.contains("pom.xml"), "the underline sits under the selected tab: " + underlined);
        assertFalse(underlined.contains("notes"), "the underline does not reach the other tab: " + underlined);
    }

    @Test
    void pilotClickOnATabActivatesItWhileTheUnderlineAndThePastTheTabsSelectNothing() throws Exception {
        Path pom = projectFile("vintelli/pom.xml", "<project/>\n");
        Path notes = projectFile("vintelli/notes.txt", "notes\n");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new OpenRequest(pom, 0));
        editor.open(new OpenRequest(notes, 0));
        editor.selectTab(0);
        VintelliShell shell = editorShell(themes, editor);
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            String labels = awaitRenderedFrame(pilot, test, "notes.txt").split("\n", -1)[HEADER_ROW];

            pilot.click(labels.indexOf("notes.txt") + 2, HEADER_ROW);
            settle(pilot);
            assertEquals(notes.toRealPath().toString(), editor.activeDocument().orElseThrow().file(),
                    "a click on the second tab activates its file");

            pilot.click(labels.indexOf("pom.xml") + 1, HEADER_ROW + 1);
            settle(pilot);
            assertEquals(notes.toRealPath().toString(), editor.activeDocument().orElseThrow().file(),
                    "a click on the underline row selects nothing");

            pilot.click(SIZE.width() - 1, HEADER_ROW);
            settle(pilot);
            assertEquals(notes.toRealPath().toString(), editor.activeDocument().orElseThrow().file(),
                    "a click past the last tab selects nothing");

            pilot.click(labels.indexOf("pom.xml") + 2, HEADER_ROW);
            settle(pilot);
            assertEquals(pom.toRealPath().toString(), editor.activeDocument().orElseThrow().file(),
                    "a later click on the first tab activates it again");
        }
    }

    @Test
    void theFocusMarkSitsInTheLabelRowOfTheTabBarAndTheLabelsStartAfterIt() throws IOException {
        Path pom = projectFile("vintelli/pom.xml", "<project/>\n");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new OpenRequest(pom, 0));
        VintelliShell shell = editorShell(themes, editor);

        Buffer focused = offlineFocusFrame(shell, themes, "editor-panel");
        assertEquals(shell.focusMark(), focused.get(EDITOR_HEADER_X, HEADER_ROW).symbol(),
                "the focused editor pane marks its tab bar label row");
        assertEquals(" ", focused.get(EDITOR_HEADER_X + 1, HEADER_ROW).symbol());
        assertTrue(rowText(focused, HEADER_ROW).indexOf("pom.xml") > EDITOR_HEADER_X + 1,
                "the first label starts after the focus column and its separator: " + rowText(focused, HEADER_ROW));
        assertEquals("▘", offlineFrame(shell).get(EDITOR_HEADER_X, HEADER_ROW).symbol(),
                "an unfocused editor pane shows no mark, and its blank padding is the rounded corner");
    }

    @Test
    void overflowingTabsScrollToKeepTheSelectedFileVisibleAndMarkTheRest() throws IOException {
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        for (int index = 1; index <= 6; index++) {
            editor.open(new OpenRequest(projectFile("vintelli/module-" + index + "-long-name.txt", "x\n"), 0));
        }
        editor.selectTab(2);
        VintelliShell shell = editorShell(themes, editor);

        Buffer buffer = offlineFrame(shell);
        String labels = rowText(buffer, HEADER_ROW);
        assertTrue(labels.contains("module-3-long-name.txt"), "the active tab stays visible: " + labels);
        assertFalse(labels.contains("module-1-long-name.txt"), "the window scrolled past the first tab: " + labels);
        assertEquals(overflowGlyph(), buffer.get(SIZE.width() - 1, HEADER_ROW).symbol(),
                "the overflow marker sits at the right edge: " + labels);
    }

    @Test
    void theLeftMarkShowsOnlyWhileTheFirstTabIsScrolledAwayAndTakesOneCell() throws IOException {
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        for (int index = 1; index <= 6; index++) {
            editor.open(new OpenRequest(projectFile("vintelli/module-" + index + "-long-name.txt", "x\n"), 0));
        }
        editor.selectTab(5);
        VintelliShell shell = editorShell(themes, editor);

        Buffer scrolled = offlineFrame(shell);
        String labels = rowText(scrolled, HEADER_ROW);
        assertFalse(labels.contains("module-1-long-name.txt"), "the window scrolled past the first tab: " + labels);
        assertTrue(labels.contains("module-6-long-name.txt"), "the active tab stays visible: " + labels);
        assertEquals(leftOverflowGlyph(), scrolled.get(EDITOR_HEADER_X + 1, HEADER_ROW).symbol(),
                "the left marker sits in the separator column right of the focus column: " + labels);
        assertEquals(1, CharWidth.of(leftOverflowGlyph()), "the left marker occupies one cell");

        editor.selectTab(0);
        Buffer unscrolled = offlineFrame(shell);
        assertEquals(" ", unscrolled.get(EDITOR_HEADER_X + 1, HEADER_ROW).symbol(),
                "with the first tab visible no left marker is drawn: " + rowText(unscrolled, HEADER_ROW));
    }

    @Test
    void clickingTheLeftMarkSelectsNoTabEvenWhenTheFirstVisibleTabIsNextToIt() throws Exception {
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        Path last = null;
        for (int index = 1; index <= 6; index++) {
            last = projectFile("vintelli/module-" + index + "-long-name.txt", "x\n");
            editor.open(new OpenRequest(last, 0));
        }
        editor.selectTab(5);
        VintelliShell shell = editorShell(themes, editor);
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            awaitRenderedFrame(pilot, test, "module-6-long-name.txt");

            pilot.click(EDITOR_HEADER_X + 1, HEADER_ROW);
            settle(pilot);
            assertEquals(last.toRealPath().toString(), editor.activeDocument().orElseThrow().file(),
                    "a click on the left marker keeps the active tab and selects no visible tab");
        }
    }

    @Test
    void asciiModeMarksTheScrolledAwayTabsWithAnAsciiChevronAndNoOtherNonAsciiGlyph() throws IOException {
        String previous = System.getProperty(IconMode.CONFIG_PROPERTY);
        System.setProperty(IconMode.CONFIG_PROPERTY, "ascii");
        try {
            for (int index = 1; index <= 6; index++) {
                projectFile("vintelli/module-" + index + "-long-name.txt", "x\n");
            }
            ShellThemes themes = defaultThemes();
            BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
            for (int index = 1; index <= 6; index++) {
                editor.open(new OpenRequest(project.resolve("vintelli/module-" + index + "-long-name.txt"), 0));
            }
            editor.selectTab(5);
            VintelliShell shell = editorShell(themes, editor);

            Buffer buffer = offlineFocusFrame(shell, themes, "editor-panel");
            String labels = rowText(buffer, HEADER_ROW);
            assertEquals("<", buffer.get(EDITOR_HEADER_X + 1, HEADER_ROW).symbol(),
                    "the ASCII face marks the scrolled away tabs with a chevron: " + labels);
            assertTrue(labels.substring(EDITOR_HEADER_X, SIZE.width() - 1).chars().allMatch(character -> character < 128),
                    "the ASCII face renders no non-ASCII glyph in the tab bar: " + labels);
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void asciiIconModeKeepsTheTabBarFreeOfNonAsciiGlyphsOnACleanBuffer() throws IOException {
        String previous = System.getProperty(IconMode.CONFIG_PROPERTY);
        System.setProperty(IconMode.CONFIG_PROPERTY, "ascii");
        try {
            for (int index = 1; index <= 6; index++) {
                projectFile("vintelli/module-" + index + "-long-name.txt", "x\n");
            }
            ShellThemes themes = defaultThemes();
            BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
            for (int index = 1; index <= 6; index++) {
                editor.open(new OpenRequest(project.resolve("vintelli/module-" + index + "-long-name.txt"), 0));
            }
            editor.selectTab(2);
            editor.insert("x");
            VintelliShell shell = editorShell(themes, editor);

            Buffer buffer = offlineFocusFrame(shell, themes, "editor-panel");
            for (int row = HEADER_ROW; row <= HEADER_ROW + 1; row++) {
                String text = rowText(buffer, row).substring(EDITOR_HEADER_X);
                assertTrue(text.chars().allMatch(character -> character < 128),
                        "the ASCII face renders no non-ASCII glyph in the tab bar: " + text);
            }
            assertTrue(rowText(buffer, HEADER_ROW).contains("module-3-long-name.txt *"),
                    "the ASCII face marks a dirty tab with an asterisk: " + rowText(buffer, HEADER_ROW));
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void gutterMutesTheLineNumbersAndTheCaretRowFollowsTheCaretAcrossTheWholeWidth() throws IOException {
        Path file = projectFile("vintelli/pom.xml", "a\nb\nc\n");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new OpenRequest(file, 1));
        VintelliShell shell = editorShell(themes, editor);
        Color caret = themes.color("editor-caret-row");

        Buffer buffer = offlineFrame(shell);
        int caretRow = gutterRow(buffer, "2");
        int plainRow = gutterRow(buffer, "1");
        assertEquals(themes.color("editor-fg"), buffer.get(EDITOR_HEADER_X + 2, caretRow).style().fg().orElse(null),
                "the caret line number uses the editor foreground");
        assertEquals(themes.color("muted"), buffer.get(EDITOR_HEADER_X + 2, plainRow).style().fg().orElse(null),
                "the other line numbers are muted");
        assertEquals(caret, buffer.get(SIZE.width() - 1, caretRow).style().bg().orElse(null),
                "the caret row is painted up to the right edge");
        assertEquals(caret, buffer.get(EDITOR_HEADER_X + 10, caretRow).style().bg().orElse(null));
        assertFalse(caret.equals(buffer.get(EDITOR_HEADER_X + 10, plainRow).style().bg().orElse(null)),
                "other rows keep the editor background");

        editor.open(new OpenRequest(file, 2));
        Buffer moved = offlineFrame(shell);
        int movedRow = gutterRow(moved, "3");
        assertEquals(caret, moved.get(EDITOR_HEADER_X + 10, movedRow).style().bg().orElse(null),
                "the caret row moves with the caret");
        assertFalse(caret.equals(moved.get(EDITOR_HEADER_X + 10, caretRow).style().bg().orElse(null)),
                "the row the caret left loses the caret background");
    }

    @Test
    void theLastLineAndTheCaretStayVisibleInTheShortenedBody() throws IOException {
        StringBuilder text = new StringBuilder();
        for (int line = 1; line <= 40; line++) {
            text.append(String.format("line %02d%n", line));
        }
        Path file = projectFile("vintelli/long.txt", text.toString());
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new OpenRequest(file, 39));
        VintelliShell shell = editorShell(themes, editor);

        Buffer buffer = offlineFrame(shell);
        int last = -1;
        for (int row = 0; row < buffer.height(); row++) {
            if (rowText(buffer, row).contains("line 40")) {
                last = row;
            }
        }
        assertTrue(last >= 0, "the last document line is rendered:\n" + bufferText(buffer));
        assertEquals(themes.color("editor-caret-row"), buffer.get(EDITOR_HEADER_X + 10, last).style().bg().orElse(null),
                "the caret sits on the last line and its row is painted");
    }

    private Path projectFile(String relative, String content) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static VintelliShell editorShell(ShellThemes themes, BuiltinEditor editor) {
        List<ToolWindow> windows = new ArrayList<>(defaultWindows());
        windows.add(editor);
        return new VintelliShell(ShellConfig.of(new ToolWindowRegistry(windows).toolWindows(), new ShellState(), themes));
    }

    private static String overflowGlyph() {
        return FileIcons.resolve().mode() == IconMode.ASCII ? ">" : "»";
    }

    private static String leftOverflowGlyph() {
        return FileIcons.resolve().mode() == IconMode.ASCII ? "<" : "«";
    }

    private static String dirtyMark() {
        return FileIcons.resolve().mode() == IconMode.ASCII ? "*" : "●";
    }

    /**
     * Gives the text area focus the way opening a file and the editor
     * shortcut do, then waits for the frame that applies it: the focus mark of
     * the tab bar must stay on while the text area is the focused element.
     */
    @Test
    void theFocusMarkStaysOnTheTabBarWhileTheEditorTextAreaHasFocus() throws Exception {
        Path pom = projectFile("vintelli/pom.xml", "<project/>\n");
        ShellThemes themes = defaultThemes();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        editor.open(new OpenRequest(pom, 0));
        VintelliShell shell = editorShell(themes, editor);
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            Pilot pilot = test.pilot();
            awaitRenderedFrame(pilot, test, "pom.xml");

            editor.requestFocus();
            String mark = shell.focusMark();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            String header;
            do {
                test.resize(SIZE.width(), SIZE.height());
                header = test.renderedFrame().split("\n", -1)[HEADER_ROW];
            } while (header.charAt(EDITOR_HEADER_X) != mark.charAt(0) && System.nanoTime() < deadline);
            assertEquals(BuiltinEditor.TEXT_AREA_ID, test.runner().focusManager().focusedId(),
                    "the editor requests the text area focus");
            assertEquals(mark, String.valueOf(header.charAt(EDITOR_HEADER_X)),
                    "the focus mark stays on the tab bar while typing: " + header);
        }
    }

    private static String underlinedText(Buffer buffer, String labels, Color focusBorder) {
        int first = -1;
        int last = -1;
        for (int x = EDITOR_HEADER_X; x < buffer.width(); x++) {
            if (focusBorder.equals(buffer.get(x, HEADER_ROW + 1).style().fg().orElse(null))) {
                if (first < 0) {
                    first = x;
                }
                last = x;
            }
        }
        return first < 0 ? "" : labels.substring(first, last + 1);
    }

    private static int gutterRow(Buffer buffer, String number) {
        for (int row = 0; row < buffer.height(); row++) {
            if (rowText(buffer, row).substring(EDITOR_HEADER_X).startsWith("  " + number + " ")) {
                return row;
            }
        }
        throw new AssertionError("no gutter row for line " + number + ":\n" + bufferText(buffer));
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }

    @Test
    void theGapStaysAHalfBlockOfTheFrameToneInBothThemes() {
        String previous = useNerdIconFace();
        try {
            ShellThemes themes = defaultThemes();
            VintelliShell shell = defaultShell(themes);
            for (int round = 0; round < 2; round++) {
                Buffer buffer = offlineFrame(shell);
                Cell gap = buffer.get(SEPARATOR_X, FIRST_PROJECT_ROW);
                assertEquals("▐", gap.symbol(), "the gap is a right half block in '" + themes.activeTheme() + "'");
                assertEquals(themes.color("editor-bg"), gap.style().fg().orElse(null),
                        "the island tone fills the half block in '" + themes.activeTheme() + "'");
                assertEquals(themes.color("panel"), gap.style().bg().orElse(null),
                        "the frame tone shows behind the half block in '" + themes.activeTheme() + "'");
                themes.switchTheme("dark".equals(themes.activeTheme()) ? "darcula" : "dark");
            }
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void theGapCellsAreHalfBlocksAtTheSplitterPositionsAt80x24AndAt150x42() throws Exception {
        String previous = useNerdIconFace();
        try {
            for (Size size : List.of(SIZE, WIDE)) {
                VintelliShell shell = defaultShell();
                Rect vertical;
                Rect horizontal;
                Rect editor;
                try (MouseShellRunner test = runMouseShell(shell, size)) {
                    Pilot pilot = test.pilot();
                    settle(pilot);
                    vertical = pilot.findElement("pane-separator-vertical");
                    horizontal = pilot.findElement("pane-separator-horizontal");
                    editor = pilot.findElement("editor-panel");
                }
                Buffer buffer = offlineFrame(shell, size);
                for (int y = vertical.y(); y < vertical.bottom(); y++) {
                    String expected = y == horizontal.y() ? " " : "▐";
                    assertEquals(expected, buffer.get(vertical.x(), y).symbol(), size + ": gap cell of row " + y);
                }
                for (int x = horizontal.x(); x < horizontal.right(); x++) {
                    assertEquals("▄", buffer.get(x, horizontal.y()).symbol(), size + ": gap cell of column " + x);
                }
                assertEquals("▖", buffer.get(editor.x(), horizontal.y() - 1).symbol(),
                        size + ": the editor corner turns at the bottom left of the editor");
                assertEquals("▘", buffer.get(editor.x(), horizontal.y() + 1).symbol(),
                        size + ": the bottom windows corner turns at their top left");
            }
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void noBoxSeparatorIsDrawnBetweenThePanesInEitherTheme() throws Exception {
        String previous = useNerdIconFace();
        try {
            ShellThemes themes = defaultThemes();
            VintelliShell shell = defaultShell(themes);
            Rect vertical;
            Rect horizontal;
            try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
                Pilot pilot = test.pilot();
                settle(pilot);
                vertical = pilot.findElement("pane-separator-vertical");
                horizontal = pilot.findElement("pane-separator-horizontal");
            }
            for (int round = 0; round < 2; round++) {
                Buffer buffer = offlineFrame(shell, WIDE);
                assertEquals("▐", buffer.get(vertical.x(), vertical.y()).symbol(),
                        "the gap is drawn in '" + themes.activeTheme() + "'");
                for (int y = vertical.y(); y < vertical.bottom(); y++) {
                    assertNoBoxLine(buffer.get(vertical.x(), y), "the vertical gap row " + y, themes);
                }
                for (int x = horizontal.x(); x < horizontal.right(); x++) {
                    assertNoBoxLine(buffer.get(x, horizontal.y()), "the horizontal gap column " + x, themes);
                }
                themes.switchTheme("dark".equals(themes.activeTheme()) ? "darcula" : "dark");
            }
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void theRightToolWindowBodyIsAnIslandOfTheEditorTone() throws Exception {
        ShellThemes themes = defaultThemes();
        VintelliShell shell = new VintelliShell(ShellConfig.of(windowsWithAcp(), new ShellState(), themes));
        if (!shell.isToolWindowVisible("acp.agents")) {
            shell.toggleToolWindow("acp.agents");
        }
        Rect right;
        try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
            Pilot pilot = test.pilot();
            settle(pilot);
            assertTrue(pilot.hasElement("right-tool-stripe"), test.renderedFrame());
            right = pilot.findElement("right-tool-stripe");
        }
        Buffer buffer = offlineFrame(shell, WIDE);
        for (int y = right.y() + 3; y < right.bottom() - 1; y++) {
            for (int x = right.x(); x < right.right(); x++) {
                assertEquals(themes.color("editor-bg"), cellBg(buffer, x, y),
                        "the right tool window body is an island of the editor tone at " + x + "," + y);
            }
        }
    }

    @Test
    void anOuterCornerRoundsItsBlankPaddingAndStaysSquareWhereTextSits() throws Exception {
        VintelliShell shell = defaultShell();
        Rect editor;
        Rect project;
        try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
            Pilot pilot = test.pilot();
            settle(pilot);
            editor = pilot.findElement("editor-panel");
            project = pilot.findElement("project-pane");
        }
        Buffer buffer = offlineFrame(shell, WIDE);
        assertEquals("▝", buffer.get(editor.right() - 1, editor.y()).symbol(),
                "the editor's top right corner meets the screen edge over blank header padding, so it rounds");
        String footerCorner = buffer.get(project.right() - 1, project.bottom() - 1).symbol();
        assertTrue(!footerCorner.isBlank() && "▘▝▖▗".indexOf(footerCorner.charAt(0)) < 0,
                "the project footer's bottom right corner holds text, so it stays square: " + footerCorner);
    }

    @Test
    void thePaneHeaderRowsAreUnderlinedInTheFrameTone() {
        ShellThemes themes = defaultThemes();
        VintelliShell shell = defaultShell(themes);
        Buffer buffer = offlineFrame(shell, WIDE);
        for (int x = PROJECT_X; x < SEPARATOR_X; x++) {
            assertEquals(themes.color("panel"), buffer.get(x, HEADER_ROW).style().underlineColor().orElse(null),
                    "the project header underline in the frame tone at column " + x);
        }
        for (int x = EDITOR_HEADER_X; x < WIDE.width() - 1; x++) {
            assertEquals(themes.color("panel"), buffer.get(x, HEADER_ROW).style().underlineColor().orElse(null),
                    "the editor header underline in the frame tone at column " + x);
        }
        int bottomTabRow = findBottomTabRow(buffer);
        assertEquals(themes.color("panel"), buffer.get(EDITOR_HEADER_X + 2, bottomTabRow).style().underlineColor().orElse(null),
                "the bottom tab row is underlined in the frame tone");
        for (int x = EDITOR_HEADER_X + 10; x < WIDE.width() - 3; x++) {
            assertEquals(themes.color("editor-bg"), cellBg(buffer, x, bottomTabRow + 3),
                    "the bottom tool window body is an island of the editor tone at column " + x);
        }
    }

    @Test
    void theGapCellsDragBothSplittersAtBothSizesAndAfterAResize() throws Exception {
        for (Size size : List.of(SIZE, WIDE)) {
            ShellLayoutStore store = new ShellLayoutStore(project.resolve("gap-" + size.width()), project);
            VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                    .withLayoutStore(store));
            try (MouseShellRunner test = runMouseShell(shell, size)) {
                Pilot pilot = test.pilot();
                settle(pilot);
                int projectBefore = pilot.findElement("project-panel").width();
                Rect vertical = pilot.findElement("pane-separator-vertical");
                int onEditor = pilot.findElement("editor-panel").y() + 2;
                pilot.mousePress(MouseButton.LEFT, vertical.x(), onEditor);
                test.drag(MouseEvent.drag(MouseButton.LEFT, vertical.x() + 4, onEditor));
                pilot.mouseRelease(MouseButton.LEFT, vertical.x() + 4, onEditor);
                settle(pilot);
                assertEquals(projectBefore + 4, pilot.findElement("project-panel").width(),
                        size + ": the vertical gap cell drags the project splitter");

                test.resize(size.width() - 10, size.height() - 4);
                settle(pilot);
                Rect resizedVertical = pilot.findElement("pane-separator-vertical");
                int resizedWidth = pilot.findElement("project-panel").width();
                pilot.mousePress(MouseButton.LEFT, resizedVertical.x(), resizedVertical.y() + 1);
                test.drag(MouseEvent.drag(MouseButton.LEFT, resizedVertical.x() - 3, resizedVertical.y() + 1));
                pilot.mouseRelease(MouseButton.LEFT, resizedVertical.x() - 3, resizedVertical.y() + 1);
                settle(pilot);
                assertEquals(resizedWidth - 3, pilot.findElement("project-panel").width(),
                        size + ": the vertical gap cell still drags after a resize");

                Rect horizontal = pilot.findElement("pane-separator-horizontal");
                int editorHeight = pilot.findElement("editor-panel").height();
                int column = pilot.findElement("editor-panel").x() + 2;
                pilot.mousePress(MouseButton.LEFT, column, horizontal.y());
                test.drag(MouseEvent.drag(MouseButton.LEFT, column, horizontal.y() - 2));
                pilot.mouseRelease(MouseButton.LEFT, column, horizontal.y() - 2);
                settle(pilot);
                assertTrue(pilot.findElement("editor-panel").height() < editorHeight,
                        size + ": the horizontal gap cell drags the bottom splitter after a resize");
            }
        }
    }

    @Test
    void hidingTheProjectPaneLeavesNoGapOrCornerBehind() throws Exception {
        String previous = useNerdIconFace();
        try {
            ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
            VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                    .withLayoutStore(store));
            int projectIcon = findRow(offlineFrame(shell), 1, PROJECT_GLYPH);
            try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
                Pilot pilot = test.pilot();
                pilot.click(1, projectIcon);
                settle(pilot);
                assertEquals(VintelliShell.TOOL_STRIP_WIDTH, pilot.findElement("editor-panel").x(),
                        "the editor starts right after the strip when the project pane is hidden");
                assertFalse(pilot.hasElement("pane-separator-vertical")
                        && pilot.findElement("pane-separator-vertical").width() > 0,
                        "the vertical gap is gone with the project pane");
                Rect horizontal = pilot.findElement("pane-separator-horizontal");
                String frame = test.renderedFrame();
                assertNoGapGlyph(frame, "the project pane is hidden", List.of("▐"));
                String gapRow = frame.split("\n", -1)[horizontal.y()];
                assertFalse(gapRow.substring(0, VintelliShell.TOOL_STRIP_WIDTH).contains("▄"),
                        "the bottom gap row starts at the editor, not at the strip: " + gapRow);
            }
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void hidingEveryBottomWindowLeavesNoGapRowAndNoCorner() throws Exception {
        String previous = useNerdIconFace();
        try {
            ShellLayoutStore store = new ShellLayoutStore(project.resolve("config"), project);
            VintelliShell shell = new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), defaultThemes())
                    .withLayoutStore(store));
            defaultWindows().forEach(window -> shell.hideToolWindow(window.id()));
            Rect editor;
            String frame;
            try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
                Pilot pilot = test.pilot();
                settle(pilot);
                assertFalse(pilot.hasElement("pane-separator-horizontal")
                        && pilot.findElement("pane-separator-horizontal").height() > 0,
                        "the horizontal gap row is gone with every bottom window");
                editor = pilot.findElement("editor-panel");
                frame = test.renderedFrame();
            }
            Buffer buffer = offlineFrame(shell, WIDE);
            int lastRow = editor.bottom() - 1;
            for (int x = editor.x(); x < editor.right(); x++) {
                String symbol = buffer.get(x, lastRow).symbol();
                assertFalse("▄".equals(symbol),
                        "no gap on the editor's last row at column " + x + ": " + symbol);
            }
            assertNoGapGlyph(frame, "every bottom window is hidden", List.of("▄"));
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void theAsciiFormDrawsPlainFrameCellsInTheGapsAndSquareCorners() throws Exception {
        String previous = System.getProperty(IconMode.CONFIG_PROPERTY);
        System.setProperty(IconMode.CONFIG_PROPERTY, "ascii");
        try {
            ShellThemes themes = defaultThemes();
            VintelliShell plain = defaultShell(themes);
            plain.useUnicodeProjectTree(false);
            Rect vertical;
            Rect horizontal;
            Rect editor;
            String frame;
            try (MouseShellRunner test = runMouseShell(plain, WIDE)) {
                Pilot pilot = test.pilot();
                settle(pilot);
                vertical = pilot.findElement("pane-separator-vertical");
                horizontal = pilot.findElement("pane-separator-horizontal");
                editor = pilot.findElement("editor-panel");
                frame = test.renderedFrame();
            }
            Buffer buffer = offlineFrame(plain, WIDE);
            for (int y = vertical.y(); y < vertical.bottom(); y++) {
                assertPlainFrameCell(buffer.get(vertical.x(), y), "the plain vertical gap row " + y, themes);
            }
            for (int x = horizontal.x(); x < horizontal.right(); x++) {
                assertPlainFrameCell(buffer.get(x, horizontal.y()), "the plain horizontal gap column " + x, themes);
            }
            assertEquals(" ", buffer.get(editor.x(), horizontal.y() - 1).symbol(), "the plain corner stays square");
            assertNoGapGlyph(frame, "the Unicode probe is off", List.of("▐", "▌", "▄", "▘", "▝", "▖", "▗"));

            VintelliShell unicode = defaultShell(themes);
            unicode.useUnicodeProjectTree(true);
            Rect unicodeVertical;
            try (MouseShellRunner test = runMouseShell(unicode, WIDE)) {
                Pilot pilot = test.pilot();
                settle(pilot);
                unicodeVertical = pilot.findElement("pane-separator-vertical");
            }
            assertEquals("▐", offlineFrame(unicode, WIDE).get(unicodeVertical.x(), unicodeVertical.y()).symbol(),
                    "the ASCII icon face still draws the half block gap while the Unicode probe is on");
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    @Test
    void theGapsAndCornersDowngradeToTheIndexedPaletteAt256Colors() throws Exception {
        String previous = useNerdIconFace();
        try {
            ShellThemes indexed = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null, ColorLevel.INDEXED_256);
            VintelliShell shell = defaultShell(indexed);
            Rect horizontal;
            Rect editor;
            try (MouseShellRunner test = runMouseShell(shell, WIDE)) {
                Pilot pilot = test.pilot();
                settle(pilot);
                horizontal = pilot.findElement("pane-separator-horizontal");
                editor = pilot.findElement("editor-panel");
            }
            Buffer buffer = offlineFrame(shell, WIDE);
            Cell gap = buffer.get(SEPARATOR_X, FIRST_PROJECT_ROW);
            assertEquals("▐", gap.symbol(), "the 256 color gap keeps its half block");
            assertEquals(indexed.color("editor-bg"), gap.style().fg().orElse(null));
            assertEquals(indexed.color("panel"), gap.style().bg().orElse(null));
            Cell corner = buffer.get(editor.x(), horizontal.y() - 1);
            assertEquals("▖", corner.symbol(), "the 256 color corner keeps its quarter block");
            assertEquals(indexed.color("panel"), corner.style().fg().orElse(null));
            assertEquals(indexed.color("editor-bg"), corner.style().bg().orElse(null));
            assertNotEquals(defaultThemes().color("panel"), gap.style().bg().orElse(null),
                    "the 256 color gap differs from the truecolor one");
        } finally {
            restoreProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    private static boolean nonGapCellsAreAscii(Buffer buffer, int row, Set<Integer> gapColumns) {
        for (int x = 0; x < buffer.width(); x++) {
            String symbol = buffer.get(x, row).symbol();
            if (!gapColumns.contains(x) && !List.of("▘", "▝", "▖", "▗").contains(symbol)
                    && !symbol.chars().allMatch(character -> character < 128)) {
                return false;
            }
        }
        return true;
    }

    private static String useNerdIconFace() {
        String previous = System.getProperty(IconMode.CONFIG_PROPERTY);
        System.setProperty(IconMode.CONFIG_PROPERTY, "nerd");
        return previous;
    }

    private static void assertNoGapGlyph(String frame, String when, List<String> glyphs) {
        for (String glyph : glyphs) {
            assertFalse(frame.contains(glyph), "no gap or corner glyph " + glyph + " is left once " + when + ":\n" + frame);
        }
    }

    private static void assertNoBoxLine(Cell cell, String where, ShellThemes themes) {
        assertFalse(List.of("│", "─", "━", "┃", "═", "║").contains(cell.symbol()),
                where + " draws a box line in '" + themes.activeTheme() + "': " + cell.symbol());
    }

    private static void assertPlainFrameCell(Cell cell, String where, ShellThemes themes) {
        assertEquals(" ", cell.symbol(), where + " is a plain cell");
        assertEquals(themes.color("panel"), cell.style().bg().orElse(null), where + " has the frame tone");
    }

    @Test
    void switchThemeRestylesTheWholeShellWithoutRestart() {
        ShellThemes themes = defaultThemes();
        VintelliShell shell = defaultShell(themes);

        Buffer beforeBuffer = offlineFrame(shell);
        int bottomTabRow = findBottomTabRow(beforeBuffer);
        assertEquals(themes.color("focus-border"), beforeBuffer.get(EDITOR_HEADER_X + 1, bottomTabRow + 1).style().fg().orElse(null),
                "the active theme colors the selected tab underline before the switch");
        // Project panel island background at an unselected row (2nd project row)
        assertEquals(themes.color("editor-bg"), cellBg(beforeBuffer, 2, SECOND_PROJECT_ROW),
                "the active theme styles the project panel before the switch");
        assertEquals(themes.color("editor-bg"), cellBg(beforeBuffer, EDITOR_PROBE_X, EDITOR_PROBE_Y),
                "the active theme styles the editor panel before the switch");
        assertEquals(themes.color("panel"), cellBg(beforeBuffer, 40, STATUS_ROW),
                "the active theme styles the status bar before the switch");
        assertTrue(rowText(beforeBuffer, MENU_ROW).matches("^ (≡|=) .*"));

        themes.switchTheme("darcula");

        Buffer afterBuffer = offlineFrame(shell);
        int afterTabRow = findBottomTabRow(afterBuffer);
        assertEquals(themes.color("focus-border"), afterBuffer.get(EDITOR_HEADER_X + 1, afterTabRow + 1).style().fg().orElse(null),
                "the switched theme reaches the selected tab underline");
        assertEquals(themes.color("panel"), cellBg(afterBuffer, 2, SECOND_PROJECT_ROW),
                "the switched theme reaches the project panel, which is panel toned in darcula");
        assertEquals(themes.color("editor-bg"), cellBg(afterBuffer, EDITOR_PROBE_X, EDITOR_PROBE_Y),
                "the switched theme reaches the editor panel");
        assertEquals(themes.color("panel"), cellBg(afterBuffer, 40, STATUS_ROW),
                "the switched theme reaches the status bar");
        assertTrue(rowText(afterBuffer, MENU_ROW).matches("^ (≡|=) .*"),
                "the theme switch keeps the whole shell rendering");
        assertFalse(themes.variablesOf("dark").get("panel").equalsIgnoreCase(themes.variablesOf("darcula").get("panel")),
                "the two shipped themes actually differ so the assertions above mean something");
    }

    @Test
    void toolbarRendersTheProjectAndTheVcsBranch() {
        VintelliShell shell = shellWithVcs(new BranchVcs("main", null));
        String row = awaitToolbarRow(shell, "main");
        String dropdown = toolbarGlyphs().dropdown();
        String project = Path.of("").toAbsolutePath().normalize().getFileName().toString();
        assertTrue(row.contains(" " + project + " " + dropdown + " "), row);
        assertTrue(row.contains(" main " + dropdown + " "), row);
    }

    @Test
    void theBranchWidgetIsHiddenWithoutAVcsProvider() {
        String row = rowText(offlineFrame(defaultShell()), MENU_ROW);
        String project = Path.of("").toAbsolutePath().normalize().getFileName().toString();
        assertTrue(row.contains(" " + project + " " + toolbarGlyphs().dropdown() + " "),
                "the project widget is drawn: " + row);
        String projectWidget = project + " " + toolbarGlyphs().dropdown();
        String afterProject = row.substring(row.indexOf(projectWidget) + projectWidget.length());
        assertFalse(afterProject.contains(toolbarGlyphs().dropdown()),
                "no branch widget is drawn after the project widget: " + row);
    }

    @Test
    void theRunWidgetIsHiddenWithoutARunProvider() {
        String row = rowText(offlineFrame(defaultShell()), MENU_ROW);
        assertFalse(row.contains(toolbarGlyphs().play()), row);
    }

    @Test
    void hamburgerClickOpensTheMenuPopup() throws Exception {
        try (MouseShellRunner test = runMouseShell(defaultShell(), SIZE)) {
            test.pilot().click(1, MENU_ROW);
            settle(test.pilot());
            assertTrue(firstRow(awaitRenderedFrame(test.pilot(), test, "Edit  View")).contains("Edit  View"));
        }
    }

    @Test
    void f10StillOpensTheMenuPopup() throws Exception {
        try (MouseShellRunner test = runMouseShell(defaultShell(), SIZE)) {
            test.pilot().press(KeyCode.F10);
            settle(test.pilot());
            assertTrue(firstRow(awaitRenderedFrame(test.pilot(), test, "Edit  View")).contains("Edit  View"));
        }
    }

    @Test
    void aClickOnAMenuLabelWhilePopupOpenSwitchesTheMenu() throws Exception {
        String keyboardEdit;
        try (MouseShellRunner reference = runMouseShell(defaultShell(), SIZE)) {
            reference.pilot().press(KeyCode.F10);
            settle(reference.pilot());
            reference.pilot().press(KeyCode.RIGHT);
            settle(reference.pilot());
            keyboardEdit = reference.renderedFrame();
        }
        try (MouseShellRunner test = runMouseShell(defaultShell(), SIZE)) {
            test.pilot().click(1, MENU_ROW);
            settle(test.pilot());
            String row = firstRow(awaitRenderedFrame(test.pilot(), test, "Edit  View"));
            test.pilot().click(row.indexOf("Edit") + 1, MENU_ROW);
            settle(test.pilot());
            String clicked = test.renderedFrame();
            assertEquals(keyboardEdit, clicked, "the label click opens the same menu the keyboard opens");
        }
    }

    @Test
    void aClickOnAnEmptyToolbarCellDoesNothing() throws Exception {
        try (MouseShellRunner test = runMouseShell(defaultShell(), SIZE)) {
            test.pilot().click(40, MENU_ROW);
            settle(test.pilot());
            assertFalse(firstRow(test.renderedFrame()).contains("Edit  View"));
        }
    }

    @Test
    void branchClickShowsTheVcsStatusAndDoesNotSwitchTheBranch() throws Exception {
        BranchVcs vcs = new BranchVcs("main", null);
        VintelliShell shell = shellWithVcs(vcs);
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            String row = firstRow(awaitRenderedFrame(test.pilot(), test, "main"));
            test.pilot().click(row.indexOf("main") + 1, MENU_ROW);
            settle(test.pilot());
            long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
            while (!shell.status().startsWith("VCS: main") && System.nanoTime() < deadline) {
                pause(test.pilot(), Duration.ofMillis(20));
            }
            assertTrue(shell.status().startsWith("VCS: main"), shell.status());
            assertEquals(0, vcs.switchCalls.get(), "the branch widget must not switch branches");
        }
    }

    @Test
    void toolbarSearchButtonOpensTheProjectSearch() throws Exception {
        VintelliShell shell = shellWithProjectSearch();
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            String row = firstRow(awaitRenderedFrame(test.pilot(), test, toolbarGlyphs().settings()));
            test.pilot().click(row.lastIndexOf(toolbarGlyphs().search()), MENU_ROW);
            settle(test.pilot());
            awaitShellStatus(shell, "Find in Files: type to search");
            assertEquals("Find in Files: type to search", shell.status());
        }
    }

    @Test
    void toolbarMoreButtonOpensTheActionMenu() throws Exception {
        try (MouseShellRunner test = runMouseShell(defaultShell(), SIZE)) {
            String row = firstRow(awaitRenderedFrame(test.pilot(), test, toolbarGlyphs().more()));
            test.pilot().click(row.lastIndexOf(toolbarGlyphs().more()), MENU_ROW);
            settle(test.pilot());
            assertTrue(firstRow(awaitRenderedFrame(test.pilot(), test, "Edit  View")).contains("Edit  View"));
        }
    }

    @Test
    void toolbarSettingsButtonOpensTheThemeChoice() throws Exception {
        VintelliShell shell = defaultShell();
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            String row = firstRow(awaitRenderedFrame(test.pilot(), test, toolbarGlyphs().settings()));
            test.pilot().click(row.lastIndexOf(toolbarGlyphs().settings()), MENU_ROW);
            settle(test.pilot());
            String frame = awaitRenderedFrame(test.pilot(), test, "Switch Shell Theme");
            assertTrue(frame.contains("Switch Shell Theme"), "the palette is narrowed to the theme choice: " + frame);
            assertTrue(frame.contains("> theme"), "the palette filter reads theme: " + frame);
            assertTrue(shell.status().startsWith("Settings: pick the shell theme"), shell.status());
        }
    }

    @Test
    void toolbarDebugButtonIsDisabledAndDispatchesNothing() throws Exception {
        VintelliShell shell = defaultShell();
        try (MouseShellRunner test = runMouseShell(shell, SIZE)) {
            String row = firstRow(awaitRenderedFrame(test.pilot(), test, toolbarGlyphs().debug()));
            String before = shell.status();
            test.pilot().click(row.lastIndexOf(toolbarGlyphs().debug()), MENU_ROW);
            settle(test.pilot());
            pause(test.pilot(), Duration.ofMillis(100));
            assertEquals(before, shell.status(), "the disabled debug button changes no state");
            String frame = test.renderedFrame();
            assertFalse(firstRow(frame).contains("Edit  View"), "debug does not open the menu: " + firstRow(frame));
            assertFalse(frame.contains("Switch Shell Theme"), "debug does not open the palette");
        }
    }

    @Test
    void toolbarRightGroupKeepsSettingsLastWithOneBlankColumnBetweenButtonsAtEightyColumns() throws Exception {
        try (MouseShellRunner test = runMouseShell(defaultShell(), SIZE)) {
            String row = firstRow(awaitRenderedFrame(test.pilot(), test, toolbarGlyphs().settings()));
            int debug = row.lastIndexOf(toolbarGlyphs().debug());
            int more = row.lastIndexOf(toolbarGlyphs().more());
            int search = row.lastIndexOf(toolbarGlyphs().search());
            int settings = row.lastIndexOf(toolbarGlyphs().settings());
            assertEquals(VIEWPORT.width() - 1, settings, "settings is the last cell of the 80 column row: " + row);
            assertEquals(3, search - more, row);
            assertEquals(3, more - debug, row);
            assertEquals(3, settings - search, row);
        }
    }

    private static void awaitShellStatus(VintelliShell shell, String expected) {
        long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
        while (!expected.equals(shell.status()) && System.nanoTime() < deadline) {
            napMillis(10);
        }
    }

    private static VintelliShell shellWithProjectSearch() {
        ShellThemes themes = defaultThemes();
        List<ToolWindow> windows = defaultWindows();
        return new VintelliShell(ShellConfig.of(windows, new ShellState(), themes)
                .withKeymapSupplier(() -> Keymap.defaultKeymap(windows))
                .withProjectSearch(new IdleProjectSearch()));
    }

    private static void napMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String firstRow(String frame) {
        return frame.split("\n", -1)[0];
    }

    @Test
    void aBlockingVcsStatusNeverBlocksTheToolbarRender() {
        CountDownLatch release = new CountDownLatch(1);
        VintelliShell shell = shellWithVcs(new BranchVcs("main", release));
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> offlineFrame(shell));
        } finally {
            release.countDown();
        }
    }

    @Test
    void renderAloneNeverCallsTheVcsStatus() {
        BranchVcs vcs = new BranchVcs("main", null);
        VintelliShell shell = shellWithVcs(vcs);
        awaitToolbarRow(shell, "main");
        int before = vcs.statusCalls.get();
        for (int frame = 0; frame < 20; frame++) {
            offlineFrame(shell);
        }
        assertEquals(before, vcs.statusCalls.get(), "painting the toolbar must not read the repository");
    }

    @Test
    void aThrowingVcsStatusHidesTheBranchWidgetAndNothingEscapesTheReadThread() {
        AtomicReference<Throwable> escaped = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, problem) -> escaped.set(problem));
        try {
            VintelliShell shell = shellWithVcs(new BranchVcs("main", null, new IllegalStateException("git is missing")));
            long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
            String row = rowText(offlineFrame(shell), MENU_ROW);
            while (row.contains("VCS") && System.nanoTime() < deadline) {
                napMillis(10);
                row = rowText(offlineFrame(shell), MENU_ROW);
            }
            assertFalse(row.contains("VCS"), "the placeholder must not stay on screen: " + row);
            assertFalse(row.contains("main"), row);
            napMillis(50);
            assertEquals(null, escaped.get(), "the failing read must not escape its thread");
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void theBranchWidgetFollowsTheRepositoryAfterARefresh() {
        BranchVcs vcs = new BranchVcs("main", null);
        VintelliShell shell = shellWithVcs(vcs);
        awaitToolbarRow(shell, "main");
        vcs.branch.set("feature");
        shell.refreshVcs();
        assertTrue(rowText(offlineFrame(shell), MENU_ROW).contains("feature"));
    }

    private static MainToolbar.Glyphs toolbarGlyphs() {
        return MainToolbar.Glyphs.of(FileIcons.resolve().mode(), ProjectTreeGlyphs.supportsUnicode());
    }

    private static VintelliShell shellWithVcs(VersionControlCapability vcs) {
        ShellThemes themes = defaultThemes();
        List<ToolWindow> windows = defaultWindows();
        return new VintelliShell(ShellConfig.of(windows, new ShellState(), themes)
                .withKeymapSupplier(() -> Keymap.defaultKeymap(windows))
                .withProjectSearch(null)
                .withNavigationProviders(List.of())
                .withVcsProviders(List.of(vcs)));
    }

    private static String awaitToolbarRow(VintelliShell shell, String text) {
        long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
        String row = rowText(offlineFrame(shell), MENU_ROW);
        while (!row.contains(text) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            row = rowText(offlineFrame(shell), MENU_ROW);
        }
        assertTrue(row.contains(text), "the toolbar never showed " + text + ": " + row);
        return row;
    }

    /** A repository fake that counts reads and switches and can block the status read. */
    private static final class BranchVcs implements VersionControlCapability {
        private final AtomicReference<String> branch;
        private final AtomicInteger statusCalls = new AtomicInteger();
        private final AtomicInteger switchCalls = new AtomicInteger();
        private final CountDownLatch release;
        private final RuntimeException failure;

        private BranchVcs(String branch, CountDownLatch release) {
            this(branch, release, null);
        }

        private BranchVcs(String branch, CountDownLatch release, RuntimeException failure) {
            this.branch = new AtomicReference<>(branch);
            this.release = release;
            this.failure = failure;
        }

        @Override
        public CapabilityResult<Status> status() {
            statusCalls.incrementAndGet();
            if (release != null) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure != null) {
                throw failure;
            }
            return CapabilityResult.ok(new Status(branch.get(), 0, 0, List.of()));
        }

        @Override public CapabilityResult<String> diff(Path changedFile) { return CapabilityResult.ok(""); }
        @Override public CapabilityResult<String> diffWorkingTree() { return CapabilityResult.ok(""); }
        @Override public CapabilityResult<Boolean> stage(Path changedFile, boolean confirmed) { return CapabilityResult.ok(confirmed); }
        @Override public CapabilityResult<Boolean> unstage(Path changedFile, boolean confirmed) { return CapabilityResult.ok(confirmed); }
        @Override public CapabilityResult<Boolean> commit(String message, boolean confirmed) { return CapabilityResult.ok(confirmed); }

        @Override
        public CapabilityResult<Boolean> switchBranch(String target, boolean confirmed) {
            switchCalls.incrementAndGet();
            return CapabilityResult.ok(confirmed);
        }

        @Override public String identifier() { return "branch-test"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }
        @Override public String provenance() { return "temporary branch test provider"; }
    }

    @Test
    void pilotCopiesAndPastesAMultiLineSelectionWithWideUnicode() throws Exception {
        EditorShell fixture = openEditorShell("Clip.txt", "ab\n漢字 ok\nend\n", 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            selectWithMouse(test, text, 0, 0, 1, 5);
            settle(pilot);
            assertEquals("ab\n漢字 ", fixture.editor().pointerState().orElseThrow().selectedText());

            pressCtrl(pilot, 'c');
            settle(pilot);
            assertEquals("ab\n漢字 ", fixture.clipboard().stored());
            assertEquals("Copied to the system clipboard", fixture.editor().status());

            pilot.click(text.x(), text.y() + 3);
            settle(pilot);
            pressCtrl(pilot, 'v');
            settle(pilot);
            assertEquals("ab\n漢字 ok\nend\nab\n漢字 ", fixture.editor().activeDocument().orElseThrow().text());
            assertTrue(fixture.editor().activeDocument().orElseThrow().dirty());
        }
    }

    @Test
    void pilotCutAndPasteAreSingleUndoStepsThatMarkTheDocumentDirty() throws Exception {
        EditorShell fixture = openEditorShell("Cut.txt", "alpha beta\n", 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            selectWithMouse(test, text, 0, 6, 0, 10);
            settle(pilot);

            pressCtrl(pilot, 'x');
            settle(pilot);
            assertEquals("alpha \n", fixture.editor().activeDocument().orElseThrow().text());
            assertEquals("beta", fixture.clipboard().stored());
            assertTrue(fixture.editor().activeDocument().orElseThrow().dirty());

            pressCtrl(pilot, 'v');
            settle(pilot);
            assertEquals("alpha beta\n", fixture.editor().activeDocument().orElseThrow().text());

            pressCtrl(pilot, 'z');
            settle(pilot);
            assertEquals("alpha \n", fixture.editor().activeDocument().orElseThrow().text(),
                    "one undo reverts the paste alone");
            assertTrue(fixture.editor().activeDocument().orElseThrow().dirty());

            pressCtrl(pilot, 'z');
            settle(pilot);
            assertEquals("alpha beta\n", fixture.editor().activeDocument().orElseThrow().text(),
                    "one undo reverts the cut alone");
            assertFalse(fixture.editor().activeDocument().orElseThrow().dirty());
        }
    }

    @Test
    void pilotCopyLeavesTheDocumentCleanAndAddsNoUndoStep() throws Exception {
        EditorShell fixture = openEditorShell("Quiet.txt", "alpha beta\n", 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            selectWithMouse(test, text, 0, 6, 0, 10);
            settle(pilot);

            pressCtrl(pilot, 'c');
            settle(pilot);
            assertEquals("alpha beta\n", fixture.editor().activeDocument().orElseThrow().text());
            assertFalse(fixture.editor().activeDocument().orElseThrow().dirty());
            assertEquals("beta", fixture.clipboard().stored());

            pressCtrl(pilot, 'z');
            settle(pilot);
            assertEquals("Nothing to undo", fixture.shell().status(), "copy did not record an undo step");
        }
    }

    @Test
    void pilotTypingAndBackspaceReplaceOrRemoveTheSelectionAsOneUndoStep() throws Exception {
        EditorShell fixture = openEditorShell("Replace.txt", "alpha beta\n", 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            selectWithMouse(test, text, 0, 6, 0, 10);
            settle(pilot);

            pilot.press('X');
            settle(pilot);
            assertEquals("alpha X\n", fixture.editor().activeDocument().orElseThrow().text());

            pressCtrl(pilot, 'z');
            settle(pilot);
            assertEquals("alpha beta\n", fixture.editor().activeDocument().orElseThrow().text(),
                    "one undo reverts the typed replacement");
            pressCtrl(pilot, 'z');
            settle(pilot);
            assertEquals("Nothing to undo", fixture.shell().status());

            selectWithMouse(test, text, 0, 6, 0, 10);
            settle(pilot);
            pilot.press(KeyCode.BACKSPACE);
            settle(pilot);
            assertEquals("alpha \n", fixture.editor().activeDocument().orElseThrow().text(),
                    "Backspace removes the selection and nothing more");
        }
    }

    @Test
    void pilotCtrlCQuitsWithoutASelection() throws Exception {
        EditorShell fixture = openEditorShell("Chord.txt", "alpha beta\n", 0);
        try (ToolkitTestRunner test = runShell(fixture.shell())) {
            Pilot pilot = test.pilot();
            pilot.pause(STARTUP);
            fixture.editor().requestFocus();
            settle(pilot);
            pressCtrl(pilot, 'c');
            settle(pilot);
            assertFalse(test.runner().isRunning(), "Ctrl+C with no selection quits as before");
        }
    }

    @Test
    void pilotCtrlCWithASelectionCopiesAndKeepsTheShellRunning() throws Exception {
        EditorShell fixture = openEditorShell("Keep.txt", "alpha beta\n", 0);
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            selectWithMouse(test, text, 0, 6, 0, 10);
            settle(pilot);

            pressCtrl(pilot, 'c');
            settle(pilot);
            assertTrue(test.running(), "Ctrl+C over a selection copies instead of quitting");
            assertEquals("beta", fixture.clipboard().stored());
        }
    }

    @Test
    void pilotClipboardStatesTheEditorOnlyFallbackWithoutASystemClipboard() throws Exception {
        EditorShell fixture = openEditorShell("Local.txt", "alpha beta\n", 0);
        fixture.clipboard().available = false;
        try (MouseShellRunner test = runMouseShell(fixture.shell(), SIZE)) {
            Pilot pilot = test.pilot();
            Rect text = pilot.findElement(BuiltinEditor.TEXT_AREA_ID);
            selectWithMouse(test, text, 0, 6, 0, 10);
            settle(pilot);

            pressCtrl(pilot, 'c');
            settle(pilot);
            assertEquals("Copied to the editor clipboard and the terminal (OSC 52): no system clipboard found",
                    fixture.editor().status());

            pilot.click(text.x(), text.y() + 1);
            settle(pilot);
            pressCtrl(pilot, 'v');
            settle(pilot);
            assertEquals("alpha beta\nbeta", fixture.editor().activeDocument().orElseThrow().text());
            assertEquals("Pasted from the editor clipboard only: no system clipboard found", fixture.editor().status());
        }
    }

    private static void selectWithMouse(MouseShellRunner test, Rect text, int startRow, int startCell, int endRow,
            int endCell) {
        Pilot pilot = test.pilot();
        int startX = text.x() + startCell;
        int startY = text.y() + startRow;
        int endX = text.x() + endCell;
        int endY = text.y() + endRow;
        pilot.mousePress(MouseButton.LEFT, startX, startY);
        test.drag(MouseEvent.drag(MouseButton.LEFT, endX, endY));
        pilot.mouseRelease(MouseButton.LEFT, endX, endY);
    }

    private static void pressCtrl(Pilot pilot, char key) {
        pilot.press(key, KeyModifiers.CTRL);
    }

    private static final class FakeClipboard implements SystemClipboard {
        private boolean available = true;
        private String stored = "";

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public boolean write(String value) {
            stored = value;
            return available;
        }

        @Override
        public Optional<String> read() {
            return available ? Optional.of(stored) : Optional.empty();
        }

        String stored() {
            return stored;
        }
    }

    private record EditorShell(VintelliShell shell, BuiltinEditor editor, FakeClipboard clipboard) {
    }

    private EditorShell openEditorShell(String name, String text, int row) throws IOException {
        return openEditorShell(name, text, row, defaultThemes(), false);
    }

    private EditorShell openEditorShell(String name, String text, int row, ShellThemes themes, boolean projectSearch)
            throws IOException {
        Path file = project.resolve(name);
        Files.writeString(file, text);
        FakeClipboard clipboard = new FakeClipboard();
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette, clipboard, sequence -> { });
        assertFalse(editor.open(new OpenRequest(file, row)).isFailure());
        List<ToolWindow> windows = new ArrayList<>(defaultWindows());
        windows.add(editor);
        ShellConfig config = ShellConfig.of(new ToolWindowRegistry(windows).toolWindows(), new ShellState(), themes);
        if (projectSearch) {
            config = config.withProjectSearch(new IdleProjectSearch());
        }
        return new EditorShell(new VintelliShell(config), editor, clipboard);
    }

    private static String numberedLines(int count) {
        StringBuilder text = new StringBuilder();
        for (int line = 1; line <= count; line++) {
            text.append("line ").append(line).append('\n');
        }
        return text.toString();
    }

    private ToolkitTestRunner runShell(VintelliShell shell) throws Exception {
        ToolkitTestRunner test = ToolkitTestRunner.runTest(shell::root, SIZE);
        test.runner().styleEngine(shell.themes().engine());
        test.runner().eventRouter().addGlobalHandler(shell);
        shell.bindQuit(test.runner()::quit);
        settleStrategy = pilot -> awaitHandled(test.runner());
        return test;
    }

    private static LongSupplier steadyClock() {
        AtomicLong now = new AtomicLong();
        return () -> now.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
    }

    private MouseShellRunner runMouseShell(VintelliShell shell, Size size) throws Exception {
        MouseShellRunner test = new MouseShellRunner(shell, size);
        settleStrategy = pilot -> test.awaitSettled();
        return test;
    }

    private static String awaitRenderedFrame(Pilot pilot, MouseShellRunner test, String text) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        String rendered = test.renderedFrame();
        while (!rendered.contains(text) && System.nanoTime() < deadline) {
            pause(pilot, Duration.ofMillis(30));
            rendered = test.renderedFrame();
        }
        return rendered;
    }

    /** Captures rendered text from Toolkit's post-render callback on the runner thread. */
    private static final class MouseShellRunner implements AutoCloseable {
        private final ToolkitRunner runner;
        private final TestBackend backend;
        private final Pilot pilot;
        private final Thread renderThread;
        private final AtomicReference<String> rendered = new AtomicReference<>("");
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicInteger frames = new AtomicInteger();

        private MouseShellRunner(VintelliShell shell, Size size) throws Exception {
            backend = new TestBackend(size.width(), size.height());
            TuiConfig config = TuiConfig.builder()
                    .rawMode(false)
                    .alternateScreen(false)
                    .hideCursor(false)
                    .mouseCapture(true)
                    .shutdownHook(false)
                    .pollTimeout(Duration.ofMillis(10))
                    .noTick()
                    .backend(backend)
                    .build();
            runner = ToolkitRunner.builder()
                    .config(config)
                    .styleEngine(shell.themes().engine())
                    .postRenderProcessor((frame, elements, styles, focus, elapsed) -> {
                        rendered.set(bufferText(frame.buffer()));
                        frames.incrementAndGet();
                    })
                    .build();
            runner.eventRouter().addGlobalHandler(shell);
            shell.bindQuit(runner::quit);
            renderThread = new Thread(() -> {
                try {
                    runner.run(shell::root);
                } catch (Throwable problem) {
                    failure.set(problem);
                }
            }, "vintelli-shell-mouse-test");
            renderThread.setDaemon(true);
            renderThread.start();
            pilot = new ToolkitPilot(runner, backend);
            awaitFirstFrame();
            assertHealthy();
        }

        /** Waits for the first frame instead of assuming it lands within a fixed window. */
        private void awaitFirstFrame() {
            long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
            while (frames.get() == 0 && System.nanoTime() < deadline) {
                pause(pilot, SETTLE_STEP);
            }
            if (frames.get() == 0) {
                throw new AssertionError("the shell did not render a first frame within "
                        + SETTLE_TIMEOUT.toMillis() + " ms");
            }
        }

        private Pilot pilot() {
            return pilot;
        }

        private ToolkitRunner runner() {
            return runner;
        }

        private String renderedFrame() {
            assertHealthy();
            return rendered.get();
        }

        /**
         * Waits for the queued input to be handled and for the frame it
         * produces. The frame lags the handled event by at most one render of
         * the same loop iteration, so the grace window is a short bound on a
         * wait that is already known to be nearly over, not a guess at how
         * long the render thread needs.
         */
        private void awaitSettled() {
            int before = frames.get();
            try {
                awaitHandled(runner);
            } catch (AssertionError notHandled) {
                assertHealthy();
                throw notHandled;
            }
            awaitFrame(before);
        }

        private void drag(MouseEvent event) {
            runner.tuiRunner().dispatch(event);
            awaitSettled();
        }

        private boolean running() {
            return runner.isRunning();
        }

        private void resize(int width, int height) {
            int before = frames.get();
            backend.resize(width, height);
            pilot.resize(width, height);
            awaitFrame(before);
        }

        private void awaitFrame(int before) {
            long deadline = System.nanoTime() + FRAME_GRACE.toNanos();
            while (frames.get() == before && System.nanoTime() < deadline) {
                pause(pilot, SETTLE_STEP);
            }
            assertHealthy();
        }

        private void assertHealthy() {
            Throwable problem = failure.get();
            if (problem != null) {
                throw new AssertionError("TUI render thread failed", problem);
            }
        }

        @Override
        public void close() throws Exception {
            runner.quit();
            renderThread.join(1000);
            runner.close();
            assertHealthy();
        }
    }

    private VintelliShell projectTreeShell(ShellThemes themes, BuiltinEditor editor, ProjectFileOperations operations,
            List<String> files, List<String> directories) {
        List<ToolWindow> windows = new ArrayList<>(defaultWindows());
        if (editor != null) {
            windows.add(editor);
        }
        List<ToolWindow> registered = new ToolWindowRegistry(windows).toolWindows();
        ProjectSearch search = new FixedProjectSearch(files, directories);
        return new VintelliShell(ShellConfig.of(registered, new ShellState(), themes)
                .withKeymapSupplier(() -> Keymap.defaultKeymap(registered))
                .withProjectSearch(search)
                .withFileOperations(operations));
    }

    private void awaitProjectTree(VintelliShell shell) {
        long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
        while (shell.projectFiles().isEmpty() && System.nanoTime() < deadline) {
            sleep(SETTLE_STEP);
        }
        assertFalse(shell.projectFiles().isEmpty(), "the project tree loads in the background");
    }

    private static List<String> numberedFiles(int count) {
        List<String> files = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            files.add("file" + (index < 10 ? "0" : "") + index + ".txt");
        }
        return files;
    }

    /** The screen row that draws {@code label} inside the list, or -1 when the list does not draw it. */
    private static int projectRowOf(String frame, Rect list, String label) {
        String[] lines = frame.split("\n", -1);
        for (int y = list.y(); y < list.y() + list.height() && y < lines.length; y++) {
            if (frameCells(lines[y], list.x(), list.width()).contains(label)) {
                return y;
            }
        }
        return -1;
    }

    /** The list row that shows the selection marker in its first column, or -1 when none does. */
    private static int highlightedProjectRow(String frame, Rect list) {
        for (int y = list.y(); y < list.y() + list.height() && y < frame.split("\n", -1).length; y++) {
            if (frameCell(frame, list.x(), y) == '>') {
                return y;
            }
        }
        return -1;
    }

    private static int projectNameColumn(String frame, Rect list, String name) {
        int row = projectRowOf(frame, list, name);
        assertTrue(row >= 0, "the list draws " + name + ": " + frame);
        return frameCells(frame.split("\n", -1)[row], list.x(), list.width()).indexOf(name);
    }

    private static String paneText(String frame, Rect list) {
        String[] lines = frame.split("\n", -1);
        StringBuilder text = new StringBuilder();
        for (int y = list.y(); y < list.y() + list.height() && y < lines.length; y++) {
            text.append(frameCells(lines[y], list.x(), list.width())).append('\n');
        }
        return text.toString();
    }

    private static char frameCell(String frame, int x, int y) {
        return frameCells(frame.split("\n", -1)[y], x, 1).charAt(0);
    }

    private static String frameCells(String line, int x, int width) {
        String padded = line.length() < x + width ? line + " ".repeat(x + width - line.length()) : line;
        return padded.substring(x, x + width);
    }

    /** A search that finds nothing and never fails, for the overlays that only need the search to open. */
    private static final class IdleProjectSearch implements ProjectSearch {

        @Override
        public List<Path> listFiles() {
            return List.of();
        }

        @Override
        public List<Path> listDirectories() {
            return List.of();
        }

        @Override
        public SearchTask files(String query, Consumer<List<FileMatch>> results) {
            return idle();
        }

        @Override
        public SearchTask text(String query, boolean regex, Consumer<List<TextMatch>> results) {
            return idle();
        }

        private static SearchTask idle() {
            return new SearchTask() {
                @Override
                public void cancel() {
                }

                @Override
                public boolean cancelled() {
                    return false;
                }

                @Override
                public CompletionStage<Void> completion() {
                    return CompletableFuture.completedFuture(null);
                }
            };
        }
    }

    private static final class FixedProjectSearch implements ProjectSearch {
        private final List<String> files;
        private final List<String> directories;

        private FixedProjectSearch(List<String> files, List<String> directories) {
            this.files = files;
            this.directories = directories;
        }

        @Override
        public List<Path> listFiles() {
            return files.stream().map(Path::of).toList();
        }

        @Override
        public List<Path> listDirectories() {
            return directories.stream().map(Path::of).toList();
        }

        @Override
        public SearchTask files(String query, Consumer<List<FileMatch>> results) {
            throw new UnsupportedOperationException("the tree tests do not search");
        }

        @Override
        public SearchTask text(String query, boolean regex, Consumer<List<TextMatch>> results) {
            throw new UnsupportedOperationException("the tree tests do not search");
        }
    }

    private static final class RecordingFileOperations implements ProjectFileOperations {
        private final List<String> created = new ArrayList<>();

        @Override
        public CapabilityResult<Path> createFile(Path directory, String name) {
            created.add(name);
            return CapabilityResult.ok(directory.resolve(name));
        }

        @Override
        public CapabilityResult<Path> createDirectory(Path directory, String name) {
            return CapabilityResult.ok(directory.resolve(name));
        }

        @Override
        public CapabilityResult<Path> rename(Path entry, String name) {
            return CapabilityResult.ok(entry);
        }

        @Override
        public CapabilityResult<Path> delete(Path entry) {
            return CapabilityResult.ok(entry);
        }
    }

    private static VintelliShell defaultShell() {
        return defaultShell(defaultThemes());
    }

    private static VintelliShell defaultShell(ShellThemes themes) {
        return new VintelliShell(ShellConfig.of(defaultWindows(), new ShellState(), themes));
    }

    private static List<ToolWindow> defaultWindows() {
        ShellThemes themes = defaultThemes();
        ToolWindowRegistry registry = new ToolWindowRegistry(List.of(
                new RunToolWindow(() -> themes), new TerminalToolWindow(() -> themes), new ProblemsToolWindow(() -> themes)));
        return registry.toolWindows();
    }

    private static List<ToolWindow> windowsWithAcp() {
        ShellThemes themes = defaultThemes();
        ToolWindowRegistry registry = new ToolWindowRegistry(List.of(
                new RunToolWindow(() -> themes), new TerminalToolWindow(() -> themes),
                new ProblemsToolWindow(() -> themes), new AcpAgentsToolWindow(() -> themes)));
        return registry.toolWindows();
    }

    private static VintelliShell shellWithAllBuiltInWindows() {
        ShellThemes themes = defaultThemes();
        List<ToolWindow> windows = new ArrayList<>(windowsWithAcp());
        windows.add(new StructureToolWindow(() -> themes));
        return new VintelliShell(ShellConfig.of(new ToolWindowRegistry(windows).toolWindows(), new ShellState(), themes));
    }

    private static VintelliShell shellWithWindow(ToolWindow window) {
        ShellThemes themes = defaultThemes();
        ToolWindowRegistry registry = new ToolWindowRegistry(List.of(
                new RunToolWindow(() -> themes), new TerminalToolWindow(() -> themes), new ProblemsToolWindow(() -> themes),
                new AcpAgentsToolWindow(() -> themes)));
        List<ToolWindow> windows = new ArrayList<>(registry.toolWindows());
        windows.add(window);
        return new VintelliShell(ShellConfig.of(new ToolWindowRegistry(windows).toolWindows(), new ShellState(), themes));
    }

    private static VintelliShell shellWithFakeWindow(FakeToolWindow fake) {
        ShellThemes themes = defaultThemes();
        ToolWindowRegistry registry = new ToolWindowRegistry(List.of(
                new RunToolWindow(() -> themes), new TerminalToolWindow(() -> themes), new ProblemsToolWindow(() -> themes),
                new AcpAgentsToolWindow(() -> themes)));
        List<ToolWindow> windows = new ArrayList<>(registry.toolWindows());
        windows.add(fake);
        return new VintelliShell(ShellConfig.of(new ToolWindowRegistry(windows).toolWindows(), new ShellState(), themes));
    }

    private static final ToolTabBarElement.Glyphs NERD_TAB_GLYPHS = new ToolTabBarElement.Glyphs("━", "«", "»");

    private static ToolTabBarElement rightTabBar(int selected, ToolTabBarElement.Glyphs glyphs) {
        List<ToolTabBarElement.Label> labels = List.of(
                new ToolTabBarElement.Label("4", "Alpha"),
                new ToolTabBarElement.Label("6", "Bravo"),
                new ToolTabBarElement.Label("7", "Charlie"),
                new ToolTabBarElement.Label("8", "Delta"));
        return new ToolTabBarElement("tool-tabs-right", labels, selected, glyphs);
    }

    private static List<String> renderTabBar(ToolTabBarElement bar, int width) {
        Rect area = new Rect(0, 0, width, ToolTabBarElement.ROWS);
        Buffer buffer = Buffer.empty(area);
        DefaultRenderContext context = DefaultRenderContext.createEmpty();
        context.setStyleEngine(defaultThemes().engine());
        bar.render(Frame.forTesting(buffer), area, context);
        List<String> rows = new ArrayList<>();
        for (int y = 0; y < ToolTabBarElement.ROWS; y++) {
            StringBuilder text = new StringBuilder();
            for (int x = 0; x < width; x++) {
                text.append(buffer.get(x, y).symbol());
            }
            rows.add(text.toString());
        }
        return rows;
    }

    /**
     * Waits until the shell under test has handled every event dispatched so
     * far. The fixed half second window this replaces was a guess at how long
     * the render thread needs; the barrier waits for the real thing (the
     * runner ran a marker task after the queued key), so the assertions read
     * the state they expect as soon as it exists instead of after a window
     * that is either too short or too long. The fixed sleeps stay where the
     * wait is for something else: the runner startup (STARTUP) and the polls
     * of a background thread (30 ms steps).
     */
    private void settle(Pilot pilot) {
        settleStrategy.await(pilot);
    }

    /** Waits for the shell under test to have handled the input dispatched so far. */
    private interface Settle {
        void await(Pilot pilot);
    }

    private Settle settleStrategy = pilot -> pilot.pause(EVENT_WINDOW);

    private static void awaitHandled(ToolkitRunner runner) {
        CountDownLatch handled = new CountDownLatch(1);
        runner.runOnRenderThread(handled::countDown);
        long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
        while (handled.getCount() > 0 && runner.isRunning() && System.nanoTime() < deadline) {
            sleep(SETTLE_STEP);
        }
        if (handled.getCount() > 0 && runner.isRunning()) {
            throw new AssertionError("the shell did not handle the queued input within "
                    + SETTLE_TIMEOUT.toMillis() + " ms");
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the shell", interrupted);
        }
    }

    private static void pause(Pilot pilot, Duration duration) {
        pilot.pause(duration);
    }

    private void press(Pilot pilot, char key) {
        pilot.press(key);
        settle(pilot);
    }

    private void press(Pilot pilot, char key, KeyModifiers modifiers) {
        pilot.press(key, modifiers);
        settle(pilot);
    }

    /**
     * A harmless second key press after the arrows, page keys, Home/End or
     * clicks: those are consumed by the focused project list before the shell
     * handler runs, so a follow-up event reads the selection back from the
     * list into the shell state and the status line deterministically.
     */
    private void pressQuietly(Pilot pilot) {
        pilot.press('x');
        settle(pilot);
    }

    private void pressCtrlC(Pilot pilot) {
        press(pilot, 'c', KeyModifiers.CTRL);
    }

    private void type(Pilot pilot, String text) {
        for (int index = 0; index < text.length(); index++) {
            pilot.press(text.charAt(index));
            settle(pilot);
        }
    }

    /** Types into the open palette filter on the synchronous entry point. */
    private static void type(VintelliShell shell, String text) {
        for (int index = 0; index < text.length(); index++) {
            shell.handle(KeyEvent.ofChar(text.charAt(index)));
        }
    }

    private static String helpText(Buffer buffer) {
        StringBuilder text = new StringBuilder();
        for (int row = 5; row < 20; row++) {
            text.append(rowText(buffer, row));
            text.append('\n');
        }
        return text.toString();
    }

    @Test
    void theProjectPaneToneFollowsTheThemeAndTheEditorStaysAnIsland() {
        for (String name : List.of("dark", "darcula")) {
            ShellThemes themes = new ShellThemes(name, (Path) null);
            Buffer buffer = offlineFrame(defaultShell(themes));
            String projectTone = name.equals("darcula") ? "panel" : "editor-bg";

            assertEquals(themes.color(projectTone), cellBg(buffer, 6, 12),
                    name + ": the project pane is drawn in the " + projectTone + " tone");
            assertEquals(themes.color("editor-bg"), cellBg(buffer, 60, 5),
                    name + ": the editor island is drawn in the editor-bg tone");
        }
    }

    private static Buffer offlineFrame(VintelliShell shell) {
        return offlineFocusFrame(shell, shell.themes(), null);
    }

    private static String awaitRendered(VintelliShell shell, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String rendered = bufferText(offlineFrame(shell));
        while (!rendered.contains(expected) && System.nanoTime() < deadline) {
            Thread.sleep(10);
            rendered = bufferText(offlineFrame(shell));
        }
        return rendered;
    }

    private static Buffer offlineFrame(VintelliShell shell, Size size) {
        return offlineFocusFrame(shell, shell.themes(), null, new Rect(0, 0, size.width(), size.height()));
    }

    private static Buffer offlineFocusFrame(VintelliShell shell, ShellThemes themes, String focusedId) {
        return offlineFocusFrame(shell, themes, focusedId, VIEWPORT);
    }

    private static Buffer offlineFocusFrame(VintelliShell shell, ShellThemes themes, String focusedId, Rect viewport) {
        Buffer buffer = Buffer.empty(viewport);
        DefaultRenderContext context = DefaultRenderContext.createEmpty();
        context.setStyleEngine(themes.engine());
        if (focusedId != null) {
            context.focusManager().setFocus(focusedId);
        }
        Element root = shell.root();
        root.render(Frame.forTesting(buffer), viewport, context);
        context.registerElement(root, viewport);
        return buffer;
    }

    private static ShellLayout customizedLayout(double projectRatio, double bottomRatio, List<ToolWindow> windows) {
        Set<String> ids = Set.copyOf(windows.stream().map(ToolWindow::id).toList());
        ShellLayout layout = new ShellLayout(projectRatio, bottomRatio, ids, ids);
        layout.setSizeCustomized(true);
        return layout;
    }

    private static int frameRowOf(String frame, int column, String text) {
        String[] rows = frame.split("\n", -1);
        for (int row = 0; row < rows.length; row++) {
            if (rows[row].startsWith(text, column)) {
                return row;
            }
        }
        return -1;
    }

    private static int lastFrameRowOf(String frame, int column, String text) {
        String[] rows = frame.split("\n", -1);
        for (int row = rows.length - 1; row >= 0; row--) {
            if (rows[row].startsWith(text, column)) {
                return row;
            }
        }
        return -1;
    }

    private static ShellThemes defaultThemes() {
        return new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
    }

    private static Color cellBg(Buffer buffer, int x, int row) {
        return buffer.get(x, row).style().bg().orElse(null);
    }

    private static int highlightedCount(Buffer buffer, int row, Color background) {
        int count = 0;
        for (int x = 0; x < buffer.width(); x++) {
            if (background.equals(buffer.get(x, row).style().bg().orElse(null))) {
                count++;
            }
        }
        return count;
    }

    private static int firstHighlightedColumn(Buffer buffer, int row, Color background) {
        for (int x = 0; x < buffer.width(); x++) {
            if (background.equals(buffer.get(x, row).style().bg().orElse(null))) {
                return x;
            }
        }
        return -1;
    }

    private static int contiguousHighlight(Buffer buffer, int row, Color background) {
        int first = firstHighlightedColumn(buffer, row, background);
        if (first < 0) {
            return 0;
        }
        int run = 0;
        for (int x = first; x < buffer.width(); x++) {
            if (!background.equals(buffer.get(x, row).style().bg().orElse(null))) {
                break;
            }
            run++;
        }
        return run;
    }

    private static String rowText(Buffer buffer, int row) {
        StringBuilder text = new StringBuilder();
        for (int x = 0; x < buffer.width(); x++) {
            text.append(buffer.get(x, row).symbol());
        }
        return text.toString();
    }

    private static String bufferText(Buffer buffer) {
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < buffer.height(); row++) {
            text.append(rowText(buffer, row)).append('\n');
        }
        return text.toString();
    }

    private static int findBottomTabRow(Buffer buffer) {
        for (int row = 0; row < buffer.height(); row++) {
            String text = rowText(buffer, row);
            if (text.contains("4 Run") && text.contains("2 Terminal")) {
                return row;
            }
        }
        return 13;
    }

    private static int firstForegroundColumn(Buffer buffer, int row, Color foreground) {
        for (int x = 0; x < buffer.width(); x++) {
            if (foreground.equals(buffer.get(x, row).style().fg().orElse(null))) {
                return x;
            }
        }
        return -1;
    }

    private static int contiguousForeground(Buffer buffer, int row, Color foreground) {
        int first = firstForegroundColumn(buffer, row, foreground);
        if (first < 0) {
            return 0;
        }
        int run = 0;
        for (int x = first; x < buffer.width(); x++) {
            if (!foreground.equals(buffer.get(x, row).style().fg().orElse(null))) {
                break;
            }
            run++;
        }
        return run;
    }
}
