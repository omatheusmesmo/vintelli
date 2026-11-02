package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.omatheusmesmo.vintelli.tui.ToolWindow;
import dev.tamboui.tui.bindings.Actions;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Action ids the TUI shell answers to. Shell navigation reuses the TamboUI
 * standard action names ({@code moveUp}, {@code moveDown}, {@code quit}); the
 * IntelliJ-like features use the IntelliJ action ids ({@code SearchEverywhere}
 * stands in for the Double Shift chord, {@code Activate<Name>ToolWindow} for
 * the {@code Alt+<n>} tool window switching), and {@code KeymapHelp} is the
 * Vintelli-specific id for the help overlay.
 */
public final class KeyActions {

    public static final String QUIT = Actions.QUIT;
    public static final String MOVE_UP = Actions.MOVE_UP;
    public static final String MOVE_DOWN = Actions.MOVE_DOWN;
    public static final String SEARCH_EVERYWHERE = "SearchEverywhere";
    public static final String KEYMAP_HELP = "KeymapHelp";
    public static final String MENU = "shell_menu";
    public static final String OPEN_FILE = "editor_open";
    public static final String SAVE = "editor_save";
    public static final String UNDO = "editor_undo";
    public static final String REDO = "editor_redo";
    public static final String FIND = "editor_find";
    public static final String CLOSE_TAB = "editor_close";
    public static final String NEXT_TAB = "editor_nextTab";
    public static final String PREVIOUS_TAB = "editor_previousTab";
    public static final String DEFINITION = "editor_definition";
    public static final String USAGES = "editor_usages";
    public static final String NEXT_PROBLEM = "editor_nextProblem";
    public static final String TOGGLE_IMPORT_FOLD = "editor_toggleImportFold";
    public static final String GO_TO_FILE = "navigation_goToFile";
    public static final String FIND_IN_FILES = "navigation_findInFiles";
    public static final String PROJECT_WIDER = "layout_project_wider";
    public static final String PROJECT_NARROWER = "layout_project_narrower";
    public static final String BOTTOM_TALLER = "layout_bottom_taller";
    public static final String BOTTOM_SHORTER = "layout_bottom_shorter";
    public static final String SWITCH_THEME = "shell.theme.switch";
    public static final String THEME_SWITCH = SWITCH_THEME;
    public static final String SWITCH_SHELL_THEME = SWITCH_THEME;
    public static final String SHELL_THEME_SWITCH = SWITCH_THEME;
    public static final String PROJECT_NEW_FILE = "project_new_file";
    public static final String PROJECT_NEW_DIRECTORY = "project_new_directory";
    public static final String PROJECT_RENAME = "project_rename";
    public static final String PROJECT_DELETE = "project_delete";
    public static final String PROJECT_REFRESH = "project_refresh";
        public static final String TOGGLE_TOOL_WINDOW = "ToggleToolWindow";
    public static final String VCS_REFRESH = "vcs_refresh";
    public static final String VCS_SHOW_DIFF = "vcs_showDiff";
    public static final String VCS_DIFF_WORKING_TREE = "vcs_diffWorkingTree";
    public static final String VCS_STAGE = "vcs_stage";
    public static final String VCS_UNSTAGE = "vcs_unstage";
    public static final String VCS_COMMIT = "vcs_commit";
    public static final String VCS_SWITCH_BRANCH = "vcs_switchBranch";
    public static final String RUN_EXECUTE = "run.execute";
    public static final String RUN_STOP = "run.stop";
    public static final String RUN_REFRESH = "run.refresh";
    public static final String RUN_SELECT_NEXT = "run.select-next";
    public static final String RUN_SELECT_PREVIOUS = "run.select-previous";
    public static final String TERMINAL_EXECUTE = "terminal.execute";

