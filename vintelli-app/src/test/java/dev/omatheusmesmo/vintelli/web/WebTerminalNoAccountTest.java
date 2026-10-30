package dev.omatheusmesmo.vintelli.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/**
 * Enabling the surface without configuring an account must leave it closed:
 * no default or empty credential opens the upgrade, every handshake stays on
 * the 401 challenge.
 */
@QuarkusTest
@TestProfile(WebTerminalNoAccountResource.class)
class WebTerminalNoAccountTest {

    @TestHTTPResource("/tui/ws")
    URI terminal;

    @Test
    void noCredentialOpensTheSurfaceWhenNoAccountIsConfigured() {
        for (String authorization : new String[] {null, WebTerminalHandshakeClient.basicAuth("", ""),
                WebTerminalHandshakeClient.basicAuth("terminal", ""),
                WebTerminalHandshakeClient.basicAuth("admin", "admin"),
                WebTerminalHandshakeClient.basicAuth("quarkus", "quarkus")}) {
            WebTerminalHandshakeClient.UpgradeFailure failure = assertThrows(
                    WebTerminalHandshakeClient.UpgradeFailure.class,
                    () -> new WebTerminalHandshakeClient().connect(terminal, authorization, null));
            assertEquals(401, failure.status(), () -> "credential " + authorization);
        }
    }
}
