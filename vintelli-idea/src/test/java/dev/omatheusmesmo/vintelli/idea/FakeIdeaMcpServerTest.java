package dev.omatheusmesmo.vintelli.idea;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeIdeaMcpServerTest {

    @Test
    void aRejectedRequestDoesNotEstablishAnMcpSession() throws Exception {
        try (FakeIdeaMcpServer server = new FakeIdeaMcpServer()) {
            server.rejectWith(404);

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(HttpRequest.newBuilder(URI.create(server.streamUrl()))
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                            HttpResponse.BodyHandlers.ofString());

            assertEquals(404, response.statusCode());
            assertTrue(response.headers().firstValue("mcp-session-id").isEmpty());
        }
    }
}
