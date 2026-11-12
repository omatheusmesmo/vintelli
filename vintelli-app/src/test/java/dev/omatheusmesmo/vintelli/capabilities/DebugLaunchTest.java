package dev.omatheusmesmo.vintelli.capabilities;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@QuarkusTest
@WithTestResource(value = DebugLaunchProjectResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class DebugLaunchTest {
    private static final String PROBE = "/tui/debug-launch-probe";
    @TempDir Path directory;

    @Test
    void realJdbAttachesToEachLaunchAndStopFreesThePort() throws Exception {
        int port = given().get(PROBE).then().statusCode(200).extract().path("port");
        for (String kind : List.of("test", "quarkus", "spring")) {
            given().queryParam("kind", kind).post(PROBE).then().statusCode(200);
            Process debugger = null;
            try {
                await(() -> output().contains("Listening for transport dt_socket at address: " + port));
                assertTrue(output().contains("127.0.0.1:" + port), output());
                Path transcript = directory.resolve(kind + ".txt");
                String executable = System.getProperty("os.name").startsWith("Windows") ? "jdb.exe" : "jdb";
                Path jdb = Path.of(System.getProperty("java.home"), "bin", executable);
                debugger = new ProcessBuilder(jdb.toString(), "-connect",
                        "com.sun.jdi.SocketAttach:hostname=127.0.0.1,port=" + port)
                        .redirectErrorStream(true).redirectOutput(transcript.toFile()).start();
                debugger.getOutputStream().write("threads\nexit\n".getBytes(StandardCharsets.UTF_8));
                debugger.getOutputStream().close();
                assertTrue(debugger.waitFor(20, TimeUnit.SECONDS), "jdb did not finish");
                String text = Files.readString(transcript);
                assertEquals(0, debugger.exitValue(), text);
                assertTrue(text.contains("main") && text.contains("java.lang.Thread"), text);
            } finally {
                if (debugger != null && debugger.isAlive()) debugger.destroyForcibly();
                given().delete(PROBE).then().statusCode(200);
                await(() -> !given().get(PROBE).path("completion").toString().isEmpty());
            }
            try (ServerSocket released = new ServerSocket()) {
                released.setReuseAddress(true);
                released.bind(new InetSocketAddress("127.0.0.1", port));
            }
            assertEquals("Run cancelled", given().get(PROBE).path("completion"));
        }
    }

    @Test
    void busyPortIsReportedWithoutLaunchingTheWrapper() throws Exception {
        int port = given().get(PROBE).then().statusCode(200).extract().path("port");
        try (ServerSocket busy = new ServerSocket()) {
            busy.setReuseAddress(true);
            busy.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            String failure = given().post(PROBE).then().statusCode(200).extract().path("failure");
            assertTrue(failure.contains("127.0.0.1:" + port) && failure.contains("busy"), failure);
            assertEquals("", output());
        }
        given().queryParam("kind", "arbitrary-command").post(PROBE).then().statusCode(400);
    }

    private static String output() {
        List<String> lines = given().get(PROBE).path("output");
        return String.join("\n", lines);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        fail("Timed out waiting for debug lifecycle: " + output());
    }
}
