package dev.omatheusmesmo.vintelli.api.maven;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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

    /** Accepts Maven's short {@code prefix:goal} and full {@code groupId:artifactId:goal} forms. */
    public static boolean isPluginGoal(String goal) {
        if (goal == null) {
            return false;
        }
        String[] segments = goal.split(":", -1);
        return (segments.length == 2 || segments.length == 3)
                && Arrays.stream(segments).allMatch(MavenLifecycle::isCoordinateSegment);
    }

    /**
     * Accepts the tool window's optional module selector, profile list and skip-tests flag.
     * Each option can appear once. Plugin goal lookup uses the module path relative to the
     * project root, as listed in the tree (for example, {@code core/runtime}).
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

    public static Optional<String> moduleSelector(List<String> arguments) {
        for (int index = 0; index + 1 < arguments.size(); index++) {
            if ("-pl".equals(arguments.get(index))) {
                return Optional.of(arguments.get(index + 1));
            }
        }
        return Optional.empty();
    }

    private static boolean isModuleSelector(String selector) {
        return !selector.isBlank() && !selector.startsWith("-") && !selector.contains("..")
                && selector.chars().allMatch(character -> isCoordinateCharacter(character)
                        || character == '/' || character == '\\');
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

    private static boolean isCoordinateSegment(String segment) {
        return !segment.isBlank() && segment.chars().allMatch(MavenLifecycle::isCoordinateCharacter);
    }

    private static boolean isCoordinateCharacter(int character) {
        return Character.isLetterOrDigit(character) || character == '.' || character == '-'
                || character == '_';
    }
}
