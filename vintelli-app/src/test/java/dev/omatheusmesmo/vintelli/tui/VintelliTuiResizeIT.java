package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Resizes the packaged runner on a real pseudo terminal, so the process receives
 * {@code SIGWINCH}. Tape playback in {@link VintelliTuiStartupIT} has no TTY and
 * installs no signal handler, and no headless surface can observe a signal: a
 * handler that is unsafe in the native image only shows here, as an abort with
 * {@code Must either be at a safepoint or in native mode}.
 */
@QuarkusIntegrationTest
@DisabledOnOs(OS.WINDOWS)
class VintelliTuiResizeIT {

    private static final String TOOL_WINDOW_MARKER = "Terminal";
    private static final int RESIZES = 40;
    private static final long RESIZE_PAUSE_MILLIS = 100;
    private static final long DRAW_TIMEOUT_MILLIS = 60_000;
    private static final long EXIT_TIMEOUT_SECONDS = 30;
    private static final int CTRL_C = 3;

    @TempDir
    Path workDir;

    @Test
    void theTuiKeepsRunningWhileItsTerminalIsResized() throws Exception {
        Path config = Files.createDirectories(workDir.resolve("config"));
        List<String> command = new ArrayList<>(VintelliTuiStartupIT.runner(List.of(
                "-Dquarkus.http.port=0",
                "-Dquarkus.log.file.enabled=false",
                "-Dvintelli.config.dir=" + config)));
        command.add("--tui");
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("TERM", "xterm-256color");

        PtyProcess process = new PtyProcessBuilder(command.toArray(String[]::new))
                .setDirectory(workDir.toString())
                .setEnvironment(environment)
                .setInitialColumns(120)
                .setInitialRows(40)
                .start();
        StringBuffer screen = new StringBuffer();
        Thread reader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), screen));
        try {
            awaitFirstFrame(process, screen);

            for (int resize = 0; resize < RESIZES && process.isAlive(); resize++) {
                process.setWinSize(new WinSize(100 + resize % 40, 30 + resize % 10));
                Thread.sleep(RESIZE_PAUSE_MILLIS);
            }

            assertTrue(process.isAlive(), () -> "The TUI died during a resize\n" + report(process, screen));
            process.getOutputStream().write(CTRL_C);
            process.getOutputStream().flush();
            assertTrue(process.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    () -> "The TUI did not quit\n" + report(process, screen));
            assertEquals(0, process.exitValue(), () -> report(process, screen));
        } finally {
            process.destroyForcibly();
            reader.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    private static void awaitFirstFrame(PtyProcess process, StringBuffer screen) throws InterruptedException {
        long deadline = System.currentTimeMillis() + DRAW_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline && process.isAlive()) {
            if (screen.indexOf(TOOL_WINDOW_MARKER) >= 0) {
                return;
            }
            Thread.sleep(RESIZE_PAUSE_MILLIS);
        }
        throw new AssertionError("The TUI never drew its tool windows\n" + report(process, screen));
    }

    private static void drain(InputStream output, StringBuffer screen) {
        byte[] chunk = new byte[8192];
        try {
            for (int read = output.read(chunk); read >= 0; read = output.read(chunk)) {
                screen.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
            }
        } catch (IOException closed) {
            screen.append("\n[pty closed: ").append(closed.getMessage()).append(']');
        }
    }

    private static String report(PtyProcess process, StringBuffer screen) {
        String exit = process.isAlive() ? "running" : "exit " + process.exitValue();
        String tail = screen.length() > 4_000 ? screen.substring(screen.length() - 4_000) : screen.toString();
        return exit + "\n--- output tail ---\n" + tail;
    }
}
