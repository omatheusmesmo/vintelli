package dev.omatheusmesmo.vintelli.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Build tool detection on real temporary project roots: Maven wins with a
 * {@code pom.xml}, Gradle with its build files, and the wrapper is chosen
 * over the system command whenever the host appropriate wrapper file exists.
 */
class BuildToolDetectorTest {

    @TempDir
    Path temp;

    @Test
    void detectsMavenWithAPomAndPrefersAnExecutableWrapper() throws IOException {
        Files.writeString(temp.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        if (TestProcesses.isWindows()) {
            Files.writeString(temp.resolve("mvnw.cmd"), "@echo off", StandardCharsets.US_ASCII);
        } else {
            makeExecutable(Files.writeString(temp.resolve("mvnw"), "#!/bin/sh", StandardCharsets.UTF_8));
        }

        BuildToolDetector detector = new BuildToolDetector(new LocalProjectRoot(temp));
        if (TestProcesses.isWindows()) {
            assertIterableEquals(
                    List.of("cmd", "/c", temp.toRealPath().resolve("mvnw.cmd").toAbsolutePath().toString()),
                    detector.baseCommand());
        } else {
            assertIterableEquals(List.of(temp.toRealPath().resolve("mvnw").toAbsolutePath().toString()),
                    detector.baseCommand());
        }

        assertEquals(BuildToolDetector.BuildTool.MAVEN, detector.detectedTool());
        assertTrue(detector.wrapperResolved());
        assertEquals("./mvnw", detector.programLabel());
        assertTrue(detector.specifications().stream().anyMatch(
                specification -> specification.identifier().equals("maven:verify")
                        && specification.command().getLast().equals("verify")));
    }

    @Test
    void fallsBackToTheSystemCommandWhenNoWrapperExists() throws IOException {
        Files.writeString(temp.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);

        BuildToolDetector detector = new BuildToolDetector(new LocalProjectRoot(temp));

        assertEquals(BuildToolDetector.BuildTool.MAVEN, detector.detectedTool());
        assertTrue(!detector.wrapperResolved());
        assertIterableEquals(List.of("mvn"), detector.baseCommand());
        assertEquals("mvn", detector.programLabel());
    }

    @Test
    void detectsGradleThroughAnyOfItsBuildFiles() throws IOException {
        Files.writeString(temp.resolve("build.gradle.kts"), "", StandardCharsets.UTF_8);

        BuildToolDetector detector = new BuildToolDetector(new LocalProjectRoot(temp));

        assertEquals(BuildToolDetector.BuildTool.GRADLE, detector.detectedTool());
        assertIterableEquals(List.of("gradle"), detector.baseCommand());
        assertTrue(detector.specifications().stream()
                .allMatch(specification -> specification.identifier().startsWith("gradle:")));
    }

    @Test
    void answersNothingDetectedOnAnEmptyProject() {
        BuildToolDetector detector = new BuildToolDetector(new LocalProjectRoot(temp));

        assertEquals(BuildToolDetector.BuildTool.NONE, detector.detectedTool());
        assertEquals(List.of(), detector.specifications());
        assertEquals("", detector.programLabel());
    }

    @Test
    void prefersMavenOverGradleWhenBothBuildFilesExist() throws IOException {
        Files.writeString(temp.resolve("build.gradle"), "", StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        writeGradleWrapper();

        BuildToolDetector detector = new BuildToolDetector(new LocalProjectRoot(temp));

        assertEquals(BuildToolDetector.BuildTool.MAVEN, detector.detectedTool());
        assertTrue(detector.specifications().stream()
                .allMatch(specification -> specification.identifier().startsWith("maven:")));
        assertFalse(detector.wrapperResolved());
        assertIterableEquals(List.of("mvn"), detector.baseCommand());
    }

    @Test
    void usesTheGradleWrapperOverTheInstalledCommand() throws IOException {
        Files.writeString(temp.resolve("settings.gradle.kts"), "", StandardCharsets.UTF_8);
        writeGradleWrapper();

        BuildToolDetector detector = new BuildToolDetector(new LocalProjectRoot(temp));

        assertEquals(BuildToolDetector.BuildTool.GRADLE, detector.detectedTool());
        assertTrue(detector.wrapperResolved());
        assertEquals("./gradlew", detector.programLabel());
    }

    @Test
    void ignoresAWrapperScriptWithoutTheExecutableBit() throws IOException {
        assumeFalse(TestProcesses.isWindows(), "Windows has no executable bit");
        Files.writeString(temp.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("mvnw"), "#!/bin/sh", StandardCharsets.UTF_8);

        BuildToolDetector detector = new BuildToolDetector(new LocalProjectRoot(temp));

        assertFalse(detector.wrapperResolved());
        assertIterableEquals(List.of("mvn"), detector.baseCommand());
    }

    @Test
    void ignoresAWrapperThatIsADirectory() throws IOException {
        Files.writeString(temp.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.createDirectories(temp.resolve(TestProcesses.isWindows() ? "mvnw.cmd" : "mvnw"));

        BuildToolDetector detector = new BuildToolDetector(new LocalProjectRoot(temp));

        assertFalse(detector.wrapperResolved());
        assertIterableEquals(List.of("mvn"), detector.baseCommand());
    }

    @Test
    void onWindowsTheBatchWrapperIsRunThroughCmdAndTheUnixScriptIsNeverTried() throws IOException {
        Files.writeString(temp.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("mvnw.cmd"), "@echo off", StandardCharsets.US_ASCII);
        makeExecutable(Files.writeString(temp.resolve("mvnw"), "#!/bin/sh", StandardCharsets.UTF_8));

        BuildToolDetector detector = new BuildToolDetector(new LocalProjectRoot(temp), true);

        assertTrue(detector.wrapperResolved());
        assertIterableEquals(List.of("cmd", "/c", temp.toRealPath().resolve("mvnw.cmd").toString()),
                detector.baseCommand());
    }

    @Test
    void onWindowsAUnixScriptAloneDoesNotCountAsAWrapper() throws IOException {
        Files.writeString(temp.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        makeExecutable(Files.writeString(temp.resolve("mvnw"), "#!/bin/sh", StandardCharsets.UTF_8));

        BuildToolDetector detector = new BuildToolDetector(new LocalProjectRoot(temp), true);

        assertFalse(detector.wrapperResolved());
        assertIterableEquals(List.of("mvn"), detector.baseCommand());
    }

    @Test
    void onUnixABatchWrapperAloneDoesNotCountAsAWrapper() throws IOException {
        Files.writeString(temp.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("mvnw.cmd"), "@echo off", StandardCharsets.US_ASCII);

        BuildToolDetector detector = new BuildToolDetector(new LocalProjectRoot(temp), false);

        assertFalse(detector.wrapperResolved());
        assertIterableEquals(List.of("mvn"), detector.baseCommand());
    }

    @Test
    void onWindowsTheGradleBatchWrapperIsRunThroughCmd() throws IOException {
        Files.writeString(temp.resolve("build.gradle"), "", StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("gradlew.bat"), "@echo off", StandardCharsets.US_ASCII);

        BuildToolDetector detector = new BuildToolDetector(new LocalProjectRoot(temp), true);

        assertEquals(BuildToolDetector.BuildTool.GRADLE, detector.detectedTool());
        assertTrue(detector.wrapperResolved());
        assertIterableEquals(List.of("cmd", "/c", temp.toRealPath().resolve("gradlew.bat").toString()),
                detector.baseCommand());
    }

    @ParameterizedTest
    @ValueSource(strings = {"&", "|", "^", "%", "<", ">", "!", "\""})
    void detectsEveryCmdMetaCharacterThroughThePredicate(String meta) {
        assertTrue(BuildToolDetector.containsCmdMetaCharacters("a" + meta + "b"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"&", "^", "%", "!"})
    void refusesAWindowsWrapperWhosePathContainsLegalFileNameMetaCharacters(String meta) throws IOException {
        Path project = Files.createDirectories(temp.resolve("a" + meta + "b"));
        Files.writeString(project.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("mvnw.cmd"), "@echo off", StandardCharsets.US_ASCII);

        BuildToolDetector detector = new BuildToolDetector(new LocalProjectRoot(project), true);

        assertFalse(detector.wrapperResolved());
        assertIterableEquals(List.of("mvn"), detector.baseCommand());
    }

    private void writeGradleWrapper() throws IOException {
        if (TestProcesses.isWindows()) {
            Files.writeString(temp.resolve("gradlew.bat"), "@echo off", StandardCharsets.US_ASCII);
        } else {
            makeExecutable(Files.writeString(temp.resolve("gradlew"), "#!/bin/sh", StandardCharsets.UTF_8));
        }
    }

    private static void makeExecutable(Path script) throws IOException {
        try {
            Files.setPosixFilePermissions(script, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "No POSIX permissions on this runner");
        }
    }
}
