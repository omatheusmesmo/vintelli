package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.menu.MenuEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellMenuCatalogTest {

    private static final class FakeHost implements ShellMenuCatalog.Host {

        boolean editorAvailable = true;
        boolean documentOpen;
        boolean dirtyEditorTab;
        int projectFileCount = 3;
        boolean projectSearchAvailable = true;
        String navigationUnavailable = "";
        boolean ideaConnectInProgress;
        String vcsUnavailable = "";
        boolean vcsWriteInProgress;

        @Override
        public boolean editorAvailable() {
            return editorAvailable;
        }

        @Override
        public boolean documentOpen() {
            return documentOpen;
        }

        @Override
        public boolean dirtyEditorTab() {
            return dirtyEditorTab;
        }

        @Override
        public int projectFileCount() {
            return projectFileCount;
        }

        @Override
        public boolean projectSearchAvailable() {
            return projectSearchAvailable;
        }

        @Override
        public String navigationUnavailableReason() {
            return navigationUnavailable;
        }

        @Override
        public List<ToolWindow> toolWindows() {
            return List.of();
        }

        @Override
        public boolean ideaConnectInProgress() {
            return ideaConnectInProgress;
        }

        @Override
        public String vcsUnavailableReason() {
            return vcsUnavailable;
        }

        @Override
        public boolean vcsWriteInProgress() {
            return vcsWriteInProgress;
        }
    }

    private final FakeHost host = new FakeHost();

    private List<MenuEntry> entries() {
        return new ShellMenuCatalog(host).entries();
    }

    private String reasonOf(List<MenuEntry> entries, String actionId) {
        return entries.stream().filter(entry -> entry.actionId().equals(actionId)).findFirst()
                .orElseThrow().unavailableReason();
    }

    @Test
    void editorActionsNeedTheBuiltinEditorThenAnOpenDocument() {
        host.editorAvailable = false;
        assertEquals("Built-in editor unavailable", reasonOf(entries(), KeyActions.OPEN_FILE));
        assertEquals("Built-in editor unavailable", reasonOf(entries(), KeyActions.SAVE));

        host.editorAvailable = true;
        assertEquals("Open a project file first", reasonOf(entries(), KeyActions.SAVE));
        assertEquals("", reasonOf(entries(), KeyActions.OPEN_FILE));

        host.documentOpen = true;
        assertEquals("", reasonOf(entries(), KeyActions.SAVE));
    }

    @Test
    void openSelectedFileNeedsAProjectFile() {
        host.projectFileCount = 0;

        assertEquals("No project files found", reasonOf(entries(), KeyActions.OPEN_FILE));
    }

    @Test
    void searchEntriesReportTheMissingProjectSearch() {
        host.projectSearchAvailable = false;

        assertEquals("Project search unavailable", reasonOf(entries(), KeyActions.GO_TO_FILE));
        assertEquals("Project search unavailable", reasonOf(entries(), KeyActions.FIND_IN_FILES));
    }

    @Test
    void vcsWritesAreBlockedByAGitOperationAndByDirtyTabsForBranches() {
        host.documentOpen = true;
        host.vcsWriteInProgress = true;

        assertEquals("A Git operation is already running", reasonOf(entries(), KeyActions.VCS_COMMIT));

        host.vcsWriteInProgress = false;
        host.dirtyEditorTab = true;

        assertEquals("Save or close dirty editor tabs before switching branches",
                reasonOf(entries(), KeyActions.VCS_SWITCH_BRANCH));
    }

    @Test
    void ideaConnectIsBlockedWhileAConnectionRuns() {
        host.ideaConnectInProgress = true;

        assertEquals("IDEA MCP connection is already in progress", reasonOf(entries(), KeyActions.IDEA_MCP_CONNECT));
    }

    @Test
    void quitIsAlwaysAvailableAndEveryEntryNamesItsMenu() {
        List<MenuEntry> entries = entries();

        assertTrue(entries.stream().anyMatch(entry -> entry.actionId().equals(KeyActions.QUIT) && entry.enabled()));
        assertTrue(entries.stream().allMatch(entry -> !entry.menu().isBlank()));
    }
}
