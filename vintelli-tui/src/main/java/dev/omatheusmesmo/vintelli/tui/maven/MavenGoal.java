package dev.omatheusmesmo.vintelli.tui.maven;

import java.util.ArrayList;
import java.util.List;

import dev.omatheusmesmo.vintelli.api.maven.MavenLifecycle;

/**
 * A lifecycle phase or plugin goal chosen in the tree, the module it runs in
 * (empty for the root project) and the profile ids selected for that module.
 */
public record MavenGoal(String phase, String selector, List<String> profiles, boolean skipTests) {

    public MavenGoal {
        if (phase == null || !isGoal(phase)) {
            throw new IllegalArgumentException("Invalid Maven goal " + phase);
        }
        selector = selector == null ? "" : selector;
        profiles = List.copyOf(profiles);
    }

    public MavenGoal(String phase, String selector) {
        this(phase, selector, List.of(), false);
    }

    public MavenGoal(String phase, String selector, List<String> profiles) {
        this(phase, selector, profiles, false);
    }

    public String identifier() {
        return MavenLifecycle.GOAL_PREFIX + phase;
    }

    public List<String> arguments() {
        List<String> arguments = new ArrayList<>();
        if (!selector.isEmpty()) {
            arguments.add("-pl");
            arguments.add(selector);
        }
        if (!profiles.isEmpty()) {
            arguments.add("-P" + String.join(",", profiles));
        }
        if (skipTests) {
            arguments.add("-DskipTests");
        }
        return List.copyOf(arguments);
    }

    private static boolean isGoal(String goal) {
        if (MavenLifecycle.isPhase(goal)) {
            return true;
        }
        int separator = goal.indexOf(':');
        return separator > 0 && separator < goal.length() - 1 && goal.indexOf(':', separator + 1) < 0
                && goal.chars().noneMatch(Character::isWhitespace) && !goal.startsWith("-");
    }
}
