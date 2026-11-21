package dev.omatheusmesmo.vintelli.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.api.maven.MavenLifecycle;

/**
 * The run provider drives real short builds through real wrapper scripts
 * created inside a temporary project root, so configuration listing, stream
 * delivery, cancellation and the failure reasons are all exercised without a
 * build tool installed on the runner. Every assertion holds on Linux, macOS
 * and Windows.
 */
class LocalRunCapabilityTest {

    private static final Duration GENEROUS = Duration.ofMinutes(5);

    @TempDir
    Path temp;

    @Test
    void listsMavenLifecycleGoalsAsRunConfigurationsWithTheWrapper() throws IOException {
        mavenProject("quick");

        LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);
        List<RunCapability.RunConfiguration> configurations = assertOk(capability.listConfigurations());

        assertEquals(List.of("maven:clean", "maven:compile", "maven:test",
                "maven:package", "maven:verify", "maven:install"),
                configurations.stream().map(RunCapability.RunConfiguration::identifier).toList());
        assertTrue(configurations.getFirst().summary().contains("mvnw"));
        assertEquals("local process runner via ./mvnw", capability.provenance());
    }

    @Test
    void listsGradleTasksAndFallsBackToTheSystemCommandWithoutAWrapper() {
        gradleProjectWithoutWrapper();

        LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);
        List<RunCapability.RunConfiguration> configurations = assertOk(capability.listConfigurations());

        assertEquals(List.of("gradle:clean", "gradle:build", "gradle:check", "gradle:test", "gradle:assemble"),
                configurations.stream().map(RunCapability.RunConfiguration::identifier).toList());
        assertEquals("local process runner via gradle", capability.provenance());
    }

    @Test
    void staysAvailableWithoutABuildToolAndListsNoConfiguration() {
        LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);

        assertTrue(capability.health().isAvailable());
        assertEquals("local process runner, no build tool detected", capability.provenance());
        assertEquals(List.of(), assertOk(capability.listConfigurations()));

        var result = capability.start(new RunCapability.RunRequest("maven:test", temp, Map.of()), line -> {
        });
        assertTrue(result.isFailure());
    }

    @Test
    void runsADetectedGoalAndStreamsItsLinesIntoTheCallerBuffer() {
        mavenProject("quick");

        LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);
        List<String> observed = new CopyOnWriteArrayList<>();

        assertOk(capability.start(new RunCapability.RunRequest("maven:clean", temp, Map.of()), observed::add));

        TestProcesses.awaitUntil(GENEROUS, () -> observed.contains("goal-finished"));
        awaitSummary(observed);
        assertTrue(observed.contains("mvnw-wrapper-called-quick"), observed.toString());
    }

    @Test
    void endsTheStreamWithAnExitSummaryAfterTheOutputOfAFinishedGoal() {
        mavenProject("quick");

        LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);
        List<String> observed = new CopyOnWriteArrayList<>();

        assertOk(capability.start(new RunCapability.RunRequest("maven:clean", temp, Map.of()), observed::add));

        awaitSummary(observed);
        assertEquals("Run finished with exit code 0", observed.getLast(), observed.toString());
        assertTrue(observed.indexOf("goal-finished") < observed.indexOf("Run finished with exit code 0"));
    }

    @Test
    void endsTheStreamWithATimeoutSummaryWhenTheGoalOutlivesItsTimeout() {
        mavenProject("cancel");

        LocalRunCapability capability = LocalRunCapability.of(root(), Duration.ofSeconds(3));
        List<String> observed = new CopyOnWriteArrayList<>();

        assertOk(capability.start(new RunCapability.RunRequest("maven:clean", temp, Map.of()), observed::add));

        awaitSummary(observed);
        assertTrue(observed.contains("goal-pumping"), observed.toString());
        assertEquals("Run timed out", observed.getLast(), observed.toString());
    }

    @Test
    void stopsAStartedGoalWhenTheCallerCancelsTheRunHandle() {
        mavenProject("cancel");

        LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);
        List<String> observed = new CopyOnWriteArrayList<>();

        RunCapability.RunHandle handle = assertOk(
                capability.start(new RunCapability.RunRequest("maven:clean", temp, Map.of()), observed::add));

        TestProcesses.awaitUntil(GENEROUS, () -> observed.contains("goal-pumping"));
        handle.cancel();
        TestProcesses.awaitUntil(GENEROUS, () -> {
            int settled = observed.size();
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return observed.size() == settled;
        });
        awaitSummary(observed);
        assertEquals("Run cancelled", observed.getLast(), observed.toString());
    }

    @Test
    void rejectsAnUnknownConfigurationNameAndNamesWhatCanRun() {
        mavenProject("quick");

        LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);
        var result = capability.start(new RunCapability.RunRequest("maven:nope", temp, Map.of()), line -> {
        });

        CapabilityFailure failure = assertInstanceOf(CapabilityResult.Failed.class, result).failure();
        assertEquals("Unknown run configuration 'maven:nope'", failure.reason());
        assertTrue(failure.detail().contains("maven:clean"));
    }

    @Test
    void rejectsARunWithAWorkingDirectoryOutsideTheProjectRoot() throws IOException {
        Path project = Files.createDirectories(temp.resolve("project"));
        Path outside = Files.createDirectories(temp.resolve("outside"));

        LocalRunCapability capability = LocalRunCapability.of(new LocalProjectRoot(project), GENEROUS);
        var result = capability.start(new RunCapability.RunRequest("maven:clean", outside, Map.of()), line -> {
        });

        assertTrue(result.isFailure());
        var failure = assertInstanceOf(CapabilityResult.Failed.class, result).failure();
        assertTrue(failure.reason().contains("outside the project root"));
    }

    @Test
    void rejectsAGoalWithAMissingWorkingDirectory() {
        mavenProject("quick");

        LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);
        var result = capability.start(
                new RunCapability.RunRequest("maven:clean", temp.resolve("not-here"), Map.of()), line -> {
                });

        CapabilityFailure failure = assertInstanceOf(CapabilityResult.Failed.class, result).failure();
        assertEquals("The working directory does not exist", failure.reason());
    }

    @Test
    void runsAPhaseWithTheModuleSelectorAfterThePhaseThroughTheWrapper() throws IOException {
        mavenProject("args");

        LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);
        List<String> observed = new CopyOnWriteArrayList<>();

        assertOk(capability.start(new RunCapability.RunRequest("maven:validate", temp, Map.of(),
                List.of("-pl", "vintelli-tui")), observed::add));

        awaitSummary(observed);
        assertTrue(observed.contains("args:validate -pl vintelli-tui"), observed.toString());
    }

    @Test
    void runsTheRootPhaseWithoutAnyReactorSelector() throws IOException {
        mavenProject("args");

        LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);
        List<String> observed = new CopyOnWriteArrayList<>();

        assertOk(capability.start(new RunCapability.RunRequest("maven:validate", temp, Map.of()), observed::add));

        awaitSummary(observed);
        assertTrue(observed.contains("args:validate"), observed.toString());
    }

    @Test
    void runsAConfiguredGoalWithProfilesAndSkipTests() throws IOException {
        mavenProject("args");

        LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);
        List<String> observed = new CopyOnWriteArrayList<>();
        assertOk(capability.start(new RunCapability.RunRequest("maven:test", temp, Map.of(),
                List.of("-Pci,fast", "-DskipTests")), observed::add));

        awaitSummary(observed);
        assertTrue(observed.contains("args:test -Pci,fast -DskipTests"), observed.toString());
    }

    @Test
    void runsPluginGoalsDiscoveredFromTheMavenWindow() throws IOException {
        mavenProject("plugin");

        LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);
        List<String> observed = new CopyOnWriteArrayList<>();

        CapabilityResult<RunCapability.RunHandle> result = capability.start(
                new RunCapability.RunRequest("maven:org.apache.maven.plugins:maven-dependency-plugin:tree", temp,
                        Map.of(), List.of()), observed::add);
        assertOk(result);
        awaitSummary(observed);
        assertTrue(observed.contains("args:org.apache.maven.plugins:maven-dependency-plugin:tree"), observed.toString());
    }

    @Test
    void refusesPluginGoalsThatWereNotDiscoveredFromTheMavenWindow() throws IOException {
        mavenProject("plugin");
        LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);

        CapabilityFailure failure = assertInstanceOf(CapabilityResult.Failed.class, capability.start(
                new RunCapability.RunRequest("maven:exec:exec", temp, Map.of()), line -> {
                })).failure();

        assertEquals("Unknown run configuration 'maven:exec:exec'", failure.reason());
    }

    @Test
    void runsADeclaredGoalOnlyForItsSelectedModule() throws IOException {
        mavenProject("module");
        LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);
        List<String> observed = new CopyOnWriteArrayList<>();

        assertOk(capability.start(new RunCapability.RunRequest(
                "maven:org.apache.maven.plugins:maven-dependency-plugin:tree", temp, Map.of(),
                List.of("-pl", "child")), observed::add));

        awaitSummary(observed);
        assertTrue(observed.contains("args:org.apache.maven.plugins:maven-dependency-plugin:tree -pl child"),
                observed.toString());
    }

    @Test
    void refusesGoalsDeclaredOnlyInsideAProfile() throws IOException {
        mavenProject("profile-plugin");
        LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);

        CapabilityFailure failure = assertInstanceOf(CapabilityResult.Failed.class, capability.start(
                new RunCapability.RunRequest("maven:org.apache.maven.plugins:maven-dependency-plugin:tree", temp,
                        Map.of()), line -> {
                        })).failure();

        assertEquals("Unknown run configuration 'maven:org.apache.maven.plugins:maven-dependency-plugin:tree'",
                failure.reason());
    }

    @Test
    void pluginGoalSyntaxRequiresNonemptyLiteralSegments() {
        assertTrue(MavenLifecycle.isPluginGoal("dev.example:sample-plugin:check_2"));
        for (String invalid : List.of("a::b", "x&y:a:g", "a:b:", "dev.example:sample-plugin:ch%eck")) {
            assertFalse(MavenLifecycle.isPluginGoal(invalid), invalid);
        }
    }

    @Test
    void refusesPluginCoordinatesWithShellCharactersOrEmptySegments() throws IOException {
        mavenProject("plugin");
        Files.writeString(temp.resolve("pom.xml"), pluginPom().replace("<goal>tree</goal>", "<goal>tr%ee</goal>"));
        LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);

        for (String goal : List.of("maven:a::b", "maven:x&calc:a:g", "maven:a:b:",
                "maven:org.apache.maven.plugins:maven-dependency-plugin:tr%ee")) {
            assertTrue(capability.start(new RunCapability.RunRequest(goal, temp, Map.of()), line -> {
            }).isFailure(), goal);
        }
    }

    @Test
    void refusesAGoalDeclaredOnlyThroughASymlinkOutsideTheProject() throws IOException {
        Path outside = Files.createTempDirectory("vintelli-maven-outside-");
        try {
            Files.writeString(outside.resolve("pom.xml"), pluginPom(), StandardCharsets.UTF_8);
            Files.writeString(temp.resolve("pom.xml"), "<project><modules><module>link</module></modules></project>",
                    StandardCharsets.UTF_8);
            try {
                Files.createSymbolicLink(temp.resolve("link"), outside);
            } catch (IOException | UnsupportedOperationException | SecurityException denied) {
                assumeTrue(false, "This runner cannot create symbolic links");
            }
            LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);
            assertTrue(capability.start(new RunCapability.RunRequest(
                    "maven:org.apache.maven.plugins:maven-dependency-plugin:tree", temp, Map.of(),
                    List.of("-pl", "link")), line -> {
                    }).isFailure());
        } finally {
            deleteRecursively(outside);
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void refusesArgumentsThatAreNotAModuleSelector() throws IOException {
        mavenProject("args");

        LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);

        CapabilityResult<RunCapability.RunHandle> result = capability.start(
                new RunCapability.RunRequest("maven:validate", temp, Map.of(), List.of("-Dskip.everything=true")),
                line -> {
                });
        assertTrue(result.isFailure());
        assertEquals("Unsupported run arguments for 'maven:validate'", failureReason(result));
    }

    @Test
    void refusesModuleSelectorsThatEscapeTheReactorSyntax() throws IOException {
        mavenProject("args");

        LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);

        assertTrue(capability.start(new RunCapability.RunRequest("maven:validate", temp, Map.of(),
                List.of("-pl", "../outside")), line -> {
                }).isFailure());
        assertTrue(capability.start(new RunCapability.RunRequest("maven:validate", temp, Map.of(),
                List.of("-pl", "--settings")), line -> {
                }).isFailure());
        assertTrue(capability.start(new RunCapability.RunRequest("maven:validate", temp, Map.of(),
                List.of("-pl", "child&calc")), line -> {
                }).isFailure());
    }

    private static String failureReason(CapabilityResult<?> result) {
        return result.failureOf().map(CapabilityFailure::reason).orElse("");
    }

    private static void awaitSummary(List<String> observed) {
        TestProcesses.awaitUntil(GENEROUS, () -> observed.stream().anyMatch(line -> line.startsWith("Run ")));
    }

    private LocalProjectRoot root() {
        return new LocalProjectRoot(temp);
    }

    /**
     * Builds a Maven-structured temporary project: a {@code pom.xml} plus a
     * host appropriate wrapper that echoes its kind, then either two final
     * marker lines or lines forever (real short process for the quick case,
     * a cancellable one for the cancel case).
     */
    private void mavenProject(String flavor) {
        try {
            String pom = switch (flavor) {
                case "plugin" -> pluginPom();
                case "profile-plugin" -> "<project><profiles><profile><id>ci</id><build><plugins>"
                        + "<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-dependency-plugin</artifactId>"
                        + "<executions><execution><goals><goal>tree</goal></goals></execution></executions></plugin>"
                        + "</plugins></build></profile></profiles></project>";
                case "module" -> "<project><modules><module>child</module></modules></project>";
                default -> "<project/>";
            };
            Files.writeString(temp.resolve("pom.xml"), pom, StandardCharsets.UTF_8);
            if ("module".equals(flavor)) {
                Files.createDirectories(temp.resolve("child"));
                Files.writeString(temp.resolve("child/pom.xml"), pluginPom(), StandardCharsets.UTF_8);
            }
            writeWrapper("mvnw", flavor);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String pluginPom() {
        return "<project><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>"
                + "<artifactId>maven-dependency-plugin</artifactId><executions><execution><goals>"
                + "<goal>tree</goal></goals></execution></executions></plugin></plugins></build></project>";
    }

    private void gradleProjectWithoutWrapper() {
        try {
            Files.writeString(temp.resolve("build.gradle"), "task placeholder {}", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes the wrapper of the host: an executable script on Unix, a batch
     * file on Windows, both printing the wrapper marker twice so the stream
     * test can observe the real child output of a goal start.
     */
    private void writeWrapper(String unixBase, String flavor) throws IOException {
        if (TestProcesses.isWindows()) {
            Path wrapper = temp.resolve(unixBase + windowsSuffix(unixBase));
            Files.writeString(wrapper, batchScript(flavor), StandardCharsets.US_ASCII);
            return;
        }
        Path wrapper = temp.resolve(unixBase);
        Files.writeString(wrapper, shellScript(flavor), StandardCharsets.US_ASCII);
        try {
            Files.setPosixFilePermissions(wrapper, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "This runner cannot express executable scripts, POSIX permissions missing");
        }
    }

    private static String windowsSuffix(String unixBase) {
        return "mvnw".equals(unixBase) ? ".cmd" : ".bat";
    }

    private static String shellScript(String flavor) {
        return "#!/bin/sh" + System.lineSeparator()
                + "echo mvnw-wrapper-called-" + flavor + System.lineSeparator()
                + (("args".equals(flavor) || "plugin".equals(flavor) || "module".equals(flavor))
                        ? "echo args:$*" + System.lineSeparator() : "")
                + ("cancel".equals(flavor)
                        ? "echo goal-pumping" + System.lineSeparator()
                                + "echo still-going" + System.lineSeparator()
                                + "sleep 120" + System.lineSeparator()
                        : "echo goal-finished" + System.lineSeparator());
    }

    private static String batchScript(String flavor) {
        return "@echo off" + System.lineSeparator()
                + "echo mvnw-wrapper-called-" + flavor + System.lineSeparator()
                + (("args".equals(flavor) || "plugin".equals(flavor) || "module".equals(flavor))
                        ? "echo args:%*" + System.lineSeparator() : "")
                + ("cancel".equals(flavor)
                        ? "echo goal-pumping" + System.lineSeparator()
                                + "echo still-going" + System.lineSeparator()
                                + "ping -n 120 127.0.0.1 > nul" + System.lineSeparator()
                                + "exit /b 0" + System.lineSeparator()
                        : "echo goal-finished" + System.lineSeparator()
                                + "exit /b 0" + System.lineSeparator());
    }

    @SuppressWarnings("unchecked")
    private static <T> T assertOk(CapabilityResult<T> result) {
        assertFalse(result.isFailure(), result.failureOf().map(CapabilityFailure::reason).orElse(""));
        return ((CapabilityResult.Ok<T>) result).value();
    }
}
