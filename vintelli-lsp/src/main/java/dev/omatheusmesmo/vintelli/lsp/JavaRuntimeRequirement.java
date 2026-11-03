package dev.omatheusmesmo.vintelli.lsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Java runtime a profile asks for with {@code java.min-version}. The
 * installation is looked up in the order the jdt.ls launcher uses
 * ({@code java.home}, then {@code JAVA_HOME}, then {@code java} on
 * {@code PATH}) and its version is read from the {@code release} file at its
 * root, so the check starts no process. It still reads files, so the client
 * runs it when it is created and before a launch, and health reads only see
 * the last result.
 *
 * <p>jdt.ls 1.63.0 exits with code 13 on Java 21 to 24 although its launcher
 * accepts any Java 21 or newer (issue #100), so the client refuses the profile
 * before the launch instead.
 */
final class JavaRuntimeRequirement {

    private static final Pattern JAVA_VERSION = Pattern.compile("^JAVA_VERSION=\"([^\"]*)\"", Pattern.MULTILINE);
    private static final Pattern FEATURE = Pattern.compile("^(\\d+)(?:\\.(\\d+))?");

    private JavaRuntimeRequirement() {
    }

    /**
     * @return the reason the profile cannot run, empty when the profile sets no
     *         minimum or the runtime it finds meets it
     */
    static Optional<String> failure(String profile, LspProfileConfig.Profile settings,
            Map<String, String> environment) {
        if (settings.javaMinVersion().isEmpty()) {
            return Optional.empty();
        }
        int minimum = settings.javaMinVersion().get();
        Optional<Installation> found = locate(profile, settings, environment);
        if (found.isEmpty()) {
            return Optional.of("the profile '" + profile + "' needs a Java " + minimum
                    + " or newer runtime and no Java was found; set vintelli.lsp.profiles." + profile
                    + ".java.home or JAVA_HOME");
        }
        Installation java = found.get();
        Optional<String> version = versionOf(java.home());
        Optional<Integer> feature = version.flatMap(JavaRuntimeRequirement::featureOf);
        if (feature.isEmpty()) {
            return Optional.of("the profile '" + profile + "' needs a Java " + minimum
                    + " or newer runtime and the Java version at " + java.home() + " (" + java.source()
                    + ") cannot be determined: its release file has no valid JAVA_VERSION");
        }
        if (feature.get() < minimum) {
            return Optional.of("the profile '" + profile + "' needs a Java " + minimum
                    + " or newer runtime, found Java " + version.get() + " at " + java.home() + " ("
                    + java.source() + ")");
        }
        return Optional.empty();
    }

    private static Optional<Installation> locate(String profile, LspProfileConfig.Profile settings,
            Map<String, String> environment) {
        String configured = settings.javaHome().orElse("");
        if (!configured.isBlank()) {
            return Optional.of(new Installation(Path.of(configured.trim()),
                    "from vintelli.lsp.profiles." + profile + ".java.home"));
        }
        String javaHome = environment.getOrDefault("JAVA_HOME", "");
        if (!javaHome.isBlank()) {
            return Optional.of(new Installation(Path.of(javaHome.trim()), "from JAVA_HOME"));
        }
        return LspClient.resolve(javaExecutableName(), environment.get("PATH"))
                .flatMap(JavaRuntimeRequirement::homeOf)
                .map(home -> new Installation(home, "from java on PATH"));
    }

    /**
     * {@code java} on {@code PATH} is often a link, for example
     * {@code /usr/bin/java} through the alternatives system, so the link is
     * followed to {@code <home>/bin/java} before the home is taken.
     */
    private static Optional<Path> homeOf(Path executable) {
        try {
            Path bin = executable.toRealPath().getParent();
            return Optional.ofNullable(bin == null ? null : bin.getParent());
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    private static Optional<String> versionOf(Path home) {
        try {
            Matcher matcher = JAVA_VERSION.matcher(Files.readString(home.resolve("release")));
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Reads the feature release, also from the {@code 1.8.0_402} spelling of
     * Java 8 and older.
     */
    private static Optional<Integer> featureOf(String version) {
        Matcher matcher = FEATURE.matcher(version);
        if (!matcher.find()) {
            return Optional.empty();
        }
        int first = Integer.parseInt(matcher.group(1));
        if (first == 1 && matcher.group(2) != null) {
            return Optional.of(Integer.parseInt(matcher.group(2)));
        }
        return Optional.of(first);
    }

    private static String javaExecutableName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows") ? "java.exe" : "java";
    }

    private record Installation(Path home, String source) {
    }
}
