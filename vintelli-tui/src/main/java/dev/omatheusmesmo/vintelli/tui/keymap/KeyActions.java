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

    /**
     * The ids of the actions whose shell chords survive a capturing window:
     * the palette, the help overlay and every tool window activation. The
     * keymap keeps a second binding set with exactly these actions, their
     * chords only, so the shell can allow them while an input has focus.
     */
    public static Set<String> reservedActionIds(List<ToolWindow> toolWindows) {
        Set<String> reserved = new LinkedHashSet<>(List.of(SEARCH_EVERYWHERE, KEYMAP_HELP));
        for (ToolWindow toolWindow : toolWindows) {
            reserved.add(activateToolWindow(toolWindow.id()));
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
