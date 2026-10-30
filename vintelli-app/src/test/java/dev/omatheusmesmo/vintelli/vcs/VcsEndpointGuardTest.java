package dev.omatheusmesmo.vintelli.vcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;

/**
 * The Git state of the project must stay behind the global Host and Origin
 * guard on every path spelling the router resolves to the VCS routes: a
 * rebound DNS name or a foreign browser origin gets 403 and never reaches the
 * provider, while a plain loopback caller still reads the state.
 */
@QuarkusTest
@WithTestResource(value = TemporaryGitRepositoryResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class VcsEndpointGuardTest {

    private static final List<String> STATUS_SPELLINGS = List.of("/vcs/status", "//vcs/status", "/./vcs/status",
            "/%76cs/status", "/vcs/status/", "/vcs//status", "/vcs/%73tatus", "/vcs/status;x=1",
            "/vcs/x/../status");
    private static final List<String> DIFF_SPELLINGS = List.of("/vcs/diff?file=tracked.txt",
            "//vcs/diff?file=tracked.txt", "/%76cs/diff?file=tracked.txt", "/vcs/%64iff?file=tracked.txt");

    @TestHTTPResource
    URI baseUri;

    @Test
    void foreignHostGets403OnEveryPathSpellingOfTheVcsRoutes() {
        List<String> notRejected = new ArrayList<>();
        for (String path : concat(STATUS_SPELLINGS, DIFF_SPELLINGS)) {
            int status = rawStatus(path, "rebound.example.org", null);
            if (status != 403) {
                notRejected.add(path + " -> " + status);
            }
        }
        assertTrue(notRejected.isEmpty(), "foreign Host must get 403 on every spelling: " + notRejected);
    }

    @Test
    void foreignOriginGets403OnEveryPathSpellingOfTheVcsRoutes() {
        List<String> notRejected = new ArrayList<>();
        for (String path : concat(STATUS_SPELLINGS, DIFF_SPELLINGS)) {
            int status = rawStatus(path, null, "http://browser.example:9999");
            if (status != 403) {
                notRejected.add(path + " -> " + status);
            }
        }
        assertTrue(notRejected.isEmpty(), "foreign Origin must get 403 on every spelling: " + notRejected);
    }

    @Test
    void loopbackCallerWithoutAnOriginStillReadsTheState() {
        assertEquals(200, rawStatus("/vcs/status", null, null));
        assertEquals(200, rawStatus("/vcs/diff?file=tracked.txt", null, null));
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private int rawStatus(String path, String host, String origin) {
        String effectiveHost = host == null ? baseUri.getHost() + ":" + baseUri.getPort() : host;
        StringBuilder request = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(effectiveHost).append("\r\n");
        if (origin != null) {
            request.append("Origin: ").append(origin).append("\r\n");
        }
        request.append("Connection: close\r\n\r\n");
        try (Socket socket = new Socket(baseUri.getHost(), baseUri.getPort())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            return Integer.parseInt(reader.readLine().split(" ")[1]);
        } catch (IOException e) {
            throw new IllegalStateException("raw request failed: " + path, e);
        }
    }
}
