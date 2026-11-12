package dev.omatheusmesmo.vintelli.capabilities;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

/** Explicit, opt-in contract against locally installed jdt.ls and Java Debug. */
@QuarkusIntegrationTest
@WithTestResource(value = JavaDebugRealContractResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class JavaDebugRealContract {

    private static final String PROBE = "/tui/debug-capability-probe";

    @Test
    void realJdtlsAndJavaDebugAttachToJDWPAndDetachWithoutStoppingTheTarget() throws Exception {
        int port = JavaDebugRealContractResource.attachPort();
        Debuggee debuggee = startDebuggee(port);
        try {
            assertTrue(debuggee.process().isAlive(),
                    "the external JDWP debuggee must start before the DAP attach");
            var attached = given().config(RestAssuredConfig.config()
                            .httpClient(HttpClientConfig.httpClientConfig()
                                    .setParam("http.connection.timeout", 180_000)
                                    .setParam("http.socket.timeout", 180_000)))
                    .post(PROBE + "?kind=attach").then().statusCode(200).extract();
            assertTrue(Boolean.TRUE.equals(attached.path("available")), attached.asString());
            assertEquals("", attached.path("error"), attached.asString());
            assertEquals("RUNNING", attached.path("snapshot.state"), attached.asString());
            List<String> threads = attached.path("snapshot.threads.name");
            assertTrue(threads.stream().anyMatch(name -> name.equals("main") || name.equals("Thread [main]")),
                    "real Java Debug threads were " + threads);

            var detached = given().delete(PROBE).then().statusCode(200).extract();
            assertEquals("DISCONNECTED", detached.path("snapshot.state"), detached.asString());
            assertTrue(debuggee.process().isAlive(),
                    "detaching must preserve the externally launched debuggee");
        } finally {
            try {
                given().delete(PROBE).then().statusCode(200);
            } finally {
                stop(debuggee);
            }
        }
    }

    private static Debuggee startDebuggee(int port) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
        Path classes = Path.of(JavaDebugRealContract.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Process process = new ProcessBuilder(java.toString(),
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:" + port,
                "-cp", classes.toString(), RealDebuggee.class.getName())
                .redirectErrorStream(true)
                .start();
        BufferedReader output = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        Debuggee debuggee = new Debuggee(process, output);
        try {
            String startup = CompletableFuture.supplyAsync(() -> {
                try {
                    return output.readLine();
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }).get(30, TimeUnit.SECONDS);
            assertTrue(startup != null && startup.contains("Listening for transport dt_socket"),
                    "JDWP listener did not become ready; debuggee output was: " + startup);
            return debuggee;
        } catch (Exception | Error failure) {
            stop(debuggee);
            throw failure;
        }
    }

    private static void stop(Debuggee debuggee) throws InterruptedException {
        Process process = debuggee.process();
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        }
        try {
            debuggee.output().close();
        } catch (IOException ignored) {
        }
    }

    private record Debuggee(Process process, BufferedReader output) {
    }

    public static final class RealDebuggee {

        private RealDebuggee() {
        }

        public static void main(String[] args) throws InterruptedException {
            while (true) {
                TimeUnit.SECONDS.sleep(1);
            }
        }
    }
}
