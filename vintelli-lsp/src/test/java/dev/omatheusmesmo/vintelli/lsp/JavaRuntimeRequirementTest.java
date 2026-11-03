package dev.omatheusmesmo.vintelli.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The runtime check of a profile that sets {@code java.min-version}: where the
 * Java installation is looked up, how its {@code release} file is read and the
 * reason a profile gets when the runtime is too old or cannot be read. The
 * installations are directories holding only the files the check reads.
 */
class JavaRuntimeRequirementTest {

    private static final String PROFILE = "jdtls";

    @TempDir
    Path root;

    @Test
    void aProfileWithoutAMinimumIsNotChecked() {
        assertEquals(Optional.empty(), JavaRuntimeRequirement.failure(PROFILE,
                profile(Optional.empty(), Optional.of(root.resolve("missing").toString())), Map.of()));
    }

    @Test
    void aRuntimeAtOrAboveTheMinimumPasses() throws IOException {
        Path java25 = javaHome("jdk-25", "25.0.2");
        Path java27 = javaHome("jdk-27", "27");

        assertEquals(Optional.empty(), JavaRuntimeRequirement.failure(PROFILE, minimum25(java25), Map.of()));
        assertEquals(Optional.empty(), JavaRuntimeRequirement.failure(PROFILE, minimum25(java27), Map.of()));
    }

    @Test
    void aConfiguredHomeWinsOverJavaHome() throws IOException {
        Path configured = javaHome("jdk-25", "25.0.2");
        Path environment = javaHome("jdk-21", "21.0.5");

        assertEquals(Optional.empty(), JavaRuntimeRequirement.failure(PROFILE, minimum25(configured),
                Map.of("JAVA_HOME", environment.toString())));
    }

    @Test
    void javaHomeIsUsedWhenNoHomeIsConfigured() throws IOException {
        Path environment = javaHome("jdk-24", "24.0.2");

        String reason = failure(profile(Optional.of(25), Optional.empty()),
                Map.of("JAVA_HOME", environment.toString()));
        assertEquals("the profile 'jdtls' needs a Java 25 or newer runtime, found Java 24.0.2 at " + environment
                + " (from JAVA_HOME)", reason);
    }

    @Test
    void javaOnPathIsFollowedThroughItsLinkToTheInstallation() throws IOException {
        Path home = javaHome("jdk-23", "23");
        Path executable = Files.createFile(Files.createDirectories(home.resolve("bin")).resolve(javaName()));
        assumeTrue(executable.toFile().setExecutable(true), "the file system cannot mark files executable");
        Path links = Files.createDirectories(root.resolve("usr-bin"));
        try {
            Files.createSymbolicLink(links.resolve(javaName()), executable);
        } catch (IOException | UnsupportedOperationException unsupported) {
            assumeTrue(false, "the file system cannot create symbolic links: " + unsupported);
        }

        String reason = failure(profile(Optional.of(25), Optional.empty()), Map.of("PATH", links.toString()));
        assertEquals("the profile 'jdtls' needs a Java 25 or newer runtime, found Java 23 at " + home.toRealPath()
                + " (from java on PATH)", reason);
    }

    @Test
    void noJavaAnywhereNamesTheSettingsToFix() {
        String reason = failure(profile(Optional.of(25), Optional.empty()), Map.of("PATH", root.toString()));

        assertEquals("the profile 'jdtls' needs a Java 25 or newer runtime and no Java was found; set "
                + "vintelli.lsp.profiles.jdtls.java.home or JAVA_HOME", reason);
    }

    @Test
    void aMissingInvalidOrAbsentReleaseFileIsAnUndeterminedVersion() throws IOException {
        Path withoutRelease = Files.createDirectories(root.resolve("no-release"));
        Path withoutVersion = Files.createDirectories(root.resolve("no-version"));
        Files.writeString(withoutVersion.resolve("release"), "IMPLEMENTOR=\"Vintelli test\"\n");
        Path invalid = javaHome("invalid", "not-a-version");
        Path absent = root.resolve("absent");

        for (Path home : List.of(withoutRelease, withoutVersion, invalid, absent)) {
            assertEquals("the profile 'jdtls' needs a Java 25 or newer runtime and the Java version at " + home
                    + " (from vintelli.lsp.profiles.jdtls.java.home) cannot be determined: its release file has "
                    + "no valid JAVA_VERSION", failure(minimum25(home), Map.of()), home.toString());
        }
    }

    @Test
    void theLegacySpellingOfJava8IsReadAsJava8() throws IOException {
        Path java8 = javaHome("jdk-8", "1.8.0_402");

        assertEquals(Optional.empty(), JavaRuntimeRequirement.failure(PROFILE,
                profile(Optional.of(8), Optional.of(java8.toString())), Map.of()));
        String reason = failure(profile(Optional.of(9), Optional.of(java8.toString())), Map.of());
        assertTrue(reason.endsWith("needs a Java 9 or newer runtime, found Java 1.8.0_402 at " + java8
                + " (from vintelli.lsp.profiles.jdtls.java.home)"), reason);
    }

    private static String failure(LspProfileConfig.Profile profile, Map<String, String> environment) {
        return JavaRuntimeRequirement.failure(PROFILE, profile, environment)
                .orElseThrow(() -> new AssertionError("the runtime check passed"));
    }

    private static LspProfileConfig.Profile minimum25(Path home) {
        return profile(Optional.of(25), Optional.of(home.toString()));
    }

    private static LspProfileConfig.Profile profile(Optional<Integer> minimum, Optional<String> home) {
        return new StaticProfileConfig.StaticProfile(List.of("jdtls"), Optional.empty(), Optional.empty(), minimum,
                home);
    }

    private Path javaHome(String name, String version) throws IOException {
        Path home = Files.createDirectories(root.resolve(name));
        Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"Vintelli test\"\nJAVA_VERSION=\"" + version
                + "\"\n");
        return home;
    }

    private static String javaName() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows") ? "java.exe" : "java";
    }
}
