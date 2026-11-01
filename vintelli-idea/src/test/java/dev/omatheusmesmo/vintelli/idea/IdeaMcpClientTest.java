package dev.omatheusmesmo.vintelli.idea;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdeaMcpClientTest {

    private static final JsonMapper MCP_JSON = JsonMapper.builder().build();
    private static final McpJsonMapper MAPPER = new JacksonMcpJsonMapper(MCP_JSON);
    private static final JsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator(MCP_JSON);

    @Test
    void connectsAndListsIdeaToolsOnStreamEndpoint() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), null, null, "test", MAPPER, VALIDATOR)) {

            assertEquals(ConnectionState.CONNECTED, client.reconnect().state(), client.status().detail());

            List<IdeaTool> tools = client.listIdeaTools();
            List<String> names = tools.stream().map(IdeaTool::name).toList();
            assertTrue(names.contains("get_run_configurations"), names.toString());
            assertTrue(names.contains("execute_terminal_command"), names.toString());

            List<IdeaTool> nextRound = client.listIdeaTools();
            assertEquals(3, nextRound.size(), names.toString());
            assertTrue(server.paths().stream().allMatch("/stream"::equals), server.paths().toString());
        }
    }

    @Test
    void contributesTheLiveIdeaConnectionStateToTheStatusBar() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), null, null, "test", MAPPER, VALIDATOR)) {
            assertEquals("IDEA:off", client.statusBarWidgets().getFirst().compactText());

            client.reconnect();

            assertEquals("IDEA:on", client.statusBarWidgets().getFirst().compactText());
        }
    }

    @Test
    void sendsProjectPathAndAuthTokenHeaders() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), "~/work/vintelli", "idea-token", "test", MAPPER, VALIDATOR)) {

            assertEquals(ConnectionState.CONNECTED, client.reconnect().state(), client.status().detail());
            client.listIdeaTools();

            assertEquals("~/work/vintelli", server.receivedHeader("IJ_MCP_SERVER_PROJECT_PATH"));
            assertEquals("idea-token", server.receivedHeader("IJ_MCP_AUTH_TOKEN"));
        }
    }

    @Test
    void reportsAuthRequiredOn401() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), null, null, "test", MAPPER, VALIDATOR)) {
            server.rejectWith(401);

            assertEquals(ConnectionState.AUTH_REQUIRED, client.reconnect().state(), client.status().detail());
        }
    }

    @Test
    void reportsConsentPendingOn403() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), null, null, "test", MAPPER, VALIDATOR)) {
            server.rejectWith(403);

            assertEquals(ConnectionState.CONSENT_PENDING, client.reconnect().state(), client.status().detail());
        }
    }

    @Test
    void reportsEndpointNotFoundOn404() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), null, null, "test", MAPPER, VALIDATOR)) {
            server.rejectWith(404);

            assertEquals(ConnectionState.ENDPOINT_NOT_FOUND, client.reconnect().state(), client.status().detail());
        }
    }

    @Test
    void reportsUnreachableOnAClosedPort() throws Exception {
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        try (IdeaMcpClient client = new IdeaMcpClient("http://127.0.0.1:" + deadPort, null, null, "test", MAPPER, VALIDATOR)) {

            assertEquals(ConnectionState.UNREACHABLE, client.reconnect().state(), client.status().detail());
            assertTrue(client.listIdeaTools().isEmpty());

            IdeaToolCall call = client.callIdeaTool("get_run_configurations", Map.of());
            assertTrue(call.isError(), call.resultText());
        }
    }

    @Test
    void callsIdeaToolAndMapsResult() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), null, null, "test", MAPPER, VALIDATOR)) {
            client.reconnect();

            IdeaToolCall call = client.callIdeaTool("execute_run_configuration", Map.of("name", "Vintelli"));
            assertEquals("execute_run_configuration", call.tool());
            assertTrue(!call.resultText().isEmpty(), call.resultText());
            assertTrue(!call.isError());
        }
    }

    @Test
    void mapsToolLevelErrorsWithoutBreakingTheSession() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), null, null, "test", MAPPER, VALIDATOR)) {
            client.reconnect();

            IdeaToolCall call = client.callIdeaTool(FakeIdeaMcpServer.ERROR_TOOL, Map.of());

            assertTrue(call.isError(), call.resultText());
            assertEquals("IDEA refused the tool", call.resultText());
            assertEquals(ConnectionState.CONNECTED, client.status().state(), client.status().detail());
        }
    }

    @Test
    void classifiesAMalformedToolResponseAsAnError() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), null, null, "test", MAPPER, VALIDATOR)) {
            client.reconnect();

            IdeaToolCall call = client.callIdeaTool(FakeIdeaMcpServer.MALFORMED_TOOL, Map.of());

            assertTrue(call.isError(), call.resultText());
            assertEquals(ConnectionState.ERROR, client.status().state(), client.status().detail());
        }
    }

    @Test
    void returnsAFailedCallInsteadOfThrowingAfterClose() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer()) {
            IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), null, null, "test", MAPPER, VALIDATOR);
            client.reconnect();
            client.close();

            IdeaToolCall call = client.callIdeaTool("get_run_configurations", Map.of());

            assertTrue(call.isError(), call.resultText());
            assertTrue(call.resultText().contains("closed"), call.resultText());
        }
    }

    @Test
    void completesToolCallsWhenOnlyOneCommonPoolWorkerIsFree() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<CompletableFuture<Void>> hogs = new ArrayList<>();
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), null, null, "test", MAPPER, VALIDATOR)) {
            client.reconnect();
            for (int i = 1; i < ForkJoinPool.commonPool().getParallelism(); i++) {
                hogs.add(CompletableFuture.runAsync(() -> awaitQuietly(release)));
            }

            IdeaToolCall call = client.callIdeaTool("get_run_configurations", Map.of(), Duration.ofSeconds(10));

            assertTrue(!call.isError(), call.resultText());
        } finally {
            release.countDown();
            hogs.forEach(CompletableFuture::join);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void timesOutLongRunningToolCalls() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), null, null, "test", MAPPER, VALIDATOR)) {
            server.delayToolCalls(Duration.ofSeconds(2));
            client.reconnect();

            IdeaToolCall call = client.callIdeaTool("execute_terminal_command", Map.of("command", "slow"),
                    Duration.ofMillis(300));

            assertTrue(call.isError(), call.resultText());
            assertEquals(ConnectionState.TIMEOUT, client.status().state(), client.status().detail());
        }
    }

    @Test
    void recoversByReconnectingWhenIdeaRestartedWithoutLosingState() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer();
                IdeaMcpClient client = new IdeaMcpClient(server.baseUrl(), null, null, "test", MAPPER, VALIDATOR)) {
            client.reconnect();
            assertEquals(3, client.listIdeaTools().size());

            server.stop();

            assertTrue(client.listIdeaTools().isEmpty(), "IDEA down means no tools");
            assertEquals(ConnectionState.UNREACHABLE, client.status().state(), client.status().detail());
            assertEquals(ConnectionState.UNREACHABLE, client.reconnect().state(), client.status().detail());
        }
    }
}
