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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;

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
    void rejectsAGoalWithAMissingWorkingDirectory() {
        mavenProject("quick");

        LocalRunCapability capability = LocalRunCapability.of(root(), GENEROUS);
        var result = capability.start(
                new RunCapability.RunRequest("maven:clean", temp.resolve("not-here"), Map.of()), line -> {
                });

        CapabilityFailure failure = assertInstanceOf(CapabilityResult.Failed.class, result).failure();
        assertEquals("The working directory does not exist", failure.reason());
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
            Files.writeString(temp.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
            writeWrapper("mvnw", flavor);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
                + ("cancel".equals(flavor)
                        ? "echo goal-pumping" + System.lineSeparator()
                                + "echo still-going" + System.lineSeparator()
                                + "sleep 120" + System.lineSeparator()
                        : "echo goal-finished" + System.lineSeparator());
    }

    private static String batchScript(String flavor) {
        return "@echo off" + System.lineSeparator()
                + "echo mvnw-wrapper-called-" + flavor + System.lineSeparator()
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
