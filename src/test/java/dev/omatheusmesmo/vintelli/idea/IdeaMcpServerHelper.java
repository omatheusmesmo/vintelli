package dev.omatheusmesmo.vintelli.idea;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Launches the fake IDEA MCP server (src/test/resources/idea_mcp_server.java)
 * as an external single-file source process, mirroring how a real IDEA server
 * would run beside the application.
 */
public final class IdeaMcpServerHelper {

    private static final int DEFAULT_PORT = 8080;
    private static final int PORT_TIMEOUT_SECONDS = 120;

    private IdeaMcpServerHelper() {
    }

    /**
     * Starts the fake IDEA MCP server on port 8080. Restricted mode with the
     * fixed token matching the test configuration; the consent gate is open.
     */
    public static Process startServer() throws Exception {
        Process process = new ProcessBuilder()
                .command(launchCommand())
                .start();
        waitForPort(DEFAULT_PORT, process);
        return process;
    }

    private static List<String> launchCommand() throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaBinary());
        command.add(scriptPath());
        command.add(String.valueOf(DEFAULT_PORT));
        command.add("vintelli-token");
        command.add("true");
        return command;
    }

    private static String javaBinary() {
        String bin = Path.of(System.getProperty("java.home"), "bin").toString();
        String executable = System.getProperty("os.name").toLowerCase().contains("windows") ? "java.exe" : "java";
        return Path.of(bin, executable).toString();
    }

    private static String scriptPath() throws IOException {
        URI uri = URI.create(IdeaMcpServerHelper.class.getResource("/idea_mcp_server.java").toString());
        Path resource;
        try {
            resource = Path.of(uri);
        } catch (Exception insideAJar) {
            resource = Files.createTempFile("idea_mcp_server", ".java");
            try (InputStream in = IdeaMcpServerHelper.class.getResourceAsStream("/idea_mcp_server.java")) {
                Files.copy(in, resource, StandardCopyOption.REPLACE_EXISTING);
            }
            resource.toFile().deleteOnExit();
        }
        return resource.toAbsolutePath().toString();
    }

    private static void waitForPort(int port, Process process) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PORT_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("fake IDEA MCP server exited early");
            }
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException notUpYet) {
                TimeUnit.MILLISECONDS.sleep(200);
            }
        }
        throw new IllegalStateException("fake IDEA MCP server did not open port " + port
                + " within " + PORT_TIMEOUT_SECONDS + " seconds");
    }
}
