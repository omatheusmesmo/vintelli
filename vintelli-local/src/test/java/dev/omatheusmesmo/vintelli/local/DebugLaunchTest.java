package dev.omatheusmesmo.vintelli.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability.RunRequest;

class DebugLaunchTest {
    @TempDir Path directory;

    @Test
    void knownLaunchesSuspendOnlyTheDebuggeeAndBindLoopback() {
        assertEquals(List.of("-Ddebug=5123", "-DdebugHost=127.0.0.1", "-Dsuspend=y"),
                DebugLaunch.arguments("maven:quarkus:dev", 5123));
        for (String id : List.of("maven:spring-boot:run", "maven:test")) {
            String argument = DebugLaunch.arguments(id, 5123).getFirst();
            assertTrue(argument.contains("server=y,suspend=y,address=127.0.0.1:5123"), argument);
            assertFalse(argument.contains("address=*:"));
        }
        assertFalse(DebugLaunch.support("gradle:test").available());
        assertFalse(DebugLaunch.support("maven:exec:java").available());
    }

    @Test
    void refusesBusyInvalidUnknownAndUnsupportedWithoutStartingAProcess() throws Exception {
        Files.writeString(directory.resolve("pom.xml"), "<project/>");
        LocalRunCapability provider = LocalRunCapability.of(new LocalProjectRoot(directory), Duration.ofSeconds(5));
        var request = new RunRequest("maven:test", directory, Map.of());
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var busy = provider.startDebug(request, socket.getLocalPort(), line -> fail(line), end -> fail(end.toString()));
            assertTrue(busy.failureOf().orElseThrow().reason().contains("busy"));
        }
        var invalid = provider.startDebug(request, 0, line -> fail(line), end -> fail(end.toString()));
        assertTrue(invalid.failureOf().orElseThrow().reason().contains("between 1 and 65535"));
        assertFalse(provider.debugSupport("maven:unknown").available());
        assertFalse(provider.debugSupport("maven:compile").available());
        assertTrue(provider.startDebug(new RunRequest("maven:compile", directory, Map.of()), 5005,
                line -> fail(line), end -> fail(end.toString())).isFailure());
        assertTrue(provider.startDebug(new RunRequest("maven:test", directory.getParent(), Map.of()), 5005,
                line -> fail(line), end -> fail(end.toString())).isFailure());
    }
}
