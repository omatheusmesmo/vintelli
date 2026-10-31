package dev.omatheusmesmo.vintelli.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** Exercises project file actions through the authenticated headless terminal. */
@QuarkusTest
@WithTestResource(value = ProjectTreeResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class ProjectTreeTest {
    @TestHTTPResource("/tui/ws")
    URI terminal;

    @Test
    void createsRenamesAndDeletesThroughTheHeadlessSurface() throws Exception {
        WebTerminalHandshakeClient client = new WebTerminalHandshakeClient();
        client.connect(terminal, WebTerminalHandshakeClient.basicAuth(WebTerminalResource.USER, WebTerminalResource.PASSWORD),
                WebTerminalResource.TRUSTED_ORIGIN);
        try {
            await(client, () -> client.screen().contains("seed.txt"));
            action(client, "New Project File", "New file:");
            client.sendInput("created.txt\r");
            Path created = ProjectTreeResource.project.resolve("created.txt");
            await(client, () -> Files.exists(created));

            action(client, "Rename Project Entry", "Rename:");
            client.sendInput("renamed.txt\r");
            Path renamed = ProjectTreeResource.project.resolve("renamed.txt");
            await(client, () -> Files.exists(renamed));
            assertFalse(Files.exists(created));

            action(client, "Delete Project Entry", "Delete '");
            assertTrue(Files.exists(renamed));
            client.sendInput("y");
            await(client, () -> !Files.exists(renamed));

            Path external = ProjectTreeResource.project.resolve("external.txt");
            Files.writeString(external, "created outside Vintelli");
            action(client, "Refresh Project Tree", "external.txt");
            await(client, () -> client.screen().contains("external.txt"));
        } finally {
            client.close();
            client.awaitClosed(5_000);
        }
    }

    private static void action(WebTerminalHandshakeClient client, String action, String expected) throws Exception {
        client.sendInput("\u0010");
        await(client, () -> client.screen().contains("Search"));
        client.sendInput(action);
        await(client, () -> client.screen().contains(action));
        client.sendInput("\r");
        await(client, () -> client.screen().contains(expected));
    }

    private static void await(WebTerminalHandshakeClient client, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            client.awaitFrame(100);
        }
        assertTrue(condition.getAsBoolean(), client::screen);
    }
}
