package dev.omatheusmesmo.vintelli.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Severity;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;

/**
 * Runs the generic {@link LspClient} against the real jdtls through the jdtls
 * profile, on the Maven fixture project under {@code jdtls-fixture}: the
 * initialize handshake, the diagnostics of a class with one deliberate error
 * and of a clean class, a hover on a method and a shutdown that leaves no
 * server process behind.
 *
 * <p>The test needs a real jdtls installation and is enabled only when
 * {@code VINTELLI_JDTLS_HOME} names the extracted archive, so the default build
 * skips it. The {@code lsp-jdtls} workflow downloads the pinned archive, checks
 * its SHA-256 and runs this class on a Java 25 runtime (issue #100).
 */
@EnabledIfEnvironmentVariable(named = "VINTELLI_JDTLS_HOME", matches = ".+")
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class JdtlsFixtureTest {

    private static final String FIXTURE = "/jdtls-fixture";
    private static final String BROKEN = "src/main/java/fixture/Broken.java";
    private static final String GREETER = "src/main/java/fixture/Greeter.java";
    private static final Duration READINESS_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration PROCESS_EXIT_TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    Path workspace;

    @TempDir
    Path data;

    @Test
    void realJdtlsServesDiagnosticsAndHoverAndShutsDownCleanly() throws Exception {
        assertTrue(Runtime.version().feature() >= 25, "jdtls 1.63.0 needs a Java 25 runtime, found "
                + Runtime.version().feature() + " (issue #100)");
        copyFixture();
        Path broken = workspace.resolve(BROKEN);
        Path greeter = workspace.resolve(GREETER);

        try (LspClient client = new LspClient(profile())) {
            assertEquals(ProviderHealth.available(), client.health(), client.health().reason());
            assertEquals("jdtls", client.activeProfileName().orElseThrow());

            List<Diagnostic> problems = awaitDiagnostics(client, broken, found -> !errorsOf(found).isEmpty());
            List<Diagnostic> errors = errorsOf(problems);
            assertEquals(1, errors.size(), problems.toString());
            Diagnostic error = errors.get(0);
            assertEquals(broken, error.filePath());
            assertEquals(Severity.ERROR, error.severity());
            assertEquals(5, error.startLine());
            assertEquals(20, error.startColumn());
            assertEquals(5, error.endLine());
            assertEquals(27, error.endColumn());
            assertTrue(error.message().contains("cannot convert from String to int"), error.message());

            List<Diagnostic> clean = awaitDiagnostics(client, greeter, found -> true);
            assertTrue(errorsOf(clean).isEmpty(), clean.toString());

            CapabilityResult<String> hover = client.hover(greeter, 4, 18);
            assertFalse(hover.isFailure(), hover.toString());
            assertTrue(ok(hover).contains("greet(String name)"), ok(hover));

            assertEquals(ProviderHealth.available(), client.health(), client.health().reason());
            assertFalse(serverProcesses().isEmpty(), "the jdtls process must run before the session closes");
        }

        awaitNoServerProcess();
    }

    private LspProfileConfig profile() {
        List<String> command = List.of(jdtlsExecutable().toString(), "-data", data.toString());
        return new StaticProfileConfig.StaticConfig(Optional.empty(), Map.of("jdtls",
                new StaticProfileConfig.StaticProfile(command, Optional.of(workspace.toString()), Optional.empty())));
    }

    private static Path jdtlsExecutable() {
        Path executable = Path.of(System.getenv("VINTELLI_JDTLS_HOME"), "bin", "jdtls");
        assertTrue(Files.isExecutable(executable), "no executable jdtls at " + executable
                + "; extract the jdt-language-server archive into VINTELLI_JDTLS_HOME");
        return executable;
    }

    private void copyFixture() throws IOException, URISyntaxException {
        Path source = Path.of(JdtlsFixtureTest.class.getResource(FIXTURE).toURI());
        try (Stream<Path> entries = Files.walk(source)) {
            for (Path entry : entries.toList()) {
                Path target = workspace.resolve(source.relativize(entry));
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(entry, target);
                }
            }
        }
    }

    /**
     * Polls the diagnostics until the server answers with a state the caller
     * accepts. Only a not-ready failure (the server is still indexing or has
     * published nothing yet) is retried; any other failure ends the test at
     * once, and the deadline bounds the wait.
     */
    private static List<Diagnostic> awaitDiagnostics(LspClient client, Path file,
            Predicate<List<Diagnostic>> ready) throws InterruptedException {
        long deadline = System.nanoTime() + READINESS_TIMEOUT.toNanos();
        while (true) {
            CapabilityResult<List<Diagnostic>> result = client.diagnostics(file);
            Optional<CapabilityFailure> failure = result.failureOf();
            if (failure.isEmpty()) {
                List<Diagnostic> problems = ok(result);
                if (ready.test(problems)) {
                    return problems;
                }
            } else if (!"not-ready".equals(failure.get().reason())) {
                fail("the language server failed on " + file + ": " + failure.get());
            }
            if (System.nanoTime() > deadline) {
                fail("no ready diagnostics for " + file + " within " + READINESS_TIMEOUT + ": " + result);
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
    }

    private static List<Diagnostic> errorsOf(List<Diagnostic> problems) {
        return problems.stream()
                .filter(problem -> problem.severity() == Severity.ERROR)
                .toList();
    }

    private List<ProcessHandle> serverProcesses() {
        String marker = data.toString();
        return ProcessHandle.allProcesses()
                .filter(handle -> handle.info().arguments()
                        .map(arguments -> Arrays.stream(arguments).anyMatch(argument -> argument.contains(marker)))
                        .orElse(false))
                .toList();
    }

    private void awaitNoServerProcess() throws InterruptedException {
        long deadline = System.nanoTime() + PROCESS_EXIT_TIMEOUT.toNanos();
        while (!serverProcesses().isEmpty()) {
            if (System.nanoTime() > deadline) {
                fail("jdtls processes survived the close: " + serverProcesses());
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
    }

    private static <V> V ok(CapabilityResult<V> result) {
        return ((CapabilityResult.Ok<V>) result).value();
    }
}
