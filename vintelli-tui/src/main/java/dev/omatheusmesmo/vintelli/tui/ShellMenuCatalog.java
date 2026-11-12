package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.menu.MenuEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the menu popup entries from the shell state at the moment the menu
 * opens: every entry names its action and carries the reason it is unavailable,
 * an empty reason meaning enabled. The shell answers the questions through its
 * {@link Host}; the catalog holds no TamboUI type.
 */
final class ShellMenuCatalog {

    interface Host {

        boolean editorAvailable();

        boolean documentOpen();

        boolean dirtyEditorTab();

        int projectFileCount();

        boolean projectSearchAvailable();

        String navigationUnavailableReason();

        List<ToolWindow> toolWindows();

        boolean ideaConnectInProgress();

        String vcsUnavailableReason();

        boolean vcsWriteInProgress();
    }

    private final Host host;

    ShellMenuCatalog(Host host) {
        this.host = host;
    }

    List<MenuEntry> entries() {
        List<MenuEntry> entries = new ArrayList<>();
        boolean canEdit = host.documentOpen();
        String editorUnavailable = !host.editorAvailable() ? "Built-in editor unavailable"
                : canEdit ? "" : "Open a project file first";
        addMenu(entries, "File", KeyActions.OPEN_FILE, "Open Selected File",
                !host.editorAvailable() ? "Built-in editor unavailable"
                        : host.projectFileCount() == 0 ? "No project files found" : "");
        addMenu(entries, "File", KeyActions.SAVE, "Save", canEdit ? "" : editorUnavailable);
        addMenu(entries, "File", KeyActions.CLOSE_TAB, "Close Editor Tab", canEdit ? "" : editorUnavailable);
        addMenu(entries, "File", KeyActions.QUIT, "Quit", "");
        addMenu(entries, "Edit", KeyActions.UNDO, "Undo", canEdit ? "" : editorUnavailable);
        addMenu(entries, "Edit", KeyActions.REDO, "Redo", canEdit ? "" : editorUnavailable);
        addMenu(entries, "Edit", KeyActions.CUT, "Cut", canEdit ? "" : editorUnavailable);
        addMenu(entries, "Edit", KeyActions.COPY, "Copy", canEdit ? "" : editorUnavailable);
        addMenu(entries, "Edit", KeyActions.PASTE, "Paste", canEdit ? "" : editorUnavailable);
        addMenu(entries, "Edit", KeyActions.FIND, "Find", canEdit ? "" : editorUnavailable);
        addMenu(entries, "View", KeyActions.PROJECT_WIDER, "Widen Project Panel", "");
        addMenu(entries, "View", KeyActions.PROJECT_NARROWER, "Narrow Project Panel", "");
        addMenu(entries, "View", KeyActions.BOTTOM_TALLER, "Grow Tool Windows", "");
        addMenu(entries, "View", KeyActions.BOTTOM_SHORTER, "Shrink Tool Windows", "");
        addMenu(entries, "View", KeyActions.TOGGLE_IMPORT_FOLD, "Fold or Unfold Imports", canEdit ? "" : editorUnavailable);
        String searchUnavailable = host.projectSearchAvailable() ? "" : "Project search unavailable";
        addMenu(entries, "Navigate", KeyActions.GO_TO_FILE, "Go to File", searchUnavailable);
        addMenu(entries, "Navigate", KeyActions.FIND_IN_FILES, "Find in Files", searchUnavailable);
        String navigationUnavailable = host.navigationUnavailableReason();
        addMenu(entries, "Navigate", KeyActions.DEFINITION, "Go to Definition",
                canEdit ? navigationUnavailable : editorUnavailable);
        addMenu(entries, "Navigate", KeyActions.USAGES, "Find Usages",
                canEdit ? navigationUnavailable : editorUnavailable);
        addMenu(entries, "Code", KeyActions.NEXT_PROBLEM, "Next Problem", canEdit ? "" : editorUnavailable);
        for (ToolWindow window : host.toolWindows()) {
            entries.addAll(window.menuEntries());
        }
        addMenu(entries, "Tools", KeyActions.IDEA_MCP_CONNECT, "Connect / Reconnect IntelliJ IDEA",
                host.ideaConnectInProgress() ? "IDEA MCP connection is already in progress" : "");
        String vcsUnavailable = host.vcsUnavailableReason();
        addMenu(entries, "VCS", KeyActions.VCS_REFRESH, "Refresh", vcsUnavailable);
        addMenu(entries, "VCS", KeyActions.VCS_SHOW_DIFF, "Show Diff", canEdit ? vcsUnavailable : editorUnavailable);
        addMenu(entries, "VCS", KeyActions.VCS_DIFF_WORKING_TREE, "Diff Working Tree", vcsUnavailable);
        String writeUnavailable = host.vcsWriteInProgress() ? "A Git operation is already running" : vcsUnavailable;
        String fileWriteUnavailable = !canEdit ? editorUnavailable : writeUnavailable;
        String branchUnavailable = host.dirtyEditorTab()
                ? "Save or close dirty editor tabs before switching branches" : writeUnavailable;
        addMenu(entries, "VCS", KeyActions.VCS_STAGE, "Stage Active File...", fileWriteUnavailable);
        addMenu(entries, "VCS", KeyActions.VCS_UNSTAGE, "Unstage Active File...", fileWriteUnavailable);
        addMenu(entries, "VCS", KeyActions.VCS_COMMIT, "Commit Staged Changes...", writeUnavailable);
        addMenu(entries, "VCS", KeyActions.VCS_SWITCH_BRANCH, "Switch Branch...", branchUnavailable);
        return List.copyOf(entries);
    }

    private static void addMenu(List<MenuEntry> entries, String menu, String action, String label, String reason) {
        entries.add(new MenuEntry(menu, action, label, reason));
    }
}