    public static List<KeyAction> shellActions() {
        return List.of(new KeyAction(MENU, "Open Menu", ""),
                new KeyAction(OPEN_FILE, "Open Selected File", ""),
                new KeyAction(SAVE, "Save File", ""), new KeyAction(UNDO, "Undo", ""),
                new KeyAction(REDO, "Redo", ""), new KeyAction(FIND, "Find in Editor", ""),
                new KeyAction(CLOSE_TAB, "Close Editor Tab", ""),
                new KeyAction(NEXT_TAB, "Next Editor Tab", ""),
                new KeyAction(PREVIOUS_TAB, "Previous Editor Tab", ""),
                new KeyAction(DEFINITION, "Go to Definition", ""),
                new KeyAction(USAGES, "Find Usages", ""),
                new KeyAction(NEXT_PROBLEM, "Next Problem", ""),
                new KeyAction(TOGGLE_IMPORT_FOLD, "Fold or Unfold Imports", ""),
                new KeyAction(GO_TO_FILE, "Go to File", ""),
                new KeyAction(FIND_IN_FILES, "Find in Files", ""),
                new KeyAction(PROJECT_WIDER, "Widen Project Panel", ""),
                new KeyAction(PROJECT_NARROWER, "Narrow Project Panel", ""),
                new KeyAction(BOTTOM_TALLER, "Grow Tool Window", ""),
                new KeyAction(BOTTOM_SHORTER, "Shrink Tool Window", ""),
                new KeyAction(SWITCH_THEME, "Switch Shell Theme", ""),
                new KeyAction(RUN_EXECUTE, "Run Selected Configuration", ""),
                new KeyAction(RUN_REFRESH, "Refresh Run Configurations", ""),
                new KeyAction(RUN_STOP, "Stop Current Run", ""),
                new KeyAction(TERMINAL_EXECUTE, "Execute Terminal Command", ""));
    }

    public static List<KeyAction> projectActions() {
        return List.of(new KeyAction(PROJECT_NEW_FILE, "New Project File", ""),
                new KeyAction(PROJECT_NEW_DIRECTORY, "New Project Directory", ""),
                new KeyAction(PROJECT_RENAME, "Rename Project Entry", ""),
                new KeyAction(PROJECT_DELETE, "Delete Project Entry", ""),
                new KeyAction(PROJECT_REFRESH, "Refresh Project Tree", ""));
    }

    private KeyActions() {
    }

    /**
     * Action id of the activation action for one tool window, derived from the
     * stable window id: {@code run} becomes {@code ActivateRunToolWindow} and
     * {@code acp.agents} becomes {@code ActivateAcpAgentsToolWindow}. The keymap
     * binds the triggers and the shell resolves the dispatching from the same
     * derivation, so both stay in sync when a tool window is added.
     */
    public static String activateToolWindow(String toolWindowId) {
        return "Activate" + pascalCase(toolWindowId) + "ToolWindow";
    }

    public static String toggleToolWindow(String toolWindowId) {
        return "Toggle" + pascalCase(toolWindowId) + "ToolWindow";
    }

    /**
     * The ids of the actions whose shell chords survive a capturing window:
     * the palette, the help overlay and every tool window activation. The
     * keymap keeps a second binding set with exactly these actions, their
     * chords only, so the shell can allow them while an input has focus.
     */
    public static Set<String> reservedActionIds(List<ToolWindow> toolWindows) {
        Set<String> reserved = new LinkedHashSet<>(List.of(SEARCH_EVERYWHERE, KEYMAP_HELP));
        shellActions().stream().map(KeyAction::id).filter(id -> !OPEN_FILE.equals(id)).forEach(reserved::add);
        for (ToolWindow toolWindow : toolWindows) {
            reserved.add(activateToolWindow(toolWindow.id()));
            reserved.add(toggleToolWindow(toolWindow.id()));
        }
        return reserved;
    }

    private static String pascalCase(String value) {
        StringBuilder id = new StringBuilder();
        for (String token : value.split("[-._]")) {
            if (token.isEmpty()) {
                continue;
            }
            id.append(Character.toUpperCase(token.charAt(0))).append(token.substring(1));
        }
        return id.toString();
    }
}
