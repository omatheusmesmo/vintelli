package dev.omatheusmesmo.vintelli.jsonrpc;

import static dev.omatheusmesmo.vintelli.jsonrpc.TestSupport.AWAIT_MILLIS;
import static dev.omatheusmesmo.vintelli.jsonrpc.TestSupport.awaitLatch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

class JsonRpcProcessSessionTest {

    private static final String SHELL = "/bin/sh";

    private static final String ECHO_LOOP =
            "while IFS= read -r line; do echo \"$line\"; done";

    @Test
    void echoRoundTripThroughARealProcessStdioPipe() throws Exception {
        assumeUnixShell();
        CountDownLatch exited = new CountDownLatch(1);
        try (JsonRpcProcessSession session = JsonRpcProcessSession.spawn(
                JsonRpcProcessSession.ProcessSpec.of(List.of(SHELL, "-c", ECHO_LOOP)),
                lines -> {
                }, code -> exited.countDown())) {
            JsonRpcConnection connection = new JsonRpcConnection(
                    LineDelimitedFraming.STANDARD, session.stdout(), session.stdin());
            JsonRpcRequest request = new JsonRpcRequest(
                    JsonRpcId.of(4), "echo", TestSupport.params("word", "hi"));
            connection.send(request);
            JsonRpcMessage echoed = connection.receive();
            assertNotNull(echoed);
            assertEquals(request, echoed);
            session.shutdown(Duration.ofSeconds(3));
            assertFalse(session.isAlive(), "session should stop after the shutdown window");
        }
        awaitLatch(exited);
    }

    @Test
    void chattyStderrDoesNotBlockTheChild() throws Exception {
        assumeUnixShell();
        AtomicInteger stderrLines = new AtomicInteger();
        CountDownLatch exited = new CountDownLatch(1);
        try (JsonRpcProcessSession session = JsonRpcProcessSession.spawn(
                JsonRpcProcessSession.ProcessSpec.of(
                        List.of(SHELL, "-c", "awk 'BEGIN { for (i = 0; i < 20000; i++) print i; }' >&2; exit 0")),
                lines -> stderrLines.incrementAndGet(), code -> exited.countDown())) {
            awaitLatch(exited);
            assertTrue(stderrLines.get() > 100,
                    "a busy stderr must be drained, saw " + stderrLines.get() + " lines");
            session.shutdown(Duration.ofSeconds(2));
        }
    }

    @Test
    void capturesStderrAndReportsCleanExitCode() throws Exception {
        assumeUnixShell();
        CountDownLatch exited = new CountDownLatch(1);
        AtomicReference<Integer> exitCode = new AtomicReference<>();
        List<String> stderrLines = new CopyOnWriteArrayList<>();
        try (JsonRpcProcessSession session = JsonRpcProcessSession.spawn(
                JsonRpcProcessSession.ProcessSpec.of(
                        List.of(SHELL, "-c", "echo boom >&2; exit 3")),
                stderrLines::add, code -> {
                    exitCode.set(Integer.valueOf(code));
                    exited.countDown();
                })) {
            awaitCondition(() -> Boolean.valueOf(
                    stderrLines.stream().anyMatch(line -> line.contains("boom"))));
            session.shutdown(Duration.ofSeconds(3));
            assertFalse(session.isAlive());
        }
        awaitLatch(exited);
        awaitCondition(() -> Boolean.valueOf(Integer.valueOf(3).equals(exitCode.get())));
    }

    /**
     * The child closes stdin itself and exits cleanly on end of input: shutdown must
     * close stdin first, so such a child exits by itself instead of collecting a
     * termination signal.
     */
    @Test
    void shutdownClosesStdinBeforeAnySignal() throws Exception {
        assumeUnixShell();
        CountDownLatch exited = new CountDownLatch(1);
        AtomicReference<Integer> exitCode = new AtomicReference<>();
        try (JsonRpcProcessSession session = JsonRpcProcessSession.spawn(
                JsonRpcProcessSession.ProcessSpec.of(
                        List.of(SHELL, "-c", ECHO_LOOP + "; echo stdin was closed >&2; exit 7")),
                lines -> {
                }, code -> {
                    exitCode.set(Integer.valueOf(code));
                    exited.countDown();
                })) {
            Instant shutdownAt = Instant.now();
            session.shutdown(Duration.ofMillis(300));
            awaitLatch(exited);
            assertFalse(session.isAlive(), "process must have exited by itself after stdin close");
            assertEquals(Integer.valueOf(7), exitCode.get(),
                    "the child must leave through the stdin close, not through a kill");
            assertTrue(Duration.between(shutdownAt, Instant.now()).toMillis() < 4000);
        }
    }

