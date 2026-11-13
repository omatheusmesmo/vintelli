package dev.omatheusmesmo.vintelli.tui.maven;

import java.util.ArrayList;
import java.util.List;

import dev.omatheusmesmo.vintelli.api.maven.MavenLifecycle;

/**
 * A lifecycle phase chosen in the tree, the module it runs in (empty for the
 * root project) and the profile ids selected for that module, in pom order.
 */
public record MavenGoal(String phase, String selector, List<String> profiles) {

    public MavenGoal {
        if (phase == null || !MavenLifecycle.isPhase(phase)) {
            throw new IllegalArgumentException("A Maven goal runs a lifecycle phase, not " + phase);
        }
        selector = selector == null ? "" : selector;
        profiles = List.copyOf(profiles);
    }

    public MavenGoal(String phase, String selector) {
        this(phase, selector, List.of());
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
        return List.copyOf(arguments);
    }
}
