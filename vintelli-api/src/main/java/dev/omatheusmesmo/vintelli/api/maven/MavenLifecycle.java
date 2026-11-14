package dev.omatheusmesmo.vintelli.api.maven;

import java.util.List;

/**
 * The closed set of Maven lifecycle phases a run may name, in lifecycle order.
 * Lifecycle and explicitly selected plugin goals reach Maven through the
 * local run capability; arguments remain restricted to the tool-window flags.
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
        boolean moduleSeen = false;
        boolean profilesSeen = false;
        boolean skipTestsSeen = false;
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if ("-pl".equals(argument)) {
                if (moduleSeen || ++index >= arguments.size() || !isModuleSelector(arguments.get(index))) {
                    return false;
                }
                moduleSeen = true;
            } else if (argument.startsWith("-P") && argument.length() > 2 && !profilesSeen
                    && isProfileList(argument.substring(2))) {
                profilesSeen = true;
            } else if ("-DskipTests".equals(argument) && !skipTestsSeen) {
                skipTestsSeen = true;
            } else {
                return false;
            }
        }
        return true;
    }

    private static boolean isModuleSelector(String selector) {
        return !selector.isBlank() && !selector.startsWith("-") && !selector.contains("..")
                && selector.chars().noneMatch(Character::isWhitespace);
    }

    private static boolean isProfileList(String profiles) {
        for (String profile : profiles.split(",", -1)) {
            if (profile.isBlank() || profile.chars().anyMatch(character -> !Character.isLetterOrDigit(character)
                    && character != '-' && character != '_' && character != '.')) {
                return false;
            }
        }
        return true;
    }
}
