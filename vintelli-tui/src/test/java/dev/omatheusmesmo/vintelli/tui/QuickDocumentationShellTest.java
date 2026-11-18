package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.api.capability.HoverCapability;
import dev.omatheusmesmo.vintelli.api.capability.HoverCapability.Documentation;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.editor.CompletionSite;
import dev.omatheusmesmo.vintelli.tui.editor.QuickDocumentationFixtures;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.app.ToolkitTestRunner;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.tui.pilot.Pilot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Quick documentation through the assembled shell. Ctrl+Q opens the popup beside the caret; the screen is read by
 * rendering the shell offline, so the states, the scrolling and the loading frame are checked on what is drawn. The
 * keys that type into the editor are routed by the TamboUI event router, so the tests that type run through a Pilot.
 */
class QuickDocumentationShellTest {

    private static final Size SIZE = new Size(120, 40);
    private static final Rect VIEWPORT = new Rect(0, 0, SIZE.width(), SIZE.height());
    private static final Duration SETTLE = Duration.ofMillis(400);
    private static final Duration ANSWER_WAIT = Duration.ofSeconds(10);
    private static final String BLANK_LINE_TWO = "class Demo {\n    void m() {\n\n    }\n}\n";
    private static final String SIGNATURE = "```java\nint String.length()\n```\n---\n"
            + "Returns the <code>length</code> of this string.\n";

    @TempDir
    Path project;

    @Test
    void ctrlQShowsTheSignatureFirstAndTheDocumentationBelowIt() throws Exception {
        Harness harness = new Harness("Signature.java", documented(SIGNATURE));

        harness.press('q', KeyModifiers.CTRL);

        String screen = harness.awaitScreen("Returns the length of this string.");
        assertTrue(screen.indexOf("int String.length()") < screen.indexOf("Returns the length"), screen);
        assertFalse(screen.contains("<code>"), "the HTML tags are read, not shown: " + screen);
    }

    @Test
    void theFrameDrawsLoadingBeforeTheAnswerArrives() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        Harness harness = new Harness("Loading.java",
                new QuickDocumentationFixtures.Provider(CapabilityResult.ok(new Documentation(SIGNATURE)), gate));

        harness.press('q', KeyModifiers.CTRL);

