package dev.omatheusmesmo.vintelli.capabilities;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;

import dev.omatheusmesmo.vintelli.lsp.FakeLspServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/** Starts the DAP twin and wires an isolated fake jdt.ls profile into JVM and native tests. */
public final class JavaDebugCapabilityProbeResource implements QuarkusTestResourceLifecycleManager {

    private static JavaDebugAdapterTwin adapter;
    private static Path projectRoot;
    private static int attachPort;

    @Override
    public Map<String, String> start() {
        try {
            adapter = new JavaDebugAdapterTwin();
            projectRoot = Files.createTempDirectory("vintelli-dap-probe-");
            Files.writeString(projectRoot.resolve("pom.xml"), """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>example</groupId><artifactId>debug-probe</artifactId><version>1</version>
                    </project>
                    """);
            Path java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
            Path classpath = Path.of(JavaDebugTwin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path args = projectRoot.resolve("debug-wrapper.args");
            Files.writeString(args, "-cp\n" + quote(classpath.toString()) + "\n"
                    + JavaDebugTwin.class.getName() + "\n");
            Path wrapper = projectRoot.resolve("mvnw");
            Files.writeString(wrapper, "#!/bin/sh\nexec " + shell(java.toString()) + " "
                    + shell("@" + args) + " \"$@\"\n");
            if (!wrapper.toFile().setExecutable(true)) {
                throw new IOException("Cannot mark the fixed debug wrapper executable");
            }
            Files.writeString(projectRoot.resolve("mvnw.cmd"), "@echo off\r\n\"" + java + "\" \"@"
                    + args + "\" %*\r\n");
            try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                attachPort = reservation.getLocalPort();
            }
            Path bundle = renamedBundle(projectRoot);
            String options = "{\"bundles\":[\"" + json(bundle.toString()) + "\"]}";
            String lspArguments = "-cp," + FakeLspServer.classpath() + "," + FakeLspServer.class.getName()
                    + ",--java-debug,--dap-port=" + adapter.port();
            return Map.ofEntries(
                    Map.entry("vintelli.inspect.enabled", "true"),
                    Map.entry("vintelli.debug.enabled", "true"),
                    Map.entry("vintelli.debug.probe.attach-port", Integer.toString(attachPort)),
                    Map.entry("vintelli.local.project-root", projectRoot.toString()),
                    Map.entry("vintelli.lsp.profile", "debug-probe"),
                    Map.entry("vintelli.lsp.profiles.debug-probe.command", java.toString()),
                    Map.entry("vintelli.lsp.profiles.debug-probe.args", lspArguments),
                    Map.entry("vintelli.lsp.profiles.debug-probe.workspace-root", projectRoot.toString()),
                    Map.entry("vintelli.lsp.profiles.debug-probe.java.home", System.getProperty("java.home")),
                    Map.entry("vintelli.lsp.profiles.debug-probe.initialization-options", options));
        } catch (Exception failure) {
            stop();
            throw new IllegalStateException("Could not start the Java debug capability test resource", failure);
        }
    }

    static JavaDebugAdapterTwin adapter() {
        if (adapter == null) {
            throw new IllegalStateException("Java debug adapter twin is not running");
        }
        return adapter;
    }

    static int attachPort() {
        if (projectRoot == null) {
            throw new IllegalStateException("Java debug capability test resource has not started");
        }
        return attachPort;
    }

    static Path projectRoot() {
        if (projectRoot == null) {
            throw new IllegalStateException("Java debug capability test resource has not started");
        }
        return projectRoot;
    }

    private static Path renamedBundle(Path root) throws Exception {
        byte[] content = "test-only Java debug bundle placeholder".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        Path bundle = root.resolve("org.eclipse.jdt.ls.java-debug-renamed-" + digest + ".jar");
        Files.write(bundle, content);
        return bundle;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String shell(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void stop() {
        if (adapter != null) {
            adapter.close();
            adapter = null;
        }
        if (projectRoot != null) {
            try (var paths = Files.walk(projectRoot)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException ignored) {
            } finally {
                projectRoot = null;
                attachPort = 0;
            }
        }
    }
}
