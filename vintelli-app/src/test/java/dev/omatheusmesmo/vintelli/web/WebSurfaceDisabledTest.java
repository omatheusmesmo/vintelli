package dev.omatheusmesmo.vintelli.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;

/**
 * The browser terminal is closed by default: with
 * {@code vintelli.web.enabled} at its {@code false} default the upgrade
 * handshake answers 404, as if the route did not exist, never leaking that the
 * endpoint is there through an authentication challenge.
 * {@link WebSurfaceDisabledIT} replays the same assertion on the native
 * runner.
 */
@QuarkusTest
class WebSurfaceDisabledTest {

    @TestHTTPResource("/tui/ws")
    URI terminal;

    @Test
    void upgradeAnswersNotFoundWhileDisabled() {
        WebTerminalHandshakeClient client = new WebTerminalHandshakeClient();
        WebTerminalHandshakeClient.UpgradeFailure failure = assertThrows(
                WebTerminalHandshakeClient.UpgradeFailure.class, () -> client.connect(terminal, null, null));
        assertEquals(404, failure.status());
    }
}
