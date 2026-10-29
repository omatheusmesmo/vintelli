package dev.omatheusmesmo.vintelli.mcp;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the headless app over HTTP. Under {@code -Dnative} it runs against the native runner,
 * so reflection, serialization and transport problems surface here instead of at runtime.
 */
@QuarkusIntegrationTest
class VintelliMcpEndpointIT {

    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
            "protocolVersion":"2025-06-18","capabilities":{},
            "clientInfo":{"name":"vintelli-it","version":"0"}}}""";

    @TestHTTPResource("/mcp")
    URI mcp;

    @Test
    void answersInitializeOverStreamableHttp() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(mcp)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("protocolVersion"), response.body());
    }
}
