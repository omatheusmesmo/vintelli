package dev.omatheusmesmo.vintelli.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** Verifies that an idle browser terminal session is closed after its timeout. */
@QuarkusTest
@WithTestResource(value = WebTerminalSessionLimitResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class WebTerminalIdleTimeoutTest {

    @TestHTTPResource("/tui/ws")
    URI terminal;

    @Test
    void idleSessionIsClosedAfterTheTimeout() throws Exception {
        WebTerminalHandshakeClient client = new WebTerminalHandshakeClient();
        client.connect(terminal, WebTerminalHandshakeClient.basicAuth(WebTerminalResource.USER, WebTerminalResource.PASSWORD),
                WebTerminalResource.TRUSTED_ORIGIN);
        assertTrue(client.awaitClosed(5_000), "idle session must close after the configured timeout");
        client.close();
    }
}