    /**
     * A child with a signal handler leaves on the first signal with its own exit
     * code, which proves the plain destroy step runs before the forced kill.
     */
    @Test
    void cleanExitOnTheTerminationSignalHappensWithinTheGraceWindow() throws Exception {
        assumeUnixShell();
        AtomicReference<Integer> exitCode = new AtomicReference<>();
        try (JsonRpcProcessSession session = JsonRpcProcessSession.spawn(
                JsonRpcProcessSession.ProcessSpec.of(
                        List.of(SHELL, "-c", "trap 'exit 0' TERM; while :; do sleep 0.2; done")),
                lines -> {
                }, code -> exitCode.set(Integer.valueOf(code)))) {
            assertTrue(session.isAlive());
            Instant shutdownAt = Instant.now();
            session.shutdown(Duration.ofMillis(500));
            assertFalse(session.isAlive());
            awaitCondition(() -> Boolean.valueOf(Integer.valueOf(0).equals(exitCode.get())),
                    "TERM must arrive before the forced kill; the child handled it");
            assertTrue(Duration.between(shutdownAt, Instant.now()).toMillis() < 4500,
                    "the child answered the termination signal, so no forced kill was reached");
        }
    }

    /**
     * The child ignores the signal, so the shutdown must escalate: it is still alive
     * after the grace window, then dead, and the sleep grandchild that survives the
     * parent on Linux is killed with it through the descendants walk.
     */
    @Test
    void signalThenForceKillAfterGraceWindowAlsoKillsDescendants() throws Exception {
        assumeUnixShell();
        CountDownLatch exited = new CountDownLatch(1);
        try (JsonRpcProcessSession session = JsonRpcProcessSession.spawn(
                JsonRpcProcessSession.ProcessSpec.of(
                        List.of(SHELL, "-c", "trap '' TERM; echo $$; sleep 60 & echo $!; sleep 60; wait")),
                lines -> {
                }, code -> exited.countDown())) {
            Pids pids = readPids(session.stdout());
            assertTrue(session.isAlive(), "the ignoring child must be alive initially");
            Instant shutdownAt = Instant.now();
            session.shutdown(Duration.ofMillis(400));
            assertFalse(session.isAlive(), "a signal ignoring child must be killed forcibly");
            assertTrue(Duration.between(shutdownAt, Instant.now()).toMillis() >= 400,
                    "the child must survive its grace window before the escalation");
            awaitCondition(
                    () -> Boolean.valueOf(ProcessHandle.of(pids.descendantPid())
                            .map(handle -> Boolean.valueOf(!handle.isAlive()))
                            .orElse(Boolean.TRUE)),
                    "the descendant must not outlive the session");
            awaitCondition(
                    () -> Boolean.valueOf(ProcessHandle.of(pids.childPid())
                            .map(handle -> Boolean.valueOf(!handle.isAlive()))
                            .orElse(Boolean.TRUE)),
                    "the child must not survive the forced kill");
        }
        awaitLatch(exited);
    }

    private record Pids(long childPid, long descendantPid) {
    }

    private static Pids readPids(InputStream stdout) throws IOException {
        try (BufferedReader lines = new BufferedReader(
                new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
            String first = lines.readLine();
            String second = lines.readLine();
            return new Pids(Long.parseLong(first.trim()), Long.parseLong(second.trim()));
        }
    }

    private static void assumeUnixShell() {
        assumeTrue(Files.isExecutable(Path.of(SHELL)), "/bin/sh required for lifecycle tests");
    }

    private static void awaitCondition(Supplier<Boolean> condition) throws Exception {
        awaitCondition(condition, "condition was not reached in time");
    }

    private static void awaitCondition(Supplier<Boolean> condition, String message) throws Exception {
        long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
        while (System.currentTimeMillis() < deadline
                && !Boolean.TRUE.equals(condition.get())) {
            Thread.sleep(20L);
        }
        assertTrue(Boolean.TRUE.equals(condition.get()), message);
    }
}
