package dev.omatheusmesmo.vintelli.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class HttpPortSettingsTest {

    @Test
    void interactiveModesBindAFreePortAndServeKeepsThePinnedOne() {
        assertEquals(Map.of("quarkus.http.port", "0"), HttpPortSettings.forMode(RunMode.TUI));
        assertEquals(Map.of("quarkus.http.port", "0"), HttpPortSettings.forMode(RunMode.CLI));
        assertEquals(Map.of("quarkus.http.port", "0"), HttpPortSettings.forMode(RunMode.REPL));
        assertEquals(Map.of(), HttpPortSettings.forMode(RunMode.SERVER));
    }

    @Test
    void aTakenExplicitPortNamesThePortAndTheFix() throws IOException {
        try (ServerSocket holder = loopback()) {
            int port = holder.getLocalPort();
            Properties properties = new Properties();
            properties.setProperty("quarkus.http.port", String.valueOf(port));

            Optional<String> refusal = HttpPortSettings.takenExplicitPort(properties, Map.of());

            assertTrue(refusal.isPresent());
            assertTrue(refusal.get().contains("port " + port + " on 127.0.0.1 is already in use"), refusal.get());
            assertTrue(refusal.get().contains("quarkus.http.port"), refusal.get());
        }
    }

    @Test
    void aTakenPortFromTheEnvironmentIsReportedToo() throws IOException {
        try (ServerSocket holder = loopback()) {
            int port = holder.getLocalPort();
            Map<String, String> env = Map.of("QUARKUS_HTTP_PORT", String.valueOf(port));

            assertTrue(HttpPortSettings.takenExplicitPort(new Properties(), env).isPresent());
        }
    }

    @Test
    void aFreeExplicitPortIsAccepted() throws IOException {
        int port;
        try (ServerSocket probe = loopback()) {
            port = probe.getLocalPort();
        }
        Properties properties = new Properties();
        properties.setProperty("quarkus.http.port", String.valueOf(port));

        assertFalse(HttpPortSettings.takenExplicitPort(properties, Map.of()).isPresent());
    }

    @Test
    void noExplicitPortOrAnEphemeralOneIsNeverReported() throws IOException {
        assertFalse(HttpPortSettings.takenExplicitPort(new Properties(), Map.of()).isPresent());
        Properties properties = new Properties();
        properties.setProperty("quarkus.http.port", "0");

        assertFalse(HttpPortSettings.takenExplicitPort(properties, Map.of()).isPresent());
    }

    private static ServerSocket loopback() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.bind(new InetSocketAddress("127.0.0.1", 0));
        return socket;
    }
}
