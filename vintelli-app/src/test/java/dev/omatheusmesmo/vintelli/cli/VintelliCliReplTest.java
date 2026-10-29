package dev.omatheusmesmo.vintelli.cli;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.aesh.terminal.StreamConnection;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the REPL surface of the AEsh command layer over the readline test
 * pattern: a {@link StreamConnection} over JDK pipes, so no terminal is ever
 * attached. Asserts the console executes a real command against the capability
 * ports and stops on its exit command, while the shell state of the running
 * application stays untouched.
 */
@QuarkusTest
class VintelliCliReplTest {

    @Inject
    VintelliCliRepl repl;

    @Test
    void replExecutesCommandsAndStopsOnExit() throws Exception {
        PipedInputStream consoleIn = new PipedInputStream(8192);
        PipedOutputStream testInput = new PipedOutputStream(consoleIn);
        ByteArrayOutputStream consoleOutput = new ByteArrayOutputStream();
        StreamConnection connection = new StreamConnection(StandardCharsets.UTF_8, consoleIn, consoleOutput);
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread console = new Thread(() -> {
            repl.run(connection, ready::countDown);
            done.countDown();
        });
        console.setDaemon(true);
        console.start();
        try {
            assertTrue(ready.await(60, TimeUnit.SECONDS), "the console never accepted input");
            testInput.write("inspect\n".getBytes(StandardCharsets.UTF_8));
            testInput.flush();
            assertTrue(awaitExport(consoleOutput, "terminal: local (local shell process)"),
                    "the inspect command never answered: " + consoleOutput);
            testInput.write("exit\n".getBytes(StandardCharsets.UTF_8));
            testInput.flush();
            assertTrue(done.await(60, TimeUnit.SECONDS), "the exit command never stopped the console");
        } finally {
            connection.close();
            testInput.close();
        }
    }

    @Test
    void replPrintsEveryRowOfAPipedInspectBeforeExitStopsTheConsole() throws Exception {
        PipedInputStream consoleIn = new PipedInputStream(8192);
        PipedOutputStream testInput = new PipedOutputStream(consoleIn);
        SlowTerminalOutput consoleOutput = new SlowTerminalOutput();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread console = new Thread(() -> {
            repl.run(VintelliCliRepl.pipedConnection(consoleIn, consoleOutput), ready::countDown);
            done.countDown();
        });
        console.setDaemon(true);
        console.start();
        try {
            assertTrue(ready.await(60, TimeUnit.SECONDS), "the console never accepted input");
            testInput.write("inspect\nexit\n".getBytes(StandardCharsets.UTF_8));
            testInput.flush();
            testInput.close();
            assertTrue(done.await(60, TimeUnit.SECONDS), "the exit command never stopped the console");
            String output = consoleOutput.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("terminal: local (local shell process)"), output);
            assertTrue(output.contains("vcs: "), output);
        } finally {
            testInput.close();
        }
    }

    /**
     * Stands in for a terminal that takes a moment per write: a command that is
     * still printing when the console reads the next line is cut short, unless
     * the console waits for the command to finish.
     */
    private static final class SlowTerminalOutput extends ByteArrayOutputStream {

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            super.write(bytes, offset, length);
        }
    }

    private static boolean awaitExport(ByteArrayOutputStream consoleOutput, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (consoleOutput.toString(StandardCharsets.UTF_8).contains(expected)) {
                return true;
            }
            Thread.sleep(100);
        }
        return consoleOutput.toString(StandardCharsets.UTF_8).contains(expected);
    }
}
