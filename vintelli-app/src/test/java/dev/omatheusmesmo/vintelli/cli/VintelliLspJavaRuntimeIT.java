package dev.omatheusmesmo.vintelli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A language server profile that sets {@code java.min-version} is refused
 * before its server starts when the Java it would run on is too old or cannot
 * be read (issue #100). The packaged runner reads the profile from system
 * properties, as a user configuration would reach it, and {@code problems}
 * prints the reason. The Java installations are directories that hold only a
 * {@code release} file, and the profile command is the test JVM, which never
 * runs because the check fails first.
 */
@QuarkusIntegrationTest
class VintelliLspJavaRuntimeIT {

    private static final long TIMEOUT_SECONDS = 60;

    @TempDir
    Path root;

    @Test
    void problemsNamesTheTooOldJavaOfTheProfile() throws Exception {
        Path java21 = javaHome("jdk-21", "JAVA_VERSION=\"21.0.5\"\n");

        CliRun run = problems(java21);

        assertEquals(1, run.exitCode(), run.describe());
        assertTrue(run.stdout().contains("Problems capability is unavailable: "), run.describe());
        assertTrue(run.stdout().contains("the profile 'jdtls' needs a Java 25 or newer runtime, found Java 21.0.5 at "
                + java21 + " (from vintelli.lsp.profiles.jdtls.java.home)"), run.describe());
    }

    @Test
    void problemsNamesAJavaWhoseVersionCannotBeRead() throws Exception {
        Path withoutVersion = javaHome("jdk-unknown", "IMPLEMENTOR=\"Vintelli test\"\n");

        CliRun run = problems(withoutVersion);

        assertEquals(1, run.exitCode(), run.describe());
        assertTrue(run.stdout().contains("the Java version at " + withoutVersion
                + " (from vintelli.lsp.profiles.jdtls.java.home) cannot be determined"), run.describe());
    }

    private Path javaHome(String name, String release) throws IOException {
        Path home = Files.createDirectories(root.resolve(name));
        Files.writeString(home.resolve("release"), release);
        return home;
    }

    private CliRun problems(Path javaHome) throws Exception {
        List<String> options = List.of(
                "-Dvintelli.lsp.profiles.jdtls.command=" + Path.of(System.getProperty("java.home"), "bin", "java"),
                "-Dvintelli.lsp.profiles.jdtls.workspace-root=" + root,
                "-Dvintelli.lsp.profiles.jdtls.java.min-version=25",
                "-Dvintelli.lsp.profiles.jdtls.java.home=" + javaHome,
                "-Dquarkus.http.port=0",
                "-Dquarkus.log.file.enabled=false");
        List<String> command = new ArrayList<>(runner(options));
        command.addAll(List.of("problems", root.resolve("Main.java").toString()));
        Process process = new ProcessBuilder(command).start();
        process.getOutputStream().close();
        CompletableFuture<String> stdout = read(process.getInputStream());
        CompletableFuture<String> stderr = read(process.getErrorStream());
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("The CLI did not finish within " + TIMEOUT_SECONDS + "s\n"
                    + new CliRun(-1, stdout.get(), stderr.get()).describe());
        }
        return new CliRun(process.exitValue(), stdout.get(), stderr.get());
    }

    private static List<String> runner(List<String> options) {
        String nativeRunner = System.getProperty("native.image.path");
        List<String> command = new ArrayList<>();
        if (nativeRunner != null && Files.isExecutable(Path.of(nativeRunner))) {
            command.add(nativeRunner);
            command.addAll(options);
            return command;
        }
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(options);
        command.add("-jar");
        command.add(Path.of("target", "quarkus-app", "quarkus-run.jar").toAbsolutePath().toString());
        return command;
    }

    private static CompletableFuture<String> read(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read the CLI output", e);
            }
        });
    }

    private record CliRun(int exitCode, String stdout, String stderr) {

        String describe() {
            return "exit " + exitCode + "\n--- stdout ---\n" + stdout + "\n--- stderr ---\n" + stderr;
        }
    }
}
