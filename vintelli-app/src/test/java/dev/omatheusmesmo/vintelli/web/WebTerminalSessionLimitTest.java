package dev.omatheusmesmo.vintelli.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a connection above the configured cap is rejected with HTTP
 * 503 and that accepted sessions complete their client close handshake.
 */
@QuarkusTest
@WithTestResource(value = WebTerminalSessionLimitResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class WebTerminalSessionLimitTest {

    @TestHTTPResource("/tui/ws")
    URI terminal;

    @Test
    void connectionAboveTheCapIsRejectedWith503() throws Exception {
        WebTerminalHandshakeClient first = new WebTerminalHandshakeClient();
        first.connect(terminal, WebTerminalHandshakeClient.basicAuth(WebTerminalResource.USER, WebTerminalResource.PASSWORD),
                WebTerminalResource.TRUSTED_ORIGIN);
        WebTerminalHandshakeClient second = new WebTerminalHandshakeClient();
        second.connect(terminal, WebTerminalHandshakeClient.basicAuth(WebTerminalResource.USER, WebTerminalResource.PASSWORD),
                WebTerminalResource.TRUSTED_ORIGIN);
        try {
            WebTerminalHandshakeClient third = new WebTerminalHandshakeClient();
            try {
                third.connect(terminal,
                        WebTerminalHandshakeClient.basicAuth(WebTerminalResource.USER, WebTerminalResource.PASSWORD),
                        WebTerminalResource.TRUSTED_ORIGIN);
                fail("third upgrade should have been rejected");
            } catch (WebTerminalHandshakeClient.UpgradeFailure failure) {
                assertEquals(503, failure.status(), "third upgrade must be rejected with 503");
            }
            third.close();
        } finally {
            second.close();
            first.close();
            assertTrue(second.awaitClosed(5_000), "second capped session must release its slot before the next test");
            assertTrue(first.awaitClosed(5_000), "first capped session must release its slot before the next test");
        }
    }

}
