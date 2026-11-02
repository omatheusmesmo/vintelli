package dev.omatheusmesmo.vintelli.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import dev.omatheusmesmo.vintelli.tui.TerminalToolWindow;
import dev.omatheusmesmo.vintelli.tui.VintelliShell;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Default-deny browser action checks shared by JVM and native runners. */
@QuarkusTest
@WithTestResource(value = WebTerminalDefaultDenyResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class WebTerminalActionGateTest {

    private static final long WAIT_MILLIS = 15_000;

    @TestHTTPResource("/tui/ws")
    URI terminal;

    private final List<WebTerminalHandshakeClient> openClients = new ArrayList<>();

    @BeforeEach
    void resetSharedState() {
        CDI.current().select(TerminalToolWindow.class).get().closeSession();
        CDI.current().select(VintelliShell.class).get().reset();
    }

    @AfterEach
    void closeInteractiveShell() {
        CDI.current().select(TerminalToolWindow.class).get().closeSession();
    }

    @AfterEach
    void closeOpenClients() throws InterruptedException {
        for (WebTerminalHandshakeClient client : openClients) {
            client.close();
            client.awaitClosed(5_000);
        }
        openClients.clear();
    }

    @Test
    void webDeniesRunAndTerminalActionsFromKeysAndPaletteByDefault() throws Exception {
        WebTerminalHandshakeClient client = new WebTerminalHandshakeClient();
        openClients.add(client);
        client.connect(terminal, WebTerminalHandshakeClient.basicAuth(WebTerminalResource.USER,
                WebTerminalResource.PASSWORD), WebTerminalResource.TRUSTED_ORIGIN);
        VintelliShell shell = CDI.current().select(VintelliShell.class).get();

        client.sendInput("\u001b1");
        await(client, () -> shell.status().equals("Tool window: Run"));
        client.sendInput("\r");
        await(client, () -> shell.status().equals("Action disabled for web surface: run.execute"));

        client.sendInput("\u0010");
        await(client, () -> client.screen().contains("Search Everywhere"));
        client.sendInput("run.execute\r");
        await(client, () -> shell.status().equals("Action disabled for web surface: run.execute"));

        client.sendInput("\u001b2");
        await(client, () -> shell.status().equals("Tool window: Terminal"));
        client.sendInput("\r");
        await(client, () -> shell.status().equals("Action disabled for web surface: terminal.execute"));
        assertTrue(!client.screen().contains("Interactive shell started"), client::screen);
    }

    private static void await(WebTerminalHandshakeClient client, BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            client.awaitFrame(Math.min(250, Math.max(1, deadline - System.currentTimeMillis())));
        }
        assertTrue(condition.getAsBoolean(), () -> "timed out waiting for terminal screen: " + client.screen());
    }
}
