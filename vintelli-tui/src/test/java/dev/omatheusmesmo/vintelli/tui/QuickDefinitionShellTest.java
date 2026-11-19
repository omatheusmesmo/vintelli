package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.DocumentSymbol;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.Location;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.editor.CompletionSite;
import dev.omatheusmesmo.vintelli.tui.editor.QuickDefinitionFixtures;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.style.Color;
import dev.tamboui.style.ColorConverter;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Quick Definition through the assembled shell. The chord opens the popup below the caret line with the declaration
 * coloured like the editor, the editor keeps its caret and tab while it is open, Enter or Ctrl+B opens the file at
 * the target line and Escape leaves the editor where it was. The navigation provider is a fixed answer and the file
 * text comes from a real temporary file; the screen is read by rendering the shell offline.
 */
class QuickDefinitionShellTest {

    private static final Size SIZE = new Size(120, 40);
    private static final Rect VIEWPORT = new Rect(0, 0, SIZE.width(), SIZE.height());
    private static final Duration ANSWER_WAIT = Duration.ofSeconds(10);
    private static final String CALLER = "class Demo {\n    void m() {\n        Util.run();\n    }\n}\n";
    private static final String UTIL = """
            class Util {
                /** Runs. */
                static void run() {
                    System.out.println("ran");
                }

                static void stop() {
                }
            }
            """;
    private static final KeyEvent CTRL_SHIFT_I = KeyEvent.builder().code(KeyCode.CHAR)
            .modifiers(KeyModifiers.of(true, false, true)).character('I').keyCodePoint('i')
            .shiftedKeyCodePoint('I').baseLayoutKeyCodePoint('i').build();

    @TempDir
    Path project;

    @Test
    void ctrlShiftIShowsTheMethodBodyHighlightedAndTheEditorStaysWhereItWas() throws Exception {
        Harness harness = new Harness(UTIL, List.of(new Location(realUtil(), 2, 16)),
                List.of(QuickDefinitionFixtures.method("run", 1, 4), QuickDefinitionFixtures.method("stop", 6, 7)));
        CompletionSite before = harness.caret();

        harness.press(CTRL_SHIFT_I);

        String screen = harness.awaitScreen("System.out.println");
        assertTrue(screen.contains("Quick Definition") && screen.contains("Util.java:3"), screen);
        assertTrue(screen.contains("static void run()"), screen);
        assertFalse(screen.contains("static void stop()"), "only the declaration is shown: " + screen);
        assertEquals(before, harness.caret(), "the caret, the text and the tab are untouched");
        assertEquals(harness.demo, Path.of(harness.editor.activeDocument().orElseThrow().file()));
        assertEquals(1, harness.editor.tabs().size());
        assertEquals(harness.keywordColor(), harness.colorOf(harness.render(), "static void run()", 0),
                "the code is coloured by the editor highlighter");
        assertNotEquals(harness.keywordColor(), harness.colorOf(harness.render(), "static void run()", 12));
    }

    @Test
    void altSpaceOpensThePopupWhereTheTerminalLacksTheKittyProtocol() throws Exception {
        Harness harness = new Harness(UTIL, List.of(new Location(realUtil(), 2, 16)),
                List.of(QuickDefinitionFixtures.method("run", 1, 4)));

        harness.press(KeyEvent.ofChar(' ', KeyModifiers.ALT));

        assertTrue(harness.awaitScreen("System.out.println").contains("Quick Definition"));
    }

    @Test
    void enterOpensTheTargetFileAtItsLineAndClosesThePopup() throws Exception {
        Harness harness = new Harness(UTIL, List.of(new Location(realUtil(), 2, 16)),
                List.of(QuickDefinitionFixtures.method("run", 1, 4)));
        harness.press(CTRL_SHIFT_I);
        harness.awaitScreen("System.out.println");

        harness.press(KeyEvent.ofKey(KeyCode.ENTER));

        assertEquals(realUtil(), Path.of(harness.editor.activeDocument().orElseThrow().file()));
        assertEquals(2, harness.editor.activeDocument().orElseThrow().line());
        assertFalse(harness.screen().contains("Quick Definition"), "Enter closes the popup");
    }

    @Test
    void ctrlBOpensTheShownTargetLikeEnter() throws Exception {
        Harness harness = new Harness(UTIL, List.of(new Location(realUtil(), 2, 16)),
                List.of(QuickDefinitionFixtures.method("run", 1, 4)));
        harness.press(CTRL_SHIFT_I);
        harness.awaitScreen("System.out.println");

        harness.press(KeyEvent.ofChar('b', KeyModifiers.CTRL));

        assertEquals(realUtil(), Path.of(harness.editor.activeDocument().orElseThrow().file()));
        assertFalse(harness.screen().contains("Quick Definition"));
    }

