package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.omatheusmesmo.vintelli.tui.AcpAgentsToolWindow;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.omatheusmesmo.vintelli.tui.ProblemsToolWindow;
import dev.omatheusmesmo.vintelli.tui.RunToolWindow;
import dev.omatheusmesmo.vintelli.tui.TerminalToolWindow;
import dev.omatheusmesmo.vintelli.tui.ToolWindow;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static dev.omatheusmesmo.vintelli.tui.keymap.KeyActions.KEYMAP_HELP;
import static dev.omatheusmesmo.vintelli.tui.keymap.KeyActions.MOVE_DOWN;
import static dev.omatheusmesmo.vintelli.tui.keymap.KeyActions.MOVE_UP;
import static dev.omatheusmesmo.vintelli.tui.keymap.KeyActions.QUIT;
import static dev.omatheusmesmo.vintelli.tui.keymap.KeyActions.SEARCH_EVERYWHERE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeymapTest {

    private static final List<ToolWindow> WINDOWS = windowList();

    private static List<ToolWindow> windowList() {
        ShellThemes themes = themes();
        return List.of(new RunToolWindow(() -> themes), new TerminalToolWindow(() -> themes), new ProblemsToolWindow(() -> themes),
                new AcpAgentsToolWindow(() -> themes));
    }

    private static ShellThemes themes() {
        return new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
    }

    @Test
    void bindsAnActivationActionPerToolWindowToItsAltChord() {
        Keymap keymap = Keymap.ofProfile(KeymapProfile.DEFAULT, WINDOWS);

        assertTrue(keymap.matches(keypress('p', KeyModifiers.CTRL), SEARCH_EVERYWHERE),
                "Ctrl+P must open the command palette (Search Everywhere stand-in)");
        assertTrue(keymap.matches(KeyEvent.ofKey(KeyCode.F1), KEYMAP_HELP),
                "F1 must open the keymap help overlay");
        assertTrue(keymap.matches(KeyEvent.ofKey(KeyCode.F5), KeyActions.PROJECT_REFRESH),
                "F5 refreshes the project tree");
        assertTrue(keymap.matches(actionChord('1'), KeyActions.ACTIVATE_PROJECT));
        assertTrue(keymap.matches(actionChord('4'), KeyActions.activateToolWindow("run")));
        assertTrue(keymap.matches(actionChord('2'), KeyActions.activateToolWindow("terminal")));
        assertTrue(keymap.matches(actionChord('6'), KeyActions.activateToolWindow("problems")));
        assertTrue(keymap.matches(actionChord('8'), KeyActions.activateToolWindow("acp.agents")));
        assertTrue(keymap.matches(keypress('c', KeyModifiers.CTRL), QUIT));
        assertTrue(keymap.matches(keypress('b', KeyModifiers.CTRL), KeyActions.TOGGLE_BREAKPOINT));
        assertTrue(keymap.matchesReserved(keypress('b', KeyModifiers.CTRL), KeyActions.TOGGLE_BREAKPOINT));
    }

    @Test
    void terminalOpensWithAltF12AndKeepsAltTwoAsASecondBinding() {
        Keymap keymap = Keymap.ofProfile(KeymapProfile.DEFAULT, WINDOWS);

        assertTrue(keymap.matches(KeyEvent.ofKey(KeyCode.F12, KeyModifiers.ALT), KeyActions.activateToolWindow("terminal")),
                "Alt+F12 is the IntelliJ chord of the Terminal tool window");
        assertTrue(keymap.matchesReserved(KeyEvent.ofKey(KeyCode.F12, KeyModifiers.ALT), KeyActions.activateToolWindow("terminal")),
                "Alt+F12 survives a capturing terminal, so the shell can switch away from it");
        assertTrue(keymap.matches(actionChord('2'), KeyActions.activateToolWindow("terminal")),
                "Alt+2 stays as the second binding of the Terminal window");
        assertTrue(keymap.matches(KeyEvent.ofKey(KeyCode.F12), KeyActions.DEFINITION),
                "bare F12 keeps Go to Definition, Alt+F12 is only the Terminal window");
        assertFalse(keymap.matches(KeyEvent.ofKey(KeyCode.F12, KeyModifiers.ALT), KeyActions.DEFINITION));
    }

    @Test
    void oldDigitsNoLongerOpenTheWindowsTheyUsedToOpen() {
        Keymap keymap = Keymap.ofProfile(KeymapProfile.DEFAULT, WINDOWS);

        assertFalse(keymap.matches(actionChord('1'), KeyActions.activateToolWindow("run")),
                "Alt+1 is the Project pane in IntelliJ, not Run");
        assertFalse(keymap.matches(actionChord('3'), KeyActions.activateToolWindow("problems")),
                "Alt+3 is not the Problems chord any more, Alt+6 is");
        assertFalse(keymap.matches(actionChord('4'), KeyActions.activateToolWindow("acp.agents")),
                "Alt+4 is Run, not ACP Agents");
        assertFalse(keymap.matches(KeyEvent.ofChar('1'), KeyActions.activateToolWindow("run")),
                "the bare 1 no longer opens Run");
        assertFalse(keymap.matches(KeyEvent.ofChar('3'), KeyActions.activateToolWindow("problems")),
                "the bare 3 no longer opens Problems");
    }

    @Test
    void paletteShowsTheIntelliJChordOfEachWindowItMirrors() {
        Keymap keymap = Keymap.ofProfile(KeymapProfile.DEFAULT, WINDOWS);

        assertEquals("Alt+4", actionById(keymap, KeyActions.activateToolWindow("run")).intellijShortcut());
        assertEquals("Alt+6", actionById(keymap, KeyActions.activateToolWindow("problems")).intellijShortcut());
        assertEquals("Alt+F12", actionById(keymap, KeyActions.activateToolWindow("terminal")).intellijShortcut());
        assertEquals("", actionById(keymap, KeyActions.activateToolWindow("acp.agents")).intellijShortcut(),
                "ACP Agents has no IntelliJ window, so the palette shows no reference");
        assertEquals("Alt+1", actionById(keymap, KeyActions.ACTIVATE_PROJECT).intellijShortcut());
    }

    @Test
    void bareShortcutsSwitchWhileTheShellHasTheKeyboardAndChordsSurviveCapture() {
        Keymap keymap = Keymap.ofProfile(KeymapProfile.DEFAULT, WINDOWS);

        assertTrue(keymap.matches(KeyEvent.ofChar('8'), KeyActions.activateToolWindow("acp.agents")),
                "the declared shortcut switches the tab while no input has focus");
        assertFalse(keymap.matchesReserved(KeyEvent.ofChar('8'), KeyActions.activateToolWindow("acp.agents")),
                "bare printable characters must never survive a capturing window");
        assertTrue(keymap.matchesReserved(actionChord('8'), KeyActions.activateToolWindow("acp.agents")),
                "the alt chord stays reserved and reaches the shell while an input has focus");
        assertTrue(keymap.matchesReserved(keypress('p', KeyModifiers.CTRL), SEARCH_EVERYWHERE),
                "the palette chord stays reserved and reaches the shell while an input has focus");
        assertTrue(keymap.matchesReserved(KeyEvent.ofKey(KeyCode.F1), KEYMAP_HELP),
                "the help chord stays reserved and reaches the shell while an input has focus");
        assertFalse(keymap.matchesReserved(keypress('c', KeyModifiers.CTRL), QUIT),
                "quit is deliberately not reserved: a capturing terminal keeps Ctrl+C for itself");
    }

    @Test
    void switchingTheProfileChangesTheBindingsWithoutCodeChanges() {
        Keymap intellij = Keymap.ofProfile(KeymapProfile.DEFAULT, WINDOWS);
        Keymap vim = Keymap.ofProfile(KeymapProfile.VIM, WINDOWS);

        assertTrue(vim.matches(keypress('k', KeyModifiers.NONE), MOVE_UP),
                "the vim profile must navigate the project list with k");
        assertTrue(vim.matches(keypress('j', KeyModifiers.NONE), MOVE_DOWN),
                "the vim profile must navigate the project list with j");
        assertTrue(intellij.matches(KeyEvent.ofKey(KeyCode.DOWN), MOVE_DOWN),
                "the intellij profile navigates with the arrow keys");
        assertFalse(intellij.matches(keypress('k', KeyModifiers.NONE), MOVE_UP),
                "the intellij profile must not navigate with bare letters");
        assertFalse(vim.matches(KeyEvent.ofKey(KeyCode.DOWN), MOVE_UP),
                "in the vim profile the arrow keys must not drive the project list up");
        assertTrue(vim.matches(KeyEvent.ofChar('c', KeyModifiers.CTRL), QUIT),
                "every profile keeps quit on Ctrl+C only");
        assertFalse(vim.matches(keypress('q', KeyModifiers.NONE), QUIT),
                "no profile keeps bare q as quit; capturing inputs stay safe");
    }

    @Test
    void catalogListsEveryActionWithADescriptionAndTheIntelliJReference() {
        Keymap keymap = Keymap.ofProfile(KeymapProfile.DEFAULT, WINDOWS);

        Set<String> ids = new LinkedHashSet<>(keymap.actions().stream().map(KeyAction::id).toList());
        // Catalog includes: basic actions, shell actions, and tool window activations
        Set<String> expected = Set.of(QUIT, MOVE_UP, MOVE_DOWN, SEARCH_EVERYWHERE, KEYMAP_HELP,
                "shell_menu",
                "run.debug", "run.execute", "run.refresh", "run.stop", "run.choose-configuration", "terminal.execute",
                "problems.navigate", "problems.refresh", "problems.select-next", "problems.select-previous",
                "problems.filter-all", "problems.filter-errors", "problems.filter-warnings",
                "editor_open", "editor_save", "editor_undo", "editor_redo", "editor_find",
                "editor_cut", "editor_copy", "editor_paste",
                "editor_close", "editor_nextTab", "editor_previousTab", "editor_definition",
                "editor_usages", "editor_nextProblem", "editor_toggleImportFold", "editor_toggleBreakpoint",
                "navigation_goToFile", "navigation_findInFiles",
                "navigation_focusBreadcrumbs",
                "layout_project_wider", "layout_project_narrower", "layout_bottom_taller", "layout_bottom_shorter",
                "shell.theme.switch", "shell.settings", "project_new_file", "project_new_directory", "project_rename",
                "project_delete", "project_refresh", "idea-mcp.connect",
                "project_view_menu", "project_new_menu", "project_select_opened_file", "project_expand_all",
                "project_collapse_all", "project_options_menu", "project_show_hidden", "project_hide",
                "ActivateProjectToolWindow", "ActivateRunToolWindow", "ActivateTerminalToolWindow",
                "ActivateProblemsToolWindow", "ActivateAcpAgentsToolWindow");
        assertEquals(expected, ids);
        KeyAction searchEverywhere = actionById(keymap, SEARCH_EVERYWHERE);
        assertEquals("Double Shift", searchEverywhere.intellijShortcut());
        KeyAction keymapHelp = actionById(keymap, KEYMAP_HELP);
        assertEquals("F1", keymapHelp.intellijShortcut(),
                "the IntelliJ reference of the help overlay has to match the row of docs/keymap.md");
        KeyAction run = actionById(keymap, "ActivateRunToolWindow");
        assertEquals("Alt+4", run.intellijShortcut());
        for (KeyAction action : keymap.actions()) {
            assertFalse(keymap.describeBindings(action.id()).isBlank(),
                    "every shipped action must bind at least one chord: " + action.id());
        }
    }

    @Test
    void userKeymapFileReplacesMentionedActionsAndKeepsTheRest(@TempDir Path tempDir) throws Exception {
        Path userFile = tempDir.resolve("keymap.properties");
        Files.writeString(userFile, """
                # replace one navigation chord
                moveUp = Ctrl+k
                ActivateRunToolWindow = F6
                """);

        Keymap keymap = Keymap.load(KeymapProfile.VIM, WINDOWS, userFile);

        assertTrue(keymap.matches(KeyEvent.ofKey(KeyCode.F6), KeyActions.activateToolWindow("run")));
        assertFalse(keymap.matches(actionChord('4'), KeyActions.activateToolWindow("run")),
                "a mentioned action must lose the profile triggers");
        assertTrue(keymap.matches(keypress('p', KeyModifiers.CTRL), SEARCH_EVERYWHERE),
                "unmentioned actions keep the profile bindings");
        assertTrue(keymap.matches(keypress('k', KeyModifiers.CTRL), MOVE_UP),
                "the user file replaces the triggers of every mentioned action");
        assertFalse(keymap.matches(keypress('k', KeyModifiers.NONE), MOVE_UP));
        assertEquals("vim + user file", keymap.source());
    }

    @Test
    void userKeymapFileWithoutRelevantEntriesKeepsTheProfileAsIs(@TempDir Path tempDir) throws Exception {
        Path userFile = tempDir.resolve("keymap.properties");
        Files.writeString(userFile, "# nothing bound: comments are not actions\n");

        Keymap keymap = Keymap.load(KeymapProfile.DEFAULT, WINDOWS, userFile);

        assertTrue(keymap.matches(actionChord('8'), KeyActions.activateToolWindow("acp.agents")));
        assertEquals("intellij + user file", keymap.source());
    }

    @Test
    void userKeymapFileThatDoesNotExistKeepsTheProfileBindings(@TempDir Path tempDir) {
        Keymap keymap = Keymap.load(KeymapProfile.DEFAULT, WINDOWS,
                tempDir.resolve("absent.properties"));

        assertTrue(keymap.matches(actionChord('8'), KeyActions.activateToolWindow("acp.agents")));
        assertEquals("intellij", keymap.source());
    }

    @Test
    void unknownActionNameInUserKeymapFileFailsFastNamingTheFileAndTheLine(@TempDir Path tempDir) throws Exception {
        Path userFile = tempDir.resolve("keymap.properties");
        Files.writeString(userFile, "# an unrelated entry first\n\nmoveDown = Ctrl+p\nActivateRunToolWidnow = F9\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> Keymap.load(KeymapProfile.DEFAULT, WINDOWS, userFile));

        assertTrue(failure.getMessage().contains("ActivateRunToolWidnow"), failure.getMessage());
        assertTrue(failure.getMessage().contains("(line 4)"), failure.getMessage());
        assertTrue(failure.getMessage().contains(userFile.toString()), failure.getMessage());
    }

    @Test
    void conflictingTriggersInUserKeymapFileFailFast(@TempDir Path tempDir) throws Exception {
        Path userFile = tempDir.resolve("keymap.properties");
        Files.writeString(userFile, "moveDown = Ctrl+p\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> Keymap.load(KeymapProfile.DEFAULT, WINDOWS, userFile));

        assertTrue(failure.getMessage().contains("'moveDown'"), failure.getMessage());
        assertTrue(failure.getMessage().contains(SEARCH_EVERYWHERE), failure.getMessage());
    }

    @Test
    void bareCharacterOnAReservedActionIsRejected(@TempDir Path tempDir) throws Exception {
        Path userFile = tempDir.resolve("keymap.properties");
        Files.writeString(userFile, "ActivateRunToolWindow = 1\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> Keymap.load(KeymapProfile.DEFAULT, WINDOWS, userFile));

        assertTrue(failure.getMessage().contains("ActivateRunToolWindow"), failure.getMessage());
    }

    @Test
    void barePrintableOnThePaletteActionIsRejected(@TempDir Path tempDir) throws Exception {
        Path userFile = tempDir.resolve("keymap.properties");
        Files.writeString(userFile, "SearchEverywhere = p\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> Keymap.load(KeymapProfile.DEFAULT, WINDOWS, userFile));

        assertTrue(failure.getMessage().contains(SEARCH_EVERYWHERE) || failure.getMessage().contains("'p'"),
                failure.getMessage());
    }

    @Test
    void barePrintableCharacterOnQuitIsRejectedNamingTheFileAndTheLine(@TempDir Path tempDir) throws Exception {
        Path userFile = tempDir.resolve("keymap.properties");
        Files.writeString(userFile, "# a comment line\nmoveUp = Ctrl+k\nquit = q\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> Keymap.load(KeymapProfile.DEFAULT, WINDOWS, userFile));

        assertTrue(failure.getMessage().contains("'quit'"), failure.getMessage());
        assertTrue(failure.getMessage().contains(userFile.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains("(line 3)"), failure.getMessage());
    }

    @Test
    void chordOnQuitIsStillAccepted(@TempDir Path tempDir) throws Exception {
        Path userFile = tempDir.resolve("keymap.properties");
        Files.writeString(userFile, "quit = Ctrl+q\n");

        Keymap keymap = Keymap.load(KeymapProfile.DEFAULT, WINDOWS, userFile);

        assertTrue(keymap.matches(keypress('q', KeyModifiers.CTRL), QUIT),
                "a chord on quit stays a legal rebinding, only bare printable characters are rejected");
        assertFalse(keymap.matches(keypress('q', KeyModifiers.NONE), QUIT),
                "the rejected bare q must not have survived as a quit trigger");
    }

    @Test
    void invalidTriggerInUserKeymapFileFailsFastNamingTheFile(@TempDir Path tempDir) throws Exception {
        Path userFile = tempDir.resolve("keymap.properties");
        Files.writeString(userFile, "moveUp = Super+F13\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> Keymap.load(KeymapProfile.DEFAULT, WINDOWS, userFile));

        assertTrue(failure.getMessage().contains(userFile.toString()), failure.getMessage());
    }

    @Test
    void malformedUnicodeEscapeInUserKeymapFileFailsFastNamingTheFileAndTheLine(@TempDir Path tempDir)
            throws Exception {
        Path userFile = tempDir.resolve("keymap.properties");
        Files.writeString(userFile, "# a comment line\nmoveUp = Ctrl+k\nActivateRunToolWindow = \\uZZZZ\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> Keymap.load(KeymapProfile.DEFAULT, WINDOWS, userFile));

        assertTrue(failure.getMessage().contains(userFile.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains("(line 3)"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Malformed"), failure.getMessage());
    }

    @Test
    void malformedUnicodeEscapeAfterAValidOneNamesTheBadLine(@TempDir Path tempDir) throws Exception {
        Path userFile = tempDir.resolve("keymap.properties");
        Files.writeString(userFile, "moveUp = \\u0041\n# a comment line\nActivateRunToolWindow = \\uZZZZ\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> Keymap.load(KeymapProfile.DEFAULT, WINDOWS, userFile));

        assertTrue(failure.getMessage().contains(userFile.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains("(line 3)"), failure.getMessage());
    }

    @Test
    void malformedUnicodeEscapeInACommentIsIgnored(@TempDir Path tempDir) throws Exception {
        Path userFile = tempDir.resolve("keymap.properties");
        Files.writeString(userFile, "# a bad escape \\uZZZZ in a comment\nmoveUp = Ctrl+k\n");

        Keymap keymap = Keymap.load(KeymapProfile.DEFAULT, WINDOWS, userFile);

        assertTrue(keymap.matches(keypress('k', KeyModifiers.CTRL), MOVE_UP),
                "comments are not parsed as bindings, their escapes never reach Properties.load");
    }

    @Test
    void defaultKeymapHonorsTheProfileNameAndTheConfigRoot(@TempDir Path configRoot) throws Exception {
        Files.createDirectories(configRoot.resolve("vintelli"));
        Files.writeString(configRoot.resolve("vintelli/keymap.properties"), "moveUp = Ctrl+k\n");

        Keymap keymap = Keymap.defaultKeymap(WINDOWS, "vim", configRoot);

        assertEquals("vim + user file", keymap.source());
        assertTrue(keymap.matches(keypress('k', KeyModifiers.CTRL), MOVE_UP));
        assertEquals("intellij", Keymap.defaultKeymap(WINDOWS, "intellij", configRoot.resolve("absent")).source());
        assertThrows(IllegalArgumentException.class,
                () -> Keymap.defaultKeymap(WINDOWS, "emacs", configRoot));
    }

    @Test
    void configRootFollowsTheDocumentedFallbackOrder() {
        String absolute = System.getProperty("java.io.tmpdir");
        String home = System.getProperty("user.home");

        assertEquals(Path.of(absolute), ConfigDirectory.configRoot(absolute, absolute + "-xdg", absolute + "-app", home));
        assertEquals(Path.of(absolute), ConfigDirectory.configRoot(null, absolute, absolute + "-app", home));
        assertEquals(Path.of(absolute + "-app"), ConfigDirectory.configRoot(null, "relative-xdg", absolute + "-app", home));
        assertEquals(Path.of(home, ".config"), ConfigDirectory.configRoot(null, null, null, home));
        assertNull(ConfigDirectory.configRoot(null, null, null, null));
    }

    @Test
    void userKeymapFileComesFromTheResolvedConfigRoot() {
        String directory = System.getProperty("java.io.tmpdir");
        Properties system = new Properties();
        system.setProperty(ConfigDirectory.USER_KEYMAP_DIR_PROPERTY, directory);

        Path userKeymapFile = ConfigDirectory.userKeymapFile(Map.of(), system);

        assertEquals(Path.of(directory, "vintelli", "keymap.properties"), userKeymapFile);
    }

    private static KeyAction actionById(Keymap keymap, String id) {
        return keymap.actions().stream()
                .filter(action -> action.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static KeyEvent actionChord(char shortcut) {
        return KeyEvent.ofChar(shortcut, KeyModifiers.ALT);
    }

    private static KeyEvent keypress(char key, KeyModifiers modifiers) {
        return KeyEvent.ofChar(key, modifiers);
    }
}
