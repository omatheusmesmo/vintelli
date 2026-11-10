package dev.omatheusmesmo.vintelli.capabilities;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public final class DebugLaunchProjectResource implements QuarkusTestResourceLifecycleManager {
    private Path directory;

    @Override
    public Map<String, String> start() {
        try {
            directory = Files.createTempDirectory("vintelli-debug-launch-");
            Files.writeString(directory.resolve("pom.xml"), """
                    <project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>app</artifactId><version>1</version>
                    <build><plugins><plugin><artifactId>quarkus-maven-plugin</artifactId></plugin>
                    <plugin><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build></project>
                    """);
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            Path classes = Path.of(JavaDebugTwin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path args = directory.resolve("wrapper.args");
            Files.writeString(args, "-cp\n" + quote(classes.toString()) + "\n" + JavaDebugTwin.class.getName() + "\n");
            Path wrapper = directory.resolve("mvnw");
            Files.writeString(wrapper, "#!/bin/sh\nexec " + shell(java.toString()) + " " + shell("@" + args) + " \"$@\"\n");
            if (!wrapper.toFile().setExecutable(true)) throw new IOException("Cannot mark wrapper executable");
            Files.writeString(directory.resolve("mvnw.cmd"), "@echo off\r\n\"" + java + "\" \"@" + args + "\" %*\r\n");
            int port;
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                port = socket.getLocalPort();
            }
            return Map.of("vintelli.local.project-root", directory.toString(), "vintelli.inspect.enabled", "true",
                    "vintelli.debug.port", String.valueOf(port));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String shell(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    @Override
    public void stop() {
        if (directory == null) return;
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
