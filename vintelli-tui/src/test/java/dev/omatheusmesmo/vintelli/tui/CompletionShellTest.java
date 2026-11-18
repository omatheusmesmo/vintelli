package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Kind;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Suggestion;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.editor.CompletionFixtures;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.tamboui.layout.Size;
import dev.tamboui.toolkit.app.ToolkitTestRunner;
import dev.tamboui.tui.event.KeyCode;
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

/**
 * Code completion keys through the assembled shell: Ctrl+Space, Ctrl+@, typing that narrows the list, the
 * dot that opens it, and the keys that insert or close it, driven through the real key routing.
 */
class CompletionShellTest {

    private static final Size SIZE = new Size(120, 40);
    private static final Duration SETTLE = Duration.ofMillis(400);
    private static final String BLANK_LINE_TWO = "class Demo {\n    void m() {\n\n    }\n}\n";
    private static final Suggestion ALPHA = suggestion("alpha", "alpha()");
    private static final Suggestion BETA = suggestion("beta", "beta()");
    private static final Suggestion GAMMA = suggestion("gamma", "gamma()");

    @TempDir
    Path project;

    @Test
    void ctrlSpaceOpensTheListAndEnterInsertsTheSuggestionDownArrowSelected() throws Exception {
        Harness harness = new Harness("Arrow.java", BLANK_LINE_TWO, List.of(ALPHA, BETA, GAMMA));
        try {
            Pilot pilot = harness.pilot();
            pilot.press(' ', KeyModifiers.CTRL);
            pilot.pause(SETTLE);
            pilot.press(KeyCode.DOWN);
            pilot.pause(SETTLE);
            pilot.press(KeyCode.ENTER);
            pilot.pause(SETTLE);

            assertEquals("beta()", harness.line(2));
        } finally {
            harness.close();
        }
    }

    @Test
    void typingNarrowsTheListSoEnterInsertsTheOnlyMatchOverTheTypedText() throws Exception {
        Harness harness = new Harness("Narrow.java", BLANK_LINE_TWO, List.of(ALPHA, BETA, GAMMA));
        try {
            Pilot pilot = harness.pilot();
            pilot.press(' ', KeyModifiers.CTRL);
            pilot.pause(SETTLE);
            pilot.press('g');
            pilot.pause(SETTLE);
            pilot.press(KeyCode.ENTER);
            pilot.pause(SETTLE);

            assertEquals("gamma()", harness.line(2), "the typed g is replaced by the one suggestion that matches it");
        } finally {
            harness.close();
        }
    }

    @Test
    void ctrlAtOpensTheListForTerminalsThatSendItAsAt() throws Exception {
        Harness harness = new Harness("CtrlAt.java", BLANK_LINE_TWO, List.of(ALPHA, BETA));
        try {
            Pilot pilot = harness.pilot();
            pilot.press('@', KeyModifiers.CTRL);
            pilot.pause(SETTLE);
            pilot.press(KeyCode.ENTER);
            pilot.pause(SETTLE);

            assertEquals("alpha()", harness.line(2));
        } finally {
            harness.close();
        }
    }

    @Test
    void escapeClosesTheListWithoutInsertingAnything() throws Exception {
        Harness harness = new Harness("Escape.java", BLANK_LINE_TWO, List.of(ALPHA, BETA));
        try {
            Pilot pilot = harness.pilot();
            pilot.press(' ', KeyModifiers.CTRL);
            pilot.pause(SETTLE);
            pilot.press(KeyCode.ESCAPE);
            pilot.pause(SETTLE);
            pilot.press(KeyCode.ENTER);
            pilot.pause(SETTLE);

            assertEquals("", harness.line(2), "Escape closed the list, so no suggestion was inserted");
            assertEquals("    }", harness.line(4), "the Enter went to the editor and opened a line");
        } finally {
            harness.close();
        }
    }

    @Test
    void aDotBehindAnIdentifierOpensTheListInAJavaFile() throws Exception {
        Harness harness = new Harness("Dot.java", BLANK_LINE_TWO, List.of(ALPHA, BETA));
        try {
            Pilot pilot = harness.pilot();
            for (char letter : "text.".toCharArray()) {
                pilot.press(letter);
                pilot.pause(SETTLE);
            }
            pilot.press(KeyCode.ENTER);
            pilot.pause(SETTLE);

            assertEquals("text.alpha()", harness.line(2), "the dot opened the list and Enter took its first row");
        } finally {
            harness.close();
        }
    }

    private static Suggestion suggestion(String label, String insertText) {
        return new Suggestion(label, Kind.METHOD, "", null, insertText, false, null, null);
    }

    /**
     * A shell with one open document and a completion provider that answers with the given suggestions.
     */
    private final class Harness {

        private final BuiltinEditor editor;
        private final VintelliShell shell;
        private final ToolkitTestRunner test;

        Harness(String name, String text, List<Suggestion> answers) throws Exception {
            Path file = project.resolve(name);
            Files.writeString(file, text);
            ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
            editor = new BuiltinEditor(project, null, themes::editorPalette);
            editor.open(new OpenRequest(file.toRealPath(), 2));
            CompletionFixtures.Provider provider = new CompletionFixtures.Provider(
                    (call, content) -> CapabilityResult.ok(answers));
            List<ToolWindow> windows = new ArrayList<>(new ToolWindowRegistry(List.of(
                    new RunToolWindow(() -> themes), new TerminalToolWindow(() -> themes),
                    new ProblemsToolWindow(() -> themes))).toolWindows());
            windows.add(editor);
            List<ToolWindow> toolWindows = new ToolWindowRegistry(windows).toolWindows();
            ShellConfig config = new ShellConfig(toolWindows, new ShellState(),
                    () -> Keymap.defaultKeymap(toolWindows), themes, null, List.of(),
                    CompletionFixtures.registryWith(CapabilitySelection.selected(CapabilityType.COMPLETION, provider)),
                    List.of(), false, toolWindows, null, null, IdeaStatusBadge.absent(), null, false);
            shell = new VintelliShell(config);
            shell.useUiWorkQueue(new UiWorkQueue());
            test = ToolkitTestRunner.runTest(shell::root, SIZE);
            test.runner().styleEngine(shell.themes().engine());
            test.runner().eventRouter().addGlobalHandler(shell);
            shell.bindQuit(test.runner()::quit);
            shell.bindRedraw(() -> test.runner().tuiRunner().dispatch(TickEvent.of(0)));
            pilot().pause(SETTLE);
            editor.requestFocus();
            pilot().pause(SETTLE);
        }

        Pilot pilot() {
            return test.pilot();
        }

        String line(int row) {
            return editor.activeDocument().orElseThrow().text().split("\n", -1)[row];
        }

        void close() {
            test.close();
        }
    }
}
