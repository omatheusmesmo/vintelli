package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import io.quarkus.arc.All;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CDI view of every {@link ToolWindow} bean exposed by the modules on the
 * classpath. The registry is the only place that owns tab order, so the shell
 * and the inspection endpoints see the same deterministic sequence no matter
 * in which order ArC discovers the beans.
 *
 * @see ToolWindow for the sort, validation and routing contract
 */
@Singleton
public final class ToolWindowRegistry {

    private static final Set<Character> RESERVED_SHORTCUTS = Set.of('j', 'k', 'q');

    private final List<ToolWindow> toolWindows;

    /**
     * CDI construction collects every {@link ToolWindow} bean through ArC
     * {@link All}; the same constructor also serves tests and the offscreen
     * inspection surface with an explicit list. Construction fails fast when
     * two beans share an id or a shortcut, or when a shortcut is a control
     * character or one of the shell reserved keys.
     *
     * @see ToolWindow for the sort and routing contract
     */
    @Inject
    public ToolWindowRegistry(@All List<ToolWindow> toolWindows) {
        this.toolWindows = sortAndValidate(toolWindows);
    }

    /**
     * All registered tool windows in deterministic tab order.
     */
    public List<ToolWindow> toolWindows() {
        return toolWindows;
    }

    private static List<ToolWindow> sortAndValidate(List<ToolWindow> candidates) {
        List<ToolWindow> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingInt(ToolWindow::ordinal).thenComparing(ToolWindow::id));
        ensureUniqueIds(sorted);
        ensureUniqueActivationActions(sorted);
        ensureValidShortcuts(sorted);
        return List.copyOf(sorted);
    }

    private static void ensureUniqueIds(List<ToolWindow> sorted) {
        Map<String, ToolWindow> registered = new HashMap<>();
        for (ToolWindow toolWindow : sorted) {
            ToolWindow previous = registered.putIfAbsent(toolWindow.id(), toolWindow);
            if (previous != null) {
                throw new IllegalStateException("duplicate tool window id '" + toolWindow.id()
                        + "' implemented by " + previous.getClass().getSimpleName()
                        + " and " + toolWindow.getClass().getSimpleName());
            }
        }
    }

    /**
     * The keymap derives one activation action id per window, and ids such as
     * {@code a-b} and {@code a.b} both derive into the same name; a collision
     * would silently shadow an action of the palette, so it fails fast here.
     */
    private static void ensureUniqueActivationActions(List<ToolWindow> sorted) {
        Map<String, ToolWindow> registered = new HashMap<>();
        for (ToolWindow toolWindow : sorted) {
            String actionId = KeyActions.activateToolWindow(toolWindow.id());
            ToolWindow previous = registered.putIfAbsent(actionId, toolWindow);
            if (previous != null) {
                throw new IllegalStateException("tool window ids '" + previous.id() + "' and '" + toolWindow.id()
                        + "' both derive the activation action '" + actionId + "'");
            }
        }
    }

    private static void ensureValidShortcuts(List<ToolWindow> sorted) {
        Set<Character> registered = new HashSet<>();
        for (ToolWindow toolWindow : sorted) {
            char shortcut = toolWindow.shortcut();
            if (Character.isISOControl(shortcut)) {
                throw new IllegalStateException("tool window '" + toolWindow.id()
                        + "' declares a control character as its shortcut");
            }
            if (RESERVED_SHORTCUTS.contains(shortcut)) {
                throw new IllegalStateException("tool window '" + toolWindow.id()
                        + "' declares shortcut '" + shortcut + "', reserved for shell navigation");
            }
            if (!registered.add(shortcut)) {
                throw new IllegalStateException("tool window '" + toolWindow.id()
                        + "' declares shortcut '" + shortcut + "', already taken by another tool window");
            }
        }
    }
}