        assertTrue(harness.screen().contains("Loading..."), "the popup draws while the server has not answered");
        gate.countDown();
        String answered = harness.awaitScreen("Returns the length of this string.");
        assertFalse(answered.contains("Loading..."), answered);
    }

    @Test
    void anEmptyAnswerSaysNoDocumentationWasFound() throws Exception {
        Harness harness = new Harness("Empty.java", documented(""));

        harness.press('q', KeyModifiers.CTRL);

        assertTrue(harness.awaitScreen("No documentation found").contains("No documentation found"));
    }

    @Test
    void aServerThatIsNotRunningSaysWhy() throws Exception {
        Harness harness = new Harness("Unavailable.java",
                CapabilitySelection.unavailable(CapabilityType.HOVER, "the server is not running"));

        harness.press('q', KeyModifiers.CTRL);

        assertTrue(harness.awaitScreen("Quick documentation unavailable: the server is not running")
                .contains("Quick documentation unavailable: the server is not running"));
    }

    @Test
    void aFailedAnswerSaysWhy() throws Exception {
        Harness harness = new Harness("Failed.java", new QuickDocumentationFixtures.Provider(
                CapabilityResult.failed(new CapabilityFailure("timeout", "the server did not answer"))));

        harness.press('q', KeyModifiers.CTRL);

        assertTrue(harness.awaitScreen("Quick documentation failed: the server did not answer")
                .contains("the server did not answer"));
    }

    @Test
    void downPageDownAndUpScrollALongDocumentationWithoutMovingTheCaret() throws Exception {
        Harness harness = new Harness("Scroll.java", documented(longCode(40)));
        harness.press('q', KeyModifiers.CTRL);
        assertTrue(harness.awaitScreen("entry-00").contains("entry-00"));
        CompletionSite caret = harness.caret();

        harness.press(KeyCode.PAGE_DOWN);
        String paged = harness.screen();
        assertTrue(paged.contains("entry-18"), paged);
        assertFalse(paged.contains("entry-00"), paged);

        harness.press(KeyCode.DOWN);
        assertTrue(harness.screen().contains("entry-36"), "one row further down shows the next last row");

        harness.press(KeyCode.UP);
        assertTrue(harness.screen().contains("entry-18"));

        assertEquals(caret, harness.caret(), "the arrow and page keys scroll the popup and leave the caret alone");
    }

    @Test
    void escapeClosesThePopupWithoutTypingAnything() throws Exception {
        Harness harness = new Harness("Escape.java", documented(SIGNATURE));
        harness.press('q', KeyModifiers.CTRL);
        harness.awaitScreen("Returns the length of this string.");

        harness.press(KeyCode.ESCAPE);

        assertFalse(harness.screen().contains("Quick Documentation"), "Escape closed the popup");
    }

    @Test
    void anAnswerThatArrivesAfterTheCaretLeftIsNotShown() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        Harness harness = new Harness("Stale.java",
                new QuickDocumentationFixtures.Provider(CapabilityResult.ok(new Documentation(SIGNATURE)), gate));
        harness.press('q', KeyModifiers.CTRL);
        harness.press(KeyCode.LEFT);

        gate.countDown();
        Thread.sleep(SETTLE.toMillis());

        assertFalse(harness.screen().contains("Returns the length of this string."),
                "the caret left before the answer arrived, so the answer is dropped");
    }

    @Test
    void aTypedKeyClosesThePopupAndReachesTheEditor() throws Exception {
        PilotHarness harness = new PilotHarness("Typing.java", documented(SIGNATURE));
        try {
            harness.pilot.press('q', KeyModifiers.CTRL);
            harness.pilot.pause(SETTLE);

            harness.pilot.press('y');
            harness.pilot.pause(SETTLE);

            assertEquals("y", harness.line(2), "the typed key reached the editor, the popup took no part in it");
        } finally {
            harness.test.close();
        }
    }

    @Test
    void theEditorTakesTheKeysAgainAfterEscape() throws Exception {
        PilotHarness harness = new PilotHarness("Escaped.java", documented(SIGNATURE));
        try {
            harness.pilot.press('q', KeyModifiers.CTRL);
            harness.pilot.pause(SETTLE);
            harness.pilot.press(KeyCode.ESCAPE);
            harness.pilot.pause(SETTLE);

            harness.pilot.press('z');
            harness.pilot.pause(SETTLE);

            assertEquals("z", harness.line(2));
        } finally {
            harness.test.close();
        }
    }

    private static QuickDocumentationFixtures.Provider documented(String markdown) {
        return new QuickDocumentationFixtures.Provider(CapabilityResult.ok(new Documentation(markdown)));
    }

    private static String longCode(int lines) {
        List<String> entries = new ArrayList<>();
        for (int index = 0; index < lines; index++) {
            entries.add(String.format("entry-%02d", index));
        }
        return "```\n" + String.join("\n", entries) + "\n```\n";
    }

    private static final class Shell {

        private final BuiltinEditor editor;
        private final VintelliShell shell;

        Shell(Path project, String name, CapabilitySelection<HoverCapability> hover) throws Exception {
            Path file = project.resolve(name);
            Files.writeString(file, BLANK_LINE_TWO);
            ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
            editor = new BuiltinEditor(project, null, themes::editorPalette);
            editor.open(new OpenRequest(file.toRealPath(), 2));
            List<ToolWindow> windows = new ArrayList<>(new ToolWindowRegistry(List.of(
                    new RunToolWindow(() -> themes), new TerminalToolWindow(() -> themes),
                    new ProblemsToolWindow(() -> themes))).toolWindows());
            windows.add(editor);
            List<ToolWindow> toolWindows = new ToolWindowRegistry(windows).toolWindows();
            ShellConfig config = new ShellConfig(toolWindows, new ShellState(),
                    () -> Keymap.defaultKeymap(toolWindows), themes, null, List.of(),
                    QuickDocumentationFixtures.registryWith(hover),
                    List.of(), false, toolWindows, null, null, IdeaStatusBadge.absent(), null, false);
            shell = new VintelliShell(config);
            shell.useUiWorkQueue(new UiWorkQueue());
        }
    }

    /**
     * A shell that receives key events directly and is rendered offline to read the screen.
     */
    private final class Harness {

        private final BuiltinEditor editor;
        private final VintelliShell shell;

        Harness(String name, QuickDocumentationFixtures.Provider provider) throws Exception {
            this(name, CapabilitySelection.selected(CapabilityType.HOVER, provider));
        }

        Harness(String name, CapabilitySelection<HoverCapability> hover) throws Exception {
            Shell built = new Shell(project, name, hover);
            editor = built.editor;
            shell = built.shell;
            render();
            editor.requestFocus();
            render();
        }

        void press(char key, KeyModifiers modifiers) {
            shell.handle(KeyEvent.ofChar(key, modifiers));
            render();
        }

        void press(KeyCode key) {
            shell.handle(KeyEvent.ofKey(key));
            render();
        }

        CompletionSite caret() {
            return editor.completionSite().orElseThrow();
        }

        String screen() {
            return text(render());
        }

        String awaitScreen(String expected) throws InterruptedException {
            long deadline = System.nanoTime() + ANSWER_WAIT.toNanos();
            String screen = screen();
            while (!screen.contains(expected) && System.nanoTime() < deadline) {
                Thread.sleep(20);
                screen = screen();
            }
            return screen;
        }

        private Buffer render() {
            Buffer buffer = Buffer.empty(VIEWPORT);
            DefaultRenderContext context = DefaultRenderContext.createEmpty();
            context.setStyleEngine(shell.themes().engine());
            Element root = shell.root();
            root.render(Frame.forTesting(buffer), VIEWPORT, context);
            context.registerElement(root, VIEWPORT);
            return buffer;
        }

        private String text(Buffer buffer) {
            StringBuilder text = new StringBuilder();
            for (int y = 0; y < buffer.height(); y++) {
                for (int x = 0; x < buffer.width(); x++) {
                    text.append(buffer.get(x, y).symbol());
                }
                text.append('\n');
            }
            return text.toString();
        }
    }

    /**
     * A shell in a running TamboUI runner, so typed keys reach the editor through the event router.
     */
    private final class PilotHarness {

        private final BuiltinEditor editor;
        private final ToolkitTestRunner test;
        private final Pilot pilot;

        PilotHarness(String name, QuickDocumentationFixtures.Provider provider) throws Exception {
            Shell built = new Shell(project, name, CapabilitySelection.selected(CapabilityType.HOVER, provider));
            editor = built.editor;
            VintelliShell shell = built.shell;
            test = ToolkitTestRunner.runTest(shell::root, SIZE);
            test.runner().styleEngine(shell.themes().engine());
            test.runner().eventRouter().addGlobalHandler(shell);
            shell.bindQuit(test.runner()::quit);
            shell.bindRedraw(() -> test.runner().tuiRunner().dispatch(TickEvent.of(0)));
            pilot = test.pilot();
            pilot.pause(SETTLE);
            editor.requestFocus();
            pilot.pause(SETTLE);
        }

        String line(int row) {
            return editor.activeDocument().orElseThrow().text().split("\n", -1)[row];
        }
    }
}
