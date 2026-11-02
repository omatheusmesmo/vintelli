package dev.omatheusmesmo.vintelli.web;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URI;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Observable native-runner checks for the web surface command gate. */
@QuarkusIntegrationTest
@WithTestResource(value = WebTerminalDefaultDenyResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class WebTerminalActionGateIT {

    @TestHTTPResource("/tui/ws")
    URI terminal;

    private WebTerminalHandshakeClient client;

    @Test
    void webKeysAndPaletteCannotStartRunsOrATerminalByDefault() throws Exception {
        client = new WebTerminalHandshakeClient();
        client.connect(terminal, WebTerminalHandshakeClient.basicAuth(WebTerminalResource.USER,
                WebTerminalResource.PASSWORD), WebTerminalResource.TRUSTED_ORIGIN);

        client.sendInput("\u001b1\r");
        client.awaitFrame(1_000);
        assertFalse(client.screen().contains("Starting maven:"), client::screen);

        client.sendInput("\u0010");
        client.awaitFrame(500);
        client.sendInput("run.execute\r");
        client.awaitFrame(1_000);
        assertFalse(client.screen().contains("Starting maven:"), client::screen);

        client.sendInput("\u001b2\r");
        client.awaitFrame(1_000);
        assertFalse(client.screen().contains("Interactive shell started"), client::screen);
    }

    @AfterEach
    void closeClient() throws Exception {
        if (client != null) {
            client.close();
            client.awaitClosed(5_000);
        }
    }
}
