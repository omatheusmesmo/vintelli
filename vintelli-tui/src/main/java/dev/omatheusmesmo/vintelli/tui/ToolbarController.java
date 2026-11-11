package dev.omatheusmesmo.vintelli.tui;

import java.util.function.Supplier;

import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;

/**
 * Owns what the main toolbar shows and how its clicks are routed: the project
 * label, the branch read from the shell, and the run configuration and the Run
 * slot of the Run window. Every button that has a keyboard route dispatches the
 * same named action as that route, so a click and a shortcut reach the same
 * behaviour. The Debug button is drawn disabled and dispatches nothing. The
 * menu popup, the Run window focus and the action dispatch stay in the shell,
 * reached through {@link Host}. It holds no TamboUI type: the
 * {@link MainToolbar} view draws the model this controller builds.
 */
final class ToolbarController {

    /** What the toolbar reads from the shell and what a click on it changes there. */
    interface Host {

        boolean menuOpen();

        int menuIndex();

        void showMenu(int index);

        void closeMenu();

        void refreshBranch();

        void focusRunWindow();

        void dispatch(String action);
    }

    private final String project;
    private final RunToolWindow run;
    private final Supplier<String> branch;
    private final Host host;

    ToolbarController(String project, RunToolWindow run, Supplier<String> branch, Host host) {
        this.project = project;
        this.run = run;
        this.branch = branch;
        this.host = host;
    }

    MainToolbar.Model model() {
        boolean menuOpen = host.menuOpen();
        return new MainToolbar.Model(project, branch.get(),
                run == null ? null : run.selectedConfigurationLabel(), run != null && run.running(),
                menuOpen, menuOpen ? host.menuIndex() : -1);
    }

    /**
     * Routes a click on a toolbar segment. While the menu is open only the menu
     * button and the menu labels take the click, so other segments return false
     * and the click falls through to the open menu.
     *
     * @return true when the click was taken by the toolbar
     */
    boolean click(MainToolbar.Segment segment) {
        MainToolbar.Kind kind = segment.kind();
        boolean menuOpen = host.menuOpen();
        if (menuOpen && kind != MainToolbar.Kind.MENU_BUTTON && kind != MainToolbar.Kind.MENU) {
            return false;
        }
        switch (kind) {
            case MENU_BUTTON -> {
                if (menuOpen) {
                    host.closeMenu();
                } else {
                    host.showMenu(0);
                }
            }
            case MENU -> host.showMenu(segment.menuIndex());
            case BRANCH -> host.refreshBranch();
            case CONFIGURATION -> {
                if (run != null) {
                    host.dispatch(KeyActions.RUN_CHOOSE_CONFIGURATION);
                }
            }
            case RUN -> dispatchRunAction(KeyActions.RUN_EXECUTE);
            case RUN_STOP -> {
                if (run != null && run.canStop()) {
                    dispatchRunAction(KeyActions.RUN_STOP);
                }
            }
            case MORE -> host.dispatch(KeyActions.MENU);
            case SEARCH -> host.dispatch(KeyActions.FIND_IN_FILES);
            case SETTINGS -> host.dispatch(KeyActions.SHELL_SETTINGS);
            case PROJECT, DEBUG -> {
            }
        }
        return true;
    }

    private void dispatchRunAction(String action) {
        if (run == null) {
            return;
        }
        host.focusRunWindow();
        host.dispatch(action);
    }
}
