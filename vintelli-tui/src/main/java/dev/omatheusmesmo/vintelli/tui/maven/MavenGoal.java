package dev.omatheusmesmo.vintelli.tui.maven;

import java.util.List;

import dev.omatheusmesmo.vintelli.api.maven.MavenLifecycle;

/** A lifecycle phase chosen in the tree, and the module it runs in (empty for the root project). */
public record MavenGoal(String phase, String selector) {

    public MavenGoal {
        if (phase == null || !MavenLifecycle.isPhase(phase)) {
            throw new IllegalArgumentException("A Maven goal runs a lifecycle phase, not " + phase);
        }
        selector = selector == null ? "" : selector;
    }

    public String identifier() {
        return MavenLifecycle.GOAL_PREFIX + phase;
    }

    public List<String> arguments() {
        return selector.isEmpty() ? List.of() : List.of("-pl", selector);
    }
}
