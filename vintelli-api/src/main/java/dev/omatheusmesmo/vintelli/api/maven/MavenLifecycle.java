package dev.omatheusmesmo.vintelli.api.maven;

import java.util.List;

/**
 * The closed set of Maven lifecycle phases a run may name, in lifecycle order.
 * Only these names reach a Maven process from a run request: a plugin goal
 * such as {@code exec:exec} is never accepted, so a remote caller cannot turn
 * a run into an arbitrary plugin execution.
 */
public final class MavenLifecycle {

    public static final String GOAL_PREFIX = "maven:";

    public static final List<String> PHASES = List.of(
            "clean",
            "validate", "initialize", "generate-sources", "process-sources", "generate-resources",
            "process-resources", "compile", "process-classes", "generate-test-sources",
            "process-test-sources", "generate-test-resources", "process-test-resources", "test-compile",
            "process-test-classes", "test", "prepare-package", "package", "pre-integration-test",
            "integration-test", "post-integration-test", "verify", "install", "deploy",
            "site");

    private MavenLifecycle() {
    }

    public static boolean isPhase(String phase) {
        return PHASES.contains(phase);
    }

    /**
     * Accepts no arguments, or exactly {@code -pl <module>} with a relative
     * module selector: a path or an {@code artifactId} without whitespace,
     * a leading dash or a parent segment.
     */
    public static boolean acceptsArguments(List<String> arguments) {
        if (arguments.isEmpty()) {
            return true;
        }
        return arguments.size() == 2 && "-pl".equals(arguments.get(0)) && isModuleSelector(arguments.get(1));
    }

    private static boolean isModuleSelector(String selector) {
        return !selector.isBlank() && !selector.startsWith("-") && !selector.contains("..")
                && selector.chars().noneMatch(Character::isWhitespace);
    }
}