    @Test
    void escapeClosesThePopupAndTheCaretIsTheSame() throws Exception {
        Harness harness = new Harness(UTIL, List.of(new Location(realUtil(), 2, 16)),
                List.of(QuickDefinitionFixtures.method("run", 1, 4)));
        CompletionSite before = harness.caret();
        harness.press(CTRL_SHIFT_I);
        harness.awaitScreen("System.out.println");

        harness.press(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertFalse(harness.screen().contains("Quick Definition"));
        assertEquals(before, harness.caret());
        assertEquals(harness.demo, Path.of(harness.editor.activeDocument().orElseThrow().file()));
    }

    @Test
    void altRightAndAltLeftPageBetweenSeveralTargets() throws Exception {
        Harness harness = new Harness(UTIL, List.of(new Location(realUtil(), 2, 16), new Location(realUtil(), 6, 16)),
                List.of(QuickDefinitionFixtures.method("run", 1, 4), QuickDefinitionFixtures.method("stop", 6, 7)));
        harness.press(CTRL_SHIFT_I);
        String first = harness.awaitScreen("System.out.println");
        assertTrue(first.contains("1/2"), first);

        harness.press(KeyEvent.ofKey(KeyCode.RIGHT, KeyModifiers.ALT));

        String second = harness.screen();
        assertTrue(second.contains("2/2") && second.contains("static void stop()"), second);
        assertTrue(second.contains("Util.java:7"), second);

        harness.press(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals(6, harness.editor.activeDocument().orElseThrow().line(), "the paged target is the one that opens");
    }

    @Test
    void pageDownAndUpScrollALongDeclarationWithoutMovingTheCaret() throws Exception {
        StringBuilder body = new StringBuilder("class Util {\n    static void run() {\n");
        IntStream.range(0, 40).forEach(index -> body.append(String.format("        step%02d();%n", index)));
        body.append("    }\n}\n");
        Harness harness = new Harness(body.toString(), List.of(new Location(realUtil(), 1, 16)),
                List.of(QuickDefinitionFixtures.method("run", 1, 42)));
        CompletionSite before = harness.caret();
        harness.press(CTRL_SHIFT_I);
        assertTrue(harness.awaitScreen("step00").contains("step00"));

        harness.press(KeyEvent.ofKey(KeyCode.PAGE_DOWN));

        String paged = harness.screen();
        assertFalse(paged.contains("step00"), paged);
        assertTrue(paged.contains("step15"), paged);
        harness.press(KeyEvent.ofKey(KeyCode.PAGE_UP));
        assertTrue(harness.screen().contains("step00"));
        assertEquals(before, harness.caret(), "the page keys scroll the popup and leave the caret alone");
    }

    @Test
    void aLibraryLocationSaysWhyOnTheStatusLine() throws Exception {
        Path library = Path.of("jdt://contents/java.base/java.lang/String.class");
        Harness harness = new Harness(UTIL, List.of(new Location(library, 10, 4)), List.of());

        harness.press(CTRL_SHIFT_I);

        String screen = harness.awaitScreen("jdt://");
        assertTrue(screen.contains("String.class") && screen.contains("jdt://"), screen);
        assertTrue(screen.contains("Quick definition unavailable"), screen);
    }

    @Test
    void aTypedKeyClosesThePopup() throws Exception {
        Harness harness = new Harness(UTIL, List.of(new Location(realUtil(), 2, 16)),
                List.of(QuickDefinitionFixtures.method("run", 1, 4)));
        harness.press(CTRL_SHIFT_I);
        harness.awaitScreen("System.out.println");

        harness.press(KeyEvent.ofKey(KeyCode.LEFT));

        assertFalse(harness.screen().contains("Quick Definition"), "a key the popup does not take closes it");
    }

    private Path realUtil() throws IOException {
        Path util = project.resolve("Util.java");
        if (!Files.exists(util)) {
            Files.writeString(util, UTIL);
        }
        return util.toRealPath();
    }

    private final class Harness {

        private final BuiltinEditor editor;
        private final VintelliShell shell;
        private final ShellThemes themes;
        private final Path demo;

        Harness(String utilText, List<Location> locations, List<DocumentSymbol> outline) throws Exception {
            Path util = project.resolve("Util.java");
            Files.writeString(util, utilText);
            Path caller = project.resolve("Demo.java");
            Files.writeString(caller, CALLER);
            demo = caller.toRealPath();
            themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
            editor = new BuiltinEditor(project, null, themes::editorPalette);
            assertFalse(editor.open(new OpenRequest(demo, 2)).isFailure());
            shell = new VintelliShell(ShellConfig.of(List.of(editor), new ShellState(), themes)
                    .withNavigationProviders(List.of(new QuickDefinitionFixtures.Navigation(
                            CapabilityResult.ok(locations), outline)))
                    .withCapabilityRegistry(QuickDefinitionFixtures.registryReadingFiles()));
            shell.useUiWorkQueue(new UiWorkQueue());
            render();
            editor.requestFocus();
            render();
        }

        CompletionSite caret() {
            return editor.completionSite().orElseThrow();
        }

        void press(KeyEvent key) {
            shell.handle(key);
            render();
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

        Color keywordColor() {
            return ColorConverter.INSTANCE.convert(themes.editorPalette().keyword()).orElseThrow();
        }

        Color colorOf(Buffer buffer, String line, int offset) {
            for (int y = 0; y < buffer.height(); y++) {
                String row = text(buffer, y);
                int at = row.indexOf(line);
                if (at >= 0) {
                    return buffer.get(at + offset, y).style().fg().orElse(null);
                }
            }
            throw new AssertionError("no row of the screen holds '" + line + "'");
        }

        Buffer render() {
            Buffer buffer = Buffer.empty(VIEWPORT);
            DefaultRenderContext context = DefaultRenderContext.createEmpty();
            context.setStyleEngine(shell.themes().engine());
            Element root = shell.root();
            root.render(Frame.forTesting(buffer), VIEWPORT, context);
            context.registerElement(root, VIEWPORT);
            return buffer;
        }

        private String text(Buffer buffer) {
            List<String> rows = new ArrayList<>();
            for (int y = 0; y < buffer.height(); y++) {
                rows.add(text(buffer, y));
            }
            return String.join("\n", rows);
        }

        private String text(Buffer buffer, int y) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < buffer.width(); x++) {
                row.append(buffer.get(x, y).symbol());
            }
            return row.toString();
        }
    }
}
