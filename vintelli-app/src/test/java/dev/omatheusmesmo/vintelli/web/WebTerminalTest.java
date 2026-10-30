package dev.omatheusmesmo.vintelli.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.omatheusmesmo.vintelli.tui.VintelliShell;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Enabled browser terminal: the shared Host and Origin guard rejects foreign
 * handshakes on every path spelling before anything routes, basic
 * authentication gates the upgrade, the happy path streams ANSI frames of the
 * rendered shell, keys route through the shell into the tool windows, and the
 * viewport (resize) belongs to exactly one session. {@link WebTerminalIT}
 * replays the same assertions on the native runner.
 */
@QuarkusTest
@WithTestResource(value = WebTerminalResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class WebTerminalTest {

    private static final Pattern CURSOR_MOVE = Pattern.compile("\u001b\\[(\\d+);(\\d+)H");
    private static final int DEFAULT_ROWS = 24;
    private static final int MAX_ROWS = 200;
    private static final int MAX_COLUMNS = 400;
    private static final int OVERSIZED_MESSAGE_BYTES = 2 * 1024 * 1024;
    private static final long WAIT_MILLIS = 15_000;
    private static final int RESET_ATTEMPTS = 5;
    private static final String ALT_ONE = "\u001b1";
    private static final List<String> UPGRADE_PATHS = List.of("/tui/ws", "//tui/ws", "/./tui/ws", "/tui/ws/",
            "/%74ui/ws", "/tui//ws", "/tui/ws;x=1", "/tui/x/../ws");

    @TestHTTPResource
    URI baseUri;

    @TestHTTPResource("/tui/ws")
    URI terminal;

    @TestHTTPResource("/tui/render")
    URI render;

    private final List<WebTerminalHandshakeClient> openClients = new ArrayList<>();

    @BeforeEach
    void resetSharedState() {
        CDI.current().select(VintelliShell.class).get().reset();
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
    void foreignHostIsRejectedOnEveryUpgradePathSpelling() {
        List<String> notRejected = new ArrayList<>();
        for (String path : UPGRADE_PATHS) {
            int status = rawUpgradeStatus(path, "rebound.example.org", null, null);
            if (status != 403) {
                notRejected.add(path + " -> " + status);
            }
        }
        assertTrue(notRejected.isEmpty(), "foreign Host must get 403 on every spelling: " + notRejected);
    }

    @Test
    void foreignOriginIsRejectedOnEveryUpgradePathSpelling() {
        List<String> notRejected = new ArrayList<>();
        for (String path : UPGRADE_PATHS) {
            int status = rawUpgradeStatus(path, null, WebTerminalResource.FOREIGN_ORIGIN, null);
            if (status != 403) {
                notRejected.add(path + " -> " + status);
            }
        }
        assertTrue(notRejected.isEmpty(), "foreign Origin must get 403 on every spelling: " + notRejected);
    }

    @Test
    void foreignOriginIsRejectedBeforeAuth() {
        assertEquals(403, rawUpgradeStatus("/tui/ws", null, WebTerminalResource.FOREIGN_ORIGIN, basic()));
    }

    @Test
    void upgradeFollowsTheBasicAccountOnly() {
        String origin = WebTerminalResource.TRUSTED_ORIGIN;
        assertThrows(WebTerminalHandshakeClient.UpgradeFailure.class, () -> handshakeUp(null, origin));
        assertThrows(WebTerminalHandshakeClient.UpgradeFailure.class,
                () -> handshakeUp(basic(WebTerminalResource.USER, "wrong-password"), origin));
    }

    @Test
    void happyPathStreamsTheRenderedShell() throws Exception {
        WebTerminalHandshakeClient client = handshakeUp(basic(), WebTerminalResource.TRUSTED_ORIGIN);
        awaitScreen(client, screen -> screen.contains("Project") && screen.contains("Editor"));
    }

    @Test
    void keysRouteIntoTheSharedShell() throws Exception {
        WebTerminalHandshakeClient client = handshakeUp(basic(), WebTerminalResource.TRUSTED_ORIGIN);
        resetToRunWindow(client);

        client.sendInput("2");
        awaitScreen(client, screen -> screen.contains("Tool window: Terminal"));

        given().when()
                .get(render)
                .then()
                .statusCode(200)
                .body("status", containsString("Tool window: Terminal"));
    }

    @Test
    void sessionsHaveTheirOwnViewport() throws Exception {
        WebTerminalHandshakeClient first = handshakeUp(basic(), WebTerminalResource.TRUSTED_ORIGIN);
        WebTerminalHandshakeClient second = handshakeUp(basic(), WebTerminalResource.TRUSTED_ORIGIN);
        awaitScreen(first, screen -> screen.contains("Project"));
        awaitScreen(second, screen -> screen.contains("Project"));

        first.sendControl("resize 120x40");
        await(first, () -> first.lastContentRow() >= DEFAULT_ROWS, "first session to draw below the default rows");

        assertTrue(second.lastContentRow() < DEFAULT_ROWS, second::screen);
    }

    @Test
    void forgedResizesStayWithinTheClampedBuffer() throws Exception {
        WebTerminalHandshakeClient client = handshakeUp(basic(), WebTerminalResource.TRUSTED_ORIGIN);

        client.sendControl("resize 100000x100000");
        await(client, () -> client.lastContentRow() >= DEFAULT_ROWS, "clamped resize to take effect");
        resetToRunWindow(client);
        client.sendInput("3");
        awaitScreen(client, screen -> screen.contains("Tool window: Problems"));

        for (int[] move : cursorMoves(client.raw())) {
            assertTrue(move[0] <= MAX_ROWS, () -> "row beyond the clamp: " + move[0]);
            assertTrue(move[1] <= MAX_COLUMNS, () -> "column beyond the clamp: " + move[1]);
        }
    }

    @Test
    void oversizedMessageClosesOnlyTheOffendingConnection() throws Exception {
        WebTerminalHandshakeClient abusive = handshakeUp(basic(), WebTerminalResource.TRUSTED_ORIGIN);
        WebTerminalHandshakeClient bystander = handshakeUp(basic(), WebTerminalResource.TRUSTED_ORIGIN);
        awaitScreen(abusive, screen -> screen.contains("Project"));
        awaitScreen(bystander, screen -> screen.contains("Project"));

        abusive.sendInput("x".repeat(OVERSIZED_MESSAGE_BYTES));

        assertTrue(abusive.awaitClosed(15_000), "an oversized message must close its connection");
        resetToRunWindow(bystander);
    }

    @Test
    void malformedResizeFramesAreIgnored() throws Exception {
        WebTerminalHandshakeClient client = handshakeUp(basic(), WebTerminalResource.TRUSTED_ORIGIN);
        awaitScreen(client, screen -> screen.contains("Project"));

        for (String forged : List.of("resize", "resize 80", "resize axb", "resize -5x-5", "resize 0x0",
                "resize 99999999999x99999999999", "resize 80x24x1", "reboot 80x24", "")) {
            client.sendControl(forged);
        }
        resetToRunWindow(client);
        assertTrue(client.lastContentRow() < DEFAULT_ROWS, client::screen);
    }

    private static void resetToRunWindow(WebTerminalHandshakeClient client) throws InterruptedException {
        for (int attempt = 0; attempt < RESET_ATTEMPTS; attempt++) {
            client.sendInput(ALT_ONE);
            if (holdsRunWindow(client)) {
                return;
            }
        }
        assertTrue(holdsRunWindow(client), () -> "Run window not reached after " + RESET_ATTEMPTS
                + " attempts, screen: " + client.screen());
    }

    private static boolean holdsRunWindow(WebTerminalHandshakeClient client) throws InterruptedException {
        return awaitCondition(client, () -> client.screen().contains("Tool window: Run"), WAIT_MILLIS);
    }

    private static List<int[]> cursorMoves(String stream) {
        List<int[]> moves = new ArrayList<>();
        Matcher matcher = CURSOR_MOVE.matcher(stream);
        while (matcher.find()) {
            moves.add(new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))});
        }
        return moves;
    }

    private static void awaitScreen(WebTerminalHandshakeClient client, Predicate<String> condition)
            throws InterruptedException {
        await(client, () -> condition.test(client.screen()), "screen " + client.screen());
    }

    private static void await(WebTerminalHandshakeClient client, BooleanSupplier condition, String expectation)
            throws InterruptedException {
        if (!awaitCondition(client, condition, WAIT_MILLIS)) {
            throw new AssertionError("timed out waiting for " + expectation + ", screen: " + client.screen());
        }
    }

    private static boolean awaitCondition(WebTerminalHandshakeClient client, BooleanSupplier condition,
            long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            client.awaitFrame(250);
        }
        return condition.getAsBoolean();
    }

    private static String basic() {
        return basic(WebTerminalResource.USER, WebTerminalResource.PASSWORD);
    }

    private static String basic(String user, String password) {
        return WebTerminalHandshakeClient.basicAuth(user, password);
    }

    private WebTerminalHandshakeClient handshakeUp(String authorization, String origin) {
        WebTerminalHandshakeClient client = new WebTerminalHandshakeClient();
        client.connect(terminal, authorization, origin);
        openClients.add(client);
        return client;
    }

    private int rawUpgradeStatus(String path, String host, String origin, String authorization) {
        String key = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        String effectiveHost = host == null ? baseUri.getHost() + ":" + baseUri.getPort() : host;
        StringBuilder request = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(effectiveHost).append("\r\n")
                .append("Upgrade: websocket\r\n")
                .append("Connection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                .append("Sec-WebSocket-Version: 13\r\n");
        if (origin != null) {
            request.append("Origin: ").append(origin).append("\r\n");
        }
        if (authorization != null) {
            request.append("Authorization: ").append(authorization).append("\r\n");
        }
        request.append("Content-Length: 0\r\n\r\n");
        try (Socket socket = new Socket(baseUri.getHost(), baseUri.getPort())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            return Integer.parseInt(reader.readLine().split(" ")[1]);
        } catch (IOException e) {
            throw new IllegalStateException("raw handshake failed", e);
        }
    }
}
