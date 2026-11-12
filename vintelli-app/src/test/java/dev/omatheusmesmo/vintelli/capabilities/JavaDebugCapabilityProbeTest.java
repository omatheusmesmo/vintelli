package dev.omatheusmesmo.vintelli.capabilities;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises the fixed debug probe through HTTP and its configured DAP/JDWP twins. */
@QuarkusTest
@WithTestResource(value = JavaDebugCapabilityProbeResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class JavaDebugCapabilityProbeTest {

    private static final String PROBE = "/tui/debug-capability-probe";

    @BeforeEach
    void restoreHealthyAdapter() {
        JavaDebugCapabilityProbeResource.adapter().mode(JavaDebugAdapterTwin.Mode.HEALTHY);
    }

    @Test
    void attachReportsRealThreadsAndDetachesWithoutStoppingTheExternalDebuggee() throws Exception {
        Process debuggee = startExternalDebuggee(JavaDebugCapabilityProbeResource.attachPort());
        try {
            assertTrue(debuggeeReady(debuggee), "the external JDWP debuggee did not start");
            var response = given().queryParam("kind", "attach").post(PROBE).then().statusCode(200).extract();
            assertTrue(Boolean.TRUE.equals(response.path("available")));
            assertEquals("", response.path("error"));
            assertEquals("RUNNING", response.path("snapshot.state"));
            List<String> threads = response.path("snapshot.threads.name");
            assertTrue(threads.contains("main"), threads.toString());
            given().delete(PROBE).then().statusCode(200).body("snapshot.state", is("DISCONNECTED"));
            assertTrue(debuggee.isAlive(), "detaching must preserve the externally launched debuggee");
        } finally {
            given().delete(PROBE).then().statusCode(200);
            destroy(debuggee);
        }
    }

    @Test
    void packagedProviderSynchronizesAndVerifiesSourceBreakpoints() throws Exception {
        Process debuggee = startExternalDebuggee(JavaDebugCapabilityProbeResource.attachPort());
        try {
            assertTrue(debuggeeReady(debuggee), "the external JDWP debuggee did not start");
            var response = given().queryParam("kind", "breakpoints").post(PROBE).then().statusCode(200).extract();
            assertTrue(Boolean.TRUE.equals(response.path("available")));
            assertEquals("VERIFIED", response.path("breakpoints[0].state"));
            assertEquals(3, ((Number) response.path("breakpoints[0].line")).intValue());
            assertEquals(List.of(3), JavaDebugCapabilityProbeResource.adapter().breakpointLines());
        } finally {
            given().delete(PROBE).then().statusCode(200);
            destroy(debuggee);
        }
    }

    @Test
    void packagedProviderStepsTheRealDebuggeeAndReportsItsStoppedFrame() throws Exception {
        Process debuggee = startExternalDebuggee(JavaDebugCapabilityProbeResource.attachPort());
        try {
            assertTrue(debuggeeReady(debuggee), "the external JDWP debuggee did not start");
            var response = given().queryParam("kind", "stepping").post(PROBE).then().statusCode(200).extract();
            assertTrue(Boolean.TRUE.equals(response.path("available")));
            assertEquals("", response.path("error"));
            assertEquals("STOPPED", response.path("snapshot.state"));
            assertEquals(JavaDebugCapabilityProbeResource.projectRoot().resolve("Main.java").toString(),
                    response.path("snapshot.currentFrame.sourcePath"));
            assertTrue(((Number) response.path("snapshot.currentFrame.line")).intValue() > 0);
            assertEquals(List.of("pause", "stepOver", "stepInto", "stepOut"),
                    JavaDebugCapabilityProbeResource.adapter().executionActions());
            given().delete(PROBE).then().statusCode(200);
            assertTrue(debuggee.isAlive(), "detaching after stepping must preserve the external debuggee");
        } finally {
            given().delete(PROBE).then().statusCode(200);
            destroy(debuggee);
        }
    }

    @Test
    void launchOwnsTheDebuggeeAndFreesItsLoopbackPortOnDisconnect() throws Exception {
        try {
            var started = given().queryParam("kind", "launch").post(PROBE).then().statusCode(200).extract();
            assertTrue(Boolean.TRUE.equals(started.path("available")));
            assertEquals("", started.path("error"));
            assertEquals("RUNNING", started.path("snapshot.state"));
            assertTrue(((List<String>) started.path("snapshot.threads.name")).contains("main"));
            await(() -> output().contains("debuggee-started"), "the launched debuggee did not resume");
            long targetPid = awaitDebuggeePid();
            int targetPort = JavaDebugCapabilityProbeResource.adapter().lastTargetPort();
            assertTrue(targetPort > 0, "the DAP attach request did not carry a JDWP port");

            given().delete(PROBE).then().statusCode(200).body("snapshot.state", is("DISCONNECTED"));
            await(() -> ProcessHandle.of(targetPid).map(process -> !process.isAlive()).orElse(true),
                    "disconnect did not stop the launch-owned debuggee process");
            try (ServerSocket released = new ServerSocket()) {
                released.setReuseAddress(true);
                released.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), targetPort));
            }
        } finally {
            given().delete(PROBE).then().statusCode(200);
        }
    }

    @Test
    void closedAdapterPortReturnsAnExplicitFailure() {
        JavaDebugCapabilityProbeResource.adapter().mode(JavaDebugAdapterTwin.Mode.CLOSED_PORT);
        try {
            var response = given().queryParam("kind", "attach").post(PROBE).then().statusCode(200).extract();
            assertTrue(Boolean.TRUE.equals(response.path("available")));
            assertNotEquals("", (String) response.path("error"));
        } finally {
            given().delete(PROBE).then().statusCode(200);
            JavaDebugCapabilityProbeResource.adapter().mode(JavaDebugAdapterTwin.Mode.HEALTHY);
        }
    }

    @Test
    void adapterHandshakeTimeoutReturnsAnExplicitFailure() {
        JavaDebugCapabilityProbeResource.adapter().mode(JavaDebugAdapterTwin.Mode.TIMEOUT);
        try {
            var response = given().queryParam("kind", "attach").post(PROBE).then().statusCode(200).extract();
            assertTrue(Boolean.TRUE.equals(response.path("available")));
            assertNotEquals("", (String) response.path("error"));
        } finally {
            given().delete(PROBE).then().statusCode(200);
            JavaDebugCapabilityProbeResource.adapter().mode(JavaDebugAdapterTwin.Mode.HEALTHY);
        }
    }

    @Test
    void malformedAdapterFrameReturnsAnExplicitFailure() {
        JavaDebugCapabilityProbeResource.adapter().mode(JavaDebugAdapterTwin.Mode.MALFORMED);
        try {
            var response = given().queryParam("kind", "attach").post(PROBE).then().statusCode(200).extract();
            assertTrue(Boolean.TRUE.equals(response.path("available")));
            assertNotEquals("", (String) response.path("error"));
        } finally {
            given().delete(PROBE).then().statusCode(200);
        }
    }

    @Test
    void droppedAdapterStreamReturnsAnExplicitFailure() {
        JavaDebugCapabilityProbeResource.adapter().mode(JavaDebugAdapterTwin.Mode.DROP);
        try {
            var response = given().queryParam("kind", "attach").post(PROBE).then().statusCode(200).extract();
            assertTrue(Boolean.TRUE.equals(response.path("available")));
            assertNotEquals("", (String) response.path("error"));
        } finally {
            given().delete(PROBE).then().statusCode(200);
        }
    }

    @Test
    void probeAcceptsOnlyItsTwoFixedActions() {
        given().queryParam("kind", "arbitrary-command").post(PROBE).then().statusCode(400);
        var response = given().queryParam("kind", "launch").queryParam("path", "/tmp")
                .queryParam("argv", "rm -rf").post(PROBE).then().statusCode(200).extract();
        assertTrue(Boolean.TRUE.equals(response.path("available")));
        assertEquals("", response.path("error"));
        assertTrue(((List<String>) response.path("snapshot.threads.name")).contains("main"));
        given().delete(PROBE).then().statusCode(200);
    }

    private static List<String> output() {
        return given().get(PROBE).then().statusCode(200).extract().jsonPath().getList("output");
    }

    private static Process startExternalDebuggee(int port) throws Exception {
        Path classes = Path.of(JavaDebugTwin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
        return new ProcessBuilder(java.toString(), "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:"
                + port, "-cp", classes.toString(), JavaDebugTwin.Debuggee.class.getName())
                .redirectErrorStream(true)
                .start();
    }

    private static boolean debuggeeReady(Process process) throws Exception {
        CountDownLatch listening = new CountDownLatch(1);
        CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Listening for transport dt_socket")) {
                        listening.countDown();
                    }
                }
            } catch (Exception ignored) {
            }
        });
        return listening.await(10, TimeUnit.SECONDS);
    }

    private static long awaitDebuggeePid() throws Exception {
        AtomicLong found = new AtomicLong(-1);
        await(() -> output().stream().filter(line -> line.startsWith("debuggee-pid:"))
                .findFirst().map(line -> {
                    found.set(Long.parseLong(line.substring("debuggee-pid:".length())));
                    return true;
                }).orElse(false), "the launch-owned Java debuggee PID was not observable");
        return found.get();
    }

    private static void destroy(Process process) throws InterruptedException {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        }
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        fail(message);
    }
}
