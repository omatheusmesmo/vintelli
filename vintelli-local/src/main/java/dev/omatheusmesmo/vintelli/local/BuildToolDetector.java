package dev.omatheusmesmo.vintelli.local;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Detection of the build tool of the {@link LocalProjectRoot}, resolved once
 * at construction so the health read of the run provider never performs I O.
 * A project with a Maven build uses the {@code mvnw} wrapper when it is
 * executable, otherwise the {@code mvn} command; a Gradle project uses
 * {@code gradlew} or falls back to {@code gradle}, mirroring how a developer
 * runs the project from a terminal.
 *
 * <p>When neither tool is detectable the provider still runs: the
 * configuration list stays empty and an unknown configuration name fails the
 * start with an explicit reason instead of guessing.
 */
final class BuildToolDetector {

    enum BuildTool {
        MAVEN,
        GRADLE,
        NONE
    }

    private final Path root;
    private final BuildTool tool;
    private final boolean usesWrapper;
    private final List<String> effectiveCommand;

    BuildToolDetector(LocalProjectRoot projectRoot) {
        this.root = projectRoot.canonical();
        BuildTool detected = BuildTool.NONE;
        boolean wrapper = false;
        List<String> command = List.of();
        if (isMavenProject()) {
            detected = BuildTool.MAVEN;
            Optional<List<String>> resolved = wrapperCommand("mvnw", "mvnw.cmd", List.of("mvn"));
            if (resolved.isPresent()) {
                wrapper = true;
                command = resolved.get();
            } else {
                command = List.of("mvn");
            }
        } else if (isGradleProject()) {
            detected = BuildTool.GRADLE;
            Optional<List<String>> resolved = wrapperCommand("gradlew", "gradlew.bat", List.of("gradle"));
            if (resolved.isPresent()) {
                wrapper = true;
                command = resolved.get();
            } else {
                command = List.of("gradle");
            }
        }
        this.tool = detected;
        this.usesWrapper = wrapper;
        this.effectiveCommand = command;
    }

    BuildTool detectedTool() {
        return tool;
    }

    /**
     * Short program label for summaries and the status bar provenance, for
     * example {@code ./mvnw} or {@code gradle}.
     */
    String programLabel() {
        return switch (tool) {
            case MAVEN -> usesWrapper ? "./mvnw" : "mvn";
            case GRADLE -> usesWrapper ? "./gradlew" : "gradle";
            case NONE -> "";
        };
    }

    /**
     * @return resolved program invocation without a goal, for example
     *         {@code [path/to/mvnw]} on Unix or {@code [cmd, /c, mvnw.cmd]} on
     *         Windows, or a wrapper-less fallback like {@code [mvn]}
     */
    List<String> baseCommand() {
        return effectiveCommand;
    }

    boolean wrapperResolved() {
        return usesWrapper;
    }

    /**
     * Common lifecycle goals and tasks of the detected tool, sorted by build
     * frequency, as run configuration identifiers of the form
     * {@code maven:clean}.
     */
    List<RunConfigurationSpecification> specifications() {
        return switch (tool) {
            case MAVEN -> lifecycle("maven", List.of("clean", "compile", "test", "package", "verify", "install"));
            case GRADLE -> lifecycle("gradle", List.of("clean", "build", "check", "test", "assemble"));
            case NONE -> List.of();
        };
    }

    record RunConfigurationSpecification(String identifier, List<String> command) {

        public RunConfigurationSpecification {
            if (identifier == null || identifier.isBlank()) {
                throw new IllegalArgumentException("A run configuration specification always has an identifier");
            }
            command = List.copyOf(command);
        }
    }

    private List<RunConfigurationSpecification> lifecycle(String toolLabel, List<String> goals) {
        List<RunConfigurationSpecification> built = new ArrayList<>(goals.size());
        for (String goal : goals) {
            List<String> command = new ArrayList<>(effectiveCommand);
            command.add(goal);
            built.add(new RunConfigurationSpecification(toolLabel + ":" + goal, List.copyOf(command)));
        }
        return List.copyOf(built);
    }

    /**
     * Host appropriate wrapper selection: consumers on Unix get the directly
     * executable wrapper script, consumers on Windows get the batch file
     * driven through {@code cmd /c}, because a batch file cannot be launched
     * as a process image directly.
     */
    private Optional<List<String>> wrapperCommand(String unixWrapper, String windowsWrapper, List<String> fallback) {
        boolean windows = isWindows();
        Path wrapperFile = root.resolve(windows ? windowsWrapper : unixWrapper);
        if (executable(wrapperFile)) {
            return Optional.of(commandLineFor(windows, wrapperFile));
        }
        if (windows) {
            Path scriptWrapper = root.resolve(unixWrapper);
            if (executable(scriptWrapper)) {
                return Optional.of(commandLineFor(true, scriptWrapper));
            }
        }
        return Optional.empty();
    }

    private static List<String> commandLineFor(boolean windows, Path wrapperFile) {
        if (windows) {
            return List.of("cmd", "/c", wrapperFile.toAbsolutePath().toString());
        }
        return List.of(wrapperFile.toAbsolutePath().toString());
    }

    private boolean executable(Path candidate) {
        if (!Files.isRegularFile(candidate)) {
            return false;
        }
        return isWindows() || Files.isExecutable(candidate);
    }

    /**
     * On Windows the wrapper script never carries an executable bit, and the
     * batch file is executable whenever it exists, so the file existence
     * already answers.
     */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private boolean isMavenProject() {
        return exists("pom.xml");
    }

    private boolean isGradleProject() {
        return exists("build.gradle") || exists("build.gradle.kts") || exists("settings.gradle")
                || exists("settings.gradle.kts");
    }

    private boolean exists(String fileName) {
        return Files.exists(root.resolve(fileName));
    }
}
