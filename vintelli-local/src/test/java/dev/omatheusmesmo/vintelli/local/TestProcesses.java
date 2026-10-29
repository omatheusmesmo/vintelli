package dev.omatheusmesmo.vintelli.local;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Real short process support for the local provider tests: the single file
 * Java sources on the test classpath are launched through the java launcher
 * of the running JVM, which works on Linux, macOS and Windows alike, with no
 * dependence on build tools installed on the runner.
 */
final class TestProcesses {

    private static final AtomicLong COUNTER = new AtomicLong();

    /**
     * @return the java executable of the JVM running the tests, resolved
     *         through the current process command first and the
     *         {@code java.home} layout second, absolute in both cases
     */
    static Path javaExecutable() {
        String command = ProcessHandle.current().info().command().orElseGet(TestProcesses::javaHomeExecutable);
        return Path.of(command).toAbsolutePath().normalize();
    }

    private static String javaHomeExecutable() {
        String home = System.getProperty("java.home");
        if (isWindows() && Files.isRegularFile(Path.of(home, "bin", "java.exe"))) {
            return Path.of(home, "bin", "java.exe").toString();
        }
        return Path.of(home, "bin", "java").toString();
    }

    /**
     * Copies the single file source of the test classpath into a fresh
     * directory under {@code root} and returns its absolute path for
     * {@code java File.java}.
     */
    static Path extractSource(Path root, String fileName) {
        Path directory = root.resolve(fileName.replace('.', '-') + "-" + COUNTER.incrementAndGet());
        try {
            Files.createDirectories(directory);
            try (InputStream in = TestProcesses.class.getResourceAsStream("/scripts/" + fileName)) {
                if (in == null) {
                    throw new IllegalStateException("Missing test resource /scripts/" + fileName);
                }
                Files.copy(in, directory.resolve(fileName));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return directory.resolve(fileName);
    }

    /**
     * Polls a condition with short sleeps until it holds or the deadline
     * passes, standing in for a polling library in the test classpath.
     */
    static void awaitUntil(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("Condition was not met within " + timeout);
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private TestProcesses() {
    }
}
