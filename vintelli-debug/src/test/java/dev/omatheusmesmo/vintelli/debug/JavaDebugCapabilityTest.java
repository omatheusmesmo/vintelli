package dev.omatheusmesmo.vintelli.debug;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability.RunCompletion;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability.RunConfiguration;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability.RunHandle;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability.RunRequest;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.api.statusbar.CapabilityStatusWidgets;
import dev.omatheusmesmo.vintelli.lsp.JavaDebugAdapterPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDebugCapabilityTest {

    @Test
    void timedOutInspectionCancelsTheOutstandingDapFuture() {
        CompletableFuture<String> response = new CompletableFuture<>();
        assertThrows(TimeoutException.class,
                () -> JavaDebugCapability.awaitInspection(response, Duration.ofMillis(1)));
        assertTrue(response.isCancelled(), "a timed-out inspection must cancel its DAP request");
    }

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(1);

    @Test
    void disabledProviderIsUnavailableWithoutStartingTheAdapter() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.NORMAL)) {
            FakeAdapter adapter = new FakeAdapter(twin.port());
            JavaDebugCapability capability = new JavaDebugCapability(false, adapter, () -> new FakeRegistry(new FakeRun()),
                    TEST_TIMEOUT, TEST_TIMEOUT, TEST_TIMEOUT);

            assertFalse(capability.health().isAvailable());
            assertTrue(capability.health().reason().contains("vintelli.debug.enabled=true"));
            assertEquals(0, adapter.startCalls.get());
        }
    }

    @Test
    void attachSendsLoopbackAndConfigurationDoneBeforeWaitingAndDetachesWithoutStoppingTarget() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.WAIT_FOR_CONFIGURATION_DONE)) {
            FakeRun run = new FakeRun();
            JavaDebugCapability capability = enabled(twin.port(), run);

            CapabilityResult<DebugCapability.DebugSession> result = capability.attach(
                    new DebugCapability.AttachRequest(5005, "existing"), ignored -> { });

            DebugCapability.DebugSession session = ok(result);
            DapTwin.Connection connection = twin.connection();
            assertEquals("127.0.0.1", connection.hostName());
            assertEquals(5005, connection.targetPort());
            assertTrue(connection.initializedSent());
            assertTrue(connection.configurationDone());
            assertEquals(DebugCapability.State.STOPPED, session.snapshot().state());
            assertEquals(List.of(new DebugCapability.DebugThread(1, "main")), ok(session.threads()));

            assertFalse(session.disconnect().isFailure());
            assertFalse(connection.terminateDebuggee());
            assertEquals(0, run.cancelCalls.get());
            assertEquals(DebugCapability.State.DISCONNECTED, session.snapshot().state());
        }
    }

    @Test
    void stoppedFrameAndExecutionActionsFollowDapEventOrdering() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.NORMAL)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "stepping"), ignored -> { }));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (session.snapshot().currentFrame() == null && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(session.snapshot().currentFrame() != null, "the stopped event should retrieve the top frame");
            assertEquals("/project/Main.java", session.snapshot().currentFrame().sourcePath());
            assertEquals(12, session.snapshot().currentFrame().line());
            assertTrue(session.snapshot().capabilities().resume());
            assertTrue(session.snapshot().capabilities().stepOver());
            assertTrue(session.resume() instanceof CapabilityResult.Ok<Boolean>);
            assertEquals(DebugCapability.State.RUNNING, session.snapshot().state());
            assertEquals(null, session.snapshot().currentFrame(), "continued clears the stale stopped frame");
            assertTrue(session.pause() instanceof CapabilityResult.Ok<Boolean>);
            assertEquals(DebugCapability.State.STOPPED, session.snapshot().state());
            assertTrue(session.stepOver() instanceof CapabilityResult.Ok<Boolean>);
            assertTrue(session.stepInto() instanceof CapabilityResult.Ok<Boolean>);
            assertTrue(session.stepOut() instanceof CapabilityResult.Ok<Boolean>);
            assertTrue(session.stepOver() instanceof CapabilityResult.Ok<Boolean>, "repeated step requests remain valid");
            assertEquals(List.of("resume", "pause", "stepOver", "stepInto", "stepOut", "stepOver"),
                    twin.executionActions());
            session.disconnect();
            assertEquals(null, session.snapshot().currentFrame());
        }
    }

    @Test
    void inspectionReadsBoundedStackScopesAndVariablesAndSelectsEditorFrame() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.NORMAL)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "inspection"), ignored -> { }));
            List<DebugCapability.DebugFrame> frames = ok(session.stackTrace(1));
            assertEquals(1, frames.size());
            assertEquals(1, frames.getFirst().frameId());
            assertEquals("/project/Main.java", frames.getFirst().sourcePath());
            assertTrue(session.selectFrame(frames.getFirst(), session.inspectionGeneration() - 1).isFailure(),
                    "frame selection from a prior stop must not update the current frame");
            assertTrue(session.selectFrame(frames.getFirst()) instanceof CapabilityResult.Ok<Boolean>);
            List<DebugCapability.DebugScope> scopes = ok(session.scopes(frames.getFirst().frameId()));
            assertEquals("Locals", scopes.getFirst().name());
            List<DebugCapability.DebugVariable> variables = ok(session.variables(scopes.getFirst().variablesReference()));
            assertEquals("answer", variables.getFirst().name());
            assertEquals("42", variables.getFirst().value());
            session.disconnect();
        }
    }

    @Test
    void stackTraceFromAnOlderStopCannotReplaceTheCurrentFrame() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.STALE_STACK_TRACE)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "stop generations"), ignored -> { }));
            DapTwin.Connection connection = twin.connection();
            long stackDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!connection.staleStackTracePending() && System.nanoTime() < stackDeadline) Thread.sleep(10);
            assertTrue(connection.staleStackTracePending());
            connection.stopAgain();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((session.snapshot().currentFrame() == null || session.snapshot().currentFrame().line() != 20)
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(20, session.snapshot().currentFrame().line());
            connection.releaseStaleStackTrace();
            Thread.sleep(50);
            assertEquals(20, session.snapshot().currentFrame().line(), "a late first-stop response must be ignored");
            session.disconnect();
        }
    }

    @Test
    void stoppedStateIsNotPublishedBeforeItsTopFrameArrives() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.STALE_STACK_TRACE)) {
            JavaDebugCapability capability = new JavaDebugCapability(true, new FakeAdapter(twin.port()),
                    () -> new FakeRegistry(new FakeRun()), TEST_TIMEOUT, TEST_TIMEOUT, Duration.ofSeconds(5));
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<CapabilityResult<DebugCapability.DebugSession>> attach = worker.submit(() -> capability.attach(
                        new DebugCapability.AttachRequest(5005, "held top frame"), ignored -> { }));
                DapTwin.Connection connection = twin.connection();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!connection.staleStackTracePending() && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(connection.staleStackTracePending());
                assertNotEquals(DebugCapability.State.STOPPED, capability.snapshot().getFirst().state(),
                        "the stopped state must not be visible while its top frame is still pending");
                connection.releaseStaleStackTrace();
                DebugCapability.DebugSession session = ok(get(attach));
                assertEquals(DebugCapability.State.STOPPED, session.snapshot().state());
                assertEquals("/project/Main.java", session.snapshot().currentFrame().sourcePath());
                assertEquals(12, session.snapshot().currentFrame().line());
                session.disconnect();
            } finally {
                worker.shutdownNow();
            }
        }
    }

    @Test
    void actionAcknowledgementWaitsForItsMatchingDapEvent() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.ACK_BEFORE_EVENT)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "ack ordering"), ignored -> { }));
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<CapabilityResult<Boolean>> first = worker.submit(session::stepOver);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!twin.connection().delayedExecutionEventPending() && System.nanoTime() < deadline) Thread.sleep(10);
                assertFalse(first.isDone(), "a DAP response does not finish the action before its stopped event");
                assertTrue(session.stepOver().isFailure(), "a repeated request is fenced until the event arrives");
                assertEquals(List.of("stepOver"), twin.executionActions());
                twin.connection().releaseDelayedExecutionEvent();
                assertTrue(first.get(2, TimeUnit.SECONDS) instanceof CapabilityResult.Ok<Boolean>);
            } finally {
                worker.shutdownNow();
                session.disconnect();
            }
        }
    }

    @Test
    void acknowledgedActionKeepsItsFenceAfterEventTimeoutUntilLateEventArrives() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.ACK_BEFORE_EVENT)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "late execution event"), ignored -> { }));
            assertTrue(session.stepOver().isFailure(), "the caller receives a bounded timeout result");
            assertEquals(DebugCapability.State.EXECUTING, session.snapshot().state(),
                    "the session stays ambiguous while the acknowledged action has no matching event");
            assertTrue(session.stepOver().isFailure(), "the retained transition fence blocks duplicate actions");
            assertEquals(List.of("stepOver"), twin.executionActions());

            twin.connection().releaseDelayedExecutionEvent();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (session.snapshot().state() != DebugCapability.State.STOPPED && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(DebugCapability.State.STOPPED, session.snapshot().state());
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (session.snapshot().currentFrame() == null && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(session.snapshot().currentFrame() != null);
            session.disconnect();
        }
    }

    @Test
    void optionalPauseIsLearnedFromTheAdapterAndTransientTimeoutStaysUnknown() throws Exception {
        try (DapTwin unsupported = new DapTwin(DapTwin.Mode.UNSUPPORTED_PAUSE)) {
            JavaDebugCapability capability = enabled(unsupported.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "unsupported pause"), ignored -> { }));
            assertFalse(session.snapshot().capabilities().known(DebugCapability.SteppingAction.PAUSE));
            assertTrue(session.resume() instanceof CapabilityResult.Ok<Boolean>);
            assertTrue(session.pause().isFailure());
            assertTrue(session.snapshot().capabilities().known(DebugCapability.SteppingAction.PAUSE));
            assertFalse(session.snapshot().capabilities().supports(DebugCapability.SteppingAction.PAUSE));
            session.disconnect();
        }
        try (DapTwin timeout = new DapTwin(DapTwin.Mode.PAUSE_NO_EVENT)) {
            JavaDebugCapability capability = enabled(timeout.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "pause timeout"), ignored -> { }));
            assertTrue(session.resume() instanceof CapabilityResult.Ok<Boolean>);
            assertTrue(session.pause().isFailure());
            assertFalse(session.snapshot().capabilities().known(DebugCapability.SteppingAction.PAUSE),
                    "a timeout must not be cached as an unsupported request");
            session.disconnect();
        }
    }

    @Test
    void launchOwnsAndCancelsItsRunHandleWhenDisconnected() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.NORMAL)) {
            FakeRun run = new FakeRun();
            JavaDebugCapability capability = enabled(twin.port(), run);

            DebugCapability.DebugSession session = ok(capability.launch(
                    new DebugCapability.DebugRequest(request(), "new run"), ignored -> { }));

            assertTrue(run.startedPort.get() > 0);
            assertEquals("127.0.0.1", twin.connection().hostName());
            assertTrue(session.disconnect() instanceof CapabilityResult.Ok<Boolean>);
            assertEquals(1, run.cancelCalls.get());
            assertFalse(twin.connection().terminateDebuggee());
        }
    }

    @Test
    void setBreakpointsReplacesTheCompleteSourceSetAndReportsAdapterVerification() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.REJECT_BREAKPOINT)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            ok(capability.launch(new DebugCapability.DebugRequest(request(), "breakpoints"), ignored -> { }));

            List<DebugCapability.SourceBreakpoint> applied = ok(capability.setBreakpoints("/project/Main.java",
                    List.of(new DebugCapability.SourceBreakpoint("/project/Main.java", 9),
                            new DebugCapability.SourceBreakpoint("/project/Main.java", 4))));

            assertEquals(List.of(4, 9), twin.breakpointLines());
            assertEquals(DebugCapability.BreakpointState.REJECTED, applied.getFirst().state());
            assertEquals("Source line is not executable", applied.getFirst().message());
            assertEquals(DebugCapability.BreakpointState.VERIFIED, applied.get(1).state());

            ok(capability.setBreakpoints("/project/Main.java",
                    List.of(new DebugCapability.SourceBreakpoint("/project/Main.java", 2))));
            assertEquals(List.of(2), twin.breakpointLines());
            ok(capability.replaceBreakpoints(Map.of()));
            assertEquals(List.of(), twin.breakpointLines(), "omitted source sets are cleared during full reconciliation");
        }
    }

    @Test
    void preservesRequestedLineAndExposesAdapterResolvedSourceLocation() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.RESOLVE_BREAKPOINT)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "resolved breakpoint"), ignored -> { }));

            DebugCapability.SourceBreakpoint applied = ok(capability.setBreakpoints("/project/Main.java",
                    List.of(new DebugCapability.SourceBreakpoint("/project/Main.java", 7)))).getFirst();

            assertEquals(7, applied.line(), "the persistent editor anchor remains the requested source line");
            assertEquals(8, applied.resolvedLine());
            assertEquals("/mapped/Main.java", applied.resolvedSourcePath());
            session.disconnect();
        }
    }

    @Test
    void rejectsAnAggregateBreakpointWhenAnyLiveSessionRejectsIt() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.REJECT_SECOND_SESSION)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            DebugCapability.DebugSession first = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "first session"), ignored -> { }));
            DebugCapability.DebugSession second = ok(capability.attach(
                    new DebugCapability.AttachRequest(5006, "second session"), ignored -> { }));

            DebugCapability.SourceBreakpoint applied = ok(capability.setBreakpoints("/project/Main.java",
                    List.of(new DebugCapability.SourceBreakpoint("/project/Main.java", 9)))).getFirst();

            assertEquals(DebugCapability.BreakpointState.REJECTED, applied.state());
            assertEquals("Source line is not executable", applied.message());
            first.disconnect();
            second.disconnect();
        }
    }

    @Test
    void launchCancellationStopsTheOwnedRun() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.NORMAL)) {
            FakeRun run = new FakeRun();
            JavaDebugCapability capability = enabled(twin.port(), run);
            DebugCapability.DebugSession session = ok(capability.launch(
                    new DebugCapability.DebugRequest(request(), "cancel"), ignored -> { }));

            session.cancel();

            assertTrue(run.cancelled.await(2, TimeUnit.SECONDS));
            assertEquals(DebugCapability.State.DISCONNECTED, session.snapshot().state());
            assertFalse(twin.connection().terminateDebuggee());
        }
    }

    @Test
    void providerShutdownWaitsForCleanupAndRejectsNewSessions() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.BLOCK_DISCONNECT)) {
            FakeRun run = new FakeRun();
            FakeAdapter adapter = new FakeAdapter(twin.port());
            JavaDebugCapability capability = new JavaDebugCapability(true, adapter, () -> new FakeRegistry(run),
                    TEST_TIMEOUT, TEST_TIMEOUT, TEST_TIMEOUT);
            DebugCapability.DebugSession session = ok(capability.launch(
                    new DebugCapability.DebugRequest(request(), "shutdown"), ignored -> { }));

            session.cancel();
            assertTrue(twin.connection().awaitDisconnect(1, TimeUnit.SECONDS));
            capability.closeSessions();

            assertTrue(run.cancelled.await(1, TimeUnit.SECONDS));
            assertEquals(1, run.cancelCalls.get());
            assertFalse(capability.health().isAvailable());
            assertTrue(capability.attach(new DebugCapability.AttachRequest(5005, "after shutdown"), ignored -> { })
                    .isFailure());
            assertEquals(1, adapter.startCalls.get());
        }
    }

    @Test
    void launchCompletionAndCancellationLeaveNoActiveRunHandle() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.NORMAL)) {
            FakeRun run = new FakeRun();
            JavaDebugCapability capability = enabled(twin.port(), run);
            DebugCapability.DebugSession session = ok(capability.launch(
                    new DebugCapability.DebugRequest(request(), "completion"), ignored -> { }));

            run.complete(new RunCompletion(RunCapability.RunOutcome.EXITED, Optional.of(0)));

            assertTrue(run.cancelled.await(2, TimeUnit.SECONDS));
            assertTrue(session.snapshot().reason().contains("exit code 0"));
        }
    }

    @Test
    void droppedDapStreamFailsSessionAndCancelsOwnedRun() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.NORMAL)) {
            FakeRun run = new FakeRun();
            JavaDebugCapability capability = enabled(twin.port(), run);
            DebugCapability.DebugSession session = ok(capability.launch(
                    new DebugCapability.DebugRequest(request(), "drop"), ignored -> { }));

            twin.drop();

            assertTrue(run.cancelled.await(2, TimeUnit.SECONDS));
            assertFalse(session.snapshot().state() == DebugCapability.State.RUNNING);
        }
    }

    @Test
    void outputBurstCannotBlockTheDapReaderOrGrowAnUnboundedQueue() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.NORMAL)) {
            FakeRun run = new FakeRun();
            JavaDebugCapability capability = enabled(twin.port(), run);
            CountDownLatch consumerEntered = new CountDownLatch(1);
            CountDownLatch releaseConsumer = new CountDownLatch(1);
            CountDownLatch secondOutputConsumed = new CountDownLatch(1);
            AtomicInteger consumed = new AtomicInteger();
            Consumer<String> slowConsumer = ignored -> {
                if (consumed.incrementAndGet() == 1) {
                    consumerEntered.countDown();
                    try {
                        releaseConsumer.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    secondOutputConsumed.countDown();
                }
            };
            DebugCapability.DebugSession session = ok(capability.launch(
                    new DebugCapability.DebugRequest(request(), "output burst"), slowConsumer));

            try {
                DapTwin.Connection connection = twin.connection();
                connection.outputBurst(1000);
                assertTrue(consumerEntered.await(1, TimeUnit.SECONDS));
                assertTrue(session.threads() instanceof CapabilityResult.Ok<List<DebugCapability.DebugThread>>);
            } finally {
                releaseConsumer.countDown();
            }

            assertTrue(secondOutputConsumed.await(1, TimeUnit.SECONDS));
            assertTrue(consumed.get() <= 257, "bounded output queue should discard excess output");
            session.disconnect();
        }
    }

    @Test
    void concurrentAttachCallsCannotExceedTheActiveSessionLimit() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.NORMAL);
                ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            CountDownLatch ready = new CountDownLatch(12);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<CapabilityResult<DebugCapability.DebugSession>>> calls = IntStream.range(0, 12)
                    .mapToObj(index -> callers.submit(() -> {
                        ready.countDown();
                        start.await();
                        return capability.attach(new DebugCapability.AttachRequest(5005 + index, "parallel"),
                                ignored -> { });
                    }))
                    .toList();
            assertTrue(ready.await(1, TimeUnit.SECONDS));
            start.countDown();

            List<CapabilityResult<DebugCapability.DebugSession>> results = calls.stream()
                    .map(JavaDebugCapabilityTest::get).toList();
            List<DebugCapability.DebugSession> sessions = results.stream()
                    .filter(CapabilityResult.Ok.class::isInstance)
                    .map(result -> ((CapabilityResult.Ok<DebugCapability.DebugSession>) result).value())
                    .toList();
            long failures = results.stream().filter(CapabilityResult.Failed.class::isInstance).count();
            assertEquals(8, sessions.size());
            assertEquals(4, failures);
            sessions.forEach(DebugCapability.DebugSession::disconnect);
        }
    }

    @Test
    void terminalEventCannotBeOverwrittenByLateAttachResponse() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.TERMINATE_DURING_ATTACH)) {
            FakeRun run = new FakeRun();
            JavaDebugCapability capability = enabled(twin.port(), run);

            CapabilityResult<DebugCapability.DebugSession> result = capability.launch(
                    new DebugCapability.DebugRequest(request(), "terminal race"), ignored -> { });

            assertTrue(result.isFailure());
            assertTrue(run.cancelled.await(2, TimeUnit.SECONDS));
            assertEquals(1, run.cancelCalls.get());
            assertEquals(DebugCapability.State.TERMINATED, capability.snapshot().getFirst().state());
        }
    }

    @Test
    void retriesAttachFailureBeforeInitializedThenCompletesConfiguration() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.FAIL_ONCE_THEN_INITIALIZED)) {
            FakeRun run = new FakeRun();
            JavaDebugCapability capability = enabled(twin.port(), run);

            DebugCapability.DebugSession session = ok(capability.launch(
                    new DebugCapability.DebugRequest(request(), "delayed JDWP"), ignored -> { }));

            DapTwin.Connection connection = twin.connection();
            assertEquals(2, connection.attachCalls());
            assertTrue(connection.initializedSent());
            assertTrue(connection.configurationDone());
            assertEquals(DebugCapability.State.STOPPED, session.snapshot().state());
            session.disconnect();
        }
    }

    @Test
    void attachTimeoutCleansUpLaunchOwnedProcess() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.NEVER_ATTACH)) {
            FakeRun run = new FakeRun();
            JavaDebugCapability capability = enabled(twin.port(), run);

            CapabilityResult<DebugCapability.DebugSession> result = capability.launch(
                    new DebugCapability.DebugRequest(request(), "timeout"), ignored -> { });

            assertTrue(result.isFailure());
            assertTrue(result.failureOf().orElseThrow().reason().contains("Timed out attaching"));
            assertEquals(1, run.cancelCalls.get());
            assertFalse(twin.connection().terminateDebuggee());
        }
    }

    @Test
    void closedAdapterPortAndMalformedStreamFailWithoutStartingAProcess() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = socket.getLocalPort();
        }
        FakeRun closedRun = new FakeRun();
        CapabilityResult<DebugCapability.DebugSession> closed = enabled(closedPort, closedRun).launch(
                new DebugCapability.DebugRequest(request(), "closed"), ignored -> { });
        assertTrue(closed.isFailure());
        assertEquals(0, closedRun.startCalls.get());

        try (DapTwin twin = new DapTwin(DapTwin.Mode.MALFORMED)) {
            FakeRun malformedRun = new FakeRun();
            CapabilityResult<DebugCapability.DebugSession> malformed = enabled(twin.port(), malformedRun).launch(
                    new DebugCapability.DebugRequest(request(), "malformed"), ignored -> { });
            assertTrue(malformed.isFailure());
            assertEquals(0, malformedRun.startCalls.get());
        }
    }

    @Test
    void unsupportedAdapterCapabilitiesFailBeforeStartingRun() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.UNSUPPORTED)) {
            FakeRun run = new FakeRun();

            CapabilityResult<DebugCapability.DebugSession> result = enabled(twin.port(), run).launch(
                    new DebugCapability.DebugRequest(request(), "unsupported"), ignored -> { });

            assertTrue(result.isFailure());
            assertTrue(result.failureOf().orElseThrow().reason().contains("configurationDone"));
            assertEquals(0, run.startCalls.get());
        }
    }

    @Test
    void evaluatesAnExpressionInTheSelectedFrameAndBoundsTheResult() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.NORMAL)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "evaluation"), ignored -> { }));
            awaitState(session, DebugCapability.State.STOPPED);
            DebugCapability.DebugFrame frame = ok(session.stackTrace(1)).getFirst();

            DebugCapability.EvaluationResult answer = ok(session.evaluate(new DebugCapability.EvaluationRequest(
                    frame.frameId(), "answer", session.inspectionGeneration(), true)));
            assertEquals("42", answer.value());
            assertEquals("int", answer.type());
            assertFalse(answer.truncated());

            DebugCapability.EvaluationResult large = ok(session.evaluate(new DebugCapability.EvaluationRequest(
                    frame.frameId(), "large", session.inspectionGeneration(), true)));
            assertTrue(large.truncated(), "a value longer than the bound must be marked as truncated");
            assertEquals(1003, large.value().length(), "the value keeps 1000 characters and an ellipsis");
            session.disconnect();
        }
    }

    @Test
    void rejectsEvaluationWithoutConfirmationOrForAFrameFromAnEarlierStop() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.NORMAL)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "confirmation"), ignored -> { }));
            awaitState(session, DebugCapability.State.STOPPED);
            long generation = session.inspectionGeneration();

            CapabilityResult<DebugCapability.EvaluationResult> unconfirmed = session.evaluate(
                    new DebugCapability.EvaluationRequest(1, "answer", generation, false));
            assertTrue(unconfirmed.isFailure());
            assertTrue(unconfirmed.failureOf().orElseThrow().reason().contains("explicit confirmation"));

            CapabilityResult<DebugCapability.EvaluationResult> stale = session.evaluate(
                    new DebugCapability.EvaluationRequest(1, "answer", generation - 1, true));
            assertTrue(stale.failureOf().orElseThrow().reason().contains("changed before evaluation"));
            assertEquals(List.of(), twin.evaluations(), "rejected requests must never reach the debuggee");
            session.disconnect();
        }
    }

    @Test
    void evaluatorErrorsAreReportedAndSideEffectsReachTheDebuggee() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.NORMAL)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "side effects"), ignored -> { }));
            awaitState(session, DebugCapability.State.STOPPED);
            DebugCapability.DebugFrame frame = ok(session.stackTrace(1)).getFirst();

            CapabilityResult<DebugCapability.EvaluationResult> rejected = session.evaluate(
                    new DebugCapability.EvaluationRequest(frame.frameId(), "missing value", session.inspectionGeneration(), true));
            assertTrue(rejected.failureOf().orElseThrow().reason().contains("Cannot find symbol"));

            assertEquals("0", ok(session.evaluate(new DebugCapability.EvaluationRequest(
                    frame.frameId(), "counter++", session.inspectionGeneration(), true))).value());
            assertEquals("1", ok(session.evaluate(new DebugCapability.EvaluationRequest(
                    frame.frameId(), "counter++", session.inspectionGeneration(), true))).value());
            assertEquals("2", ok(session.evaluate(new DebugCapability.EvaluationRequest(
                    frame.frameId(), "counter", session.inspectionGeneration(), true))).value(),
                    "confirmed expressions run in the debuggee, so their side effects persist");
            session.disconnect();
        }
    }

    @Test
    void unsupportedEvaluatorAndSilentAdapterAreReportedAsBoundedFailures() throws Exception {
        try (DapTwin unsupported = new DapTwin(DapTwin.Mode.EVALUATE_UNSUPPORTED)) {
            JavaDebugCapability capability = enabled(unsupported.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "unsupported evaluate"), ignored -> { }));
            awaitState(session, DebugCapability.State.STOPPED);
            CapabilityResult<DebugCapability.EvaluationResult> result = session.evaluate(
                    new DebugCapability.EvaluationRequest(1, "answer", session.inspectionGeneration(), true));
            assertEquals("Evaluation is not supported by this adapter", result.failureOf().orElseThrow().reason());
            session.disconnect();
        }
        try (DapTwin silent = new DapTwin(DapTwin.Mode.EVALUATE_NO_RESPONSE)) {
            JavaDebugCapability capability = enabled(silent.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "silent evaluate"), ignored -> { }));
            awaitState(session, DebugCapability.State.STOPPED);
            CapabilityResult<DebugCapability.EvaluationResult> result = session.evaluate(
                    new DebugCapability.EvaluationRequest(1, "answer", session.inspectionGeneration(), true));
            assertTrue(result.failureOf().orElseThrow().reason().contains("did not answer"),
                    "a silent adapter must release the caller after the request timeout");
            session.disconnect();
        }
    }

    @Test
    void setsAVariableThroughTheAdapterAndTheNextReadSeesTheNewValue() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.NORMAL)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "set value"), ignored -> { }));
            awaitState(session, DebugCapability.State.STOPPED);

            DebugCapability.DebugVariable changed = ok(session.setVariable(22, "answer", "  10 ",
                    session.inspectionGeneration()));

            assertEquals(List.of("22:answer=10"), twin.assignments(), "the adapter receives the stripped value");
            assertEquals("answer", changed.name());
            assertEquals("10", changed.value());
            assertEquals("int", changed.type());
            assertEquals("10", ok(session.variables(22)).getFirst().value());
            session.disconnect();
        }
    }

    @Test
    void anAdapterErrorKeepsTheOldValueAndIsReportedVerbatim() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.NORMAL)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "invalid value"), ignored -> { }));
            awaitState(session, DebugCapability.State.STOPPED);

            CapabilityResult<DebugCapability.DebugVariable> rejected = session.setVariable(22, "answer", "\"abc\"",
                    session.inspectionGeneration());

            assertTrue(rejected.failureOf().orElseThrow().reason().contains("Type mismatch"));
            assertEquals("42", ok(session.variables(22)).getFirst().value());
            session.disconnect();
        }
    }

    @Test
    void rejectsAnAssignmentForAnEarlierStopOrAMalformedValueWithoutReachingTheAdapter() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.NORMAL)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "stale set value"), ignored -> { }));
            awaitState(session, DebugCapability.State.STOPPED);
            long generation = session.inspectionGeneration();

            CapabilityResult<DebugCapability.DebugVariable> stale = session.setVariable(22, "answer", "10",
                    generation - 1);
            assertTrue(stale.failureOf().orElseThrow().reason().contains("changed before the value was set"));
            assertTrue(session.setVariable(22, "answer", "1\n2", generation).isFailure());
            assertTrue(session.setVariable(22, "answer", " ", generation).isFailure());
            assertTrue(session.setVariable(0, "answer", "10", generation).isFailure());
            assertEquals(List.of(), twin.assignments(), "rejected requests must never reach the debuggee");

            assertTrue(session.resume() instanceof CapabilityResult.Ok<Boolean>);
            awaitState(session, DebugCapability.State.RUNNING);
            assertTrue(session.setVariable(22, "answer", "10", generation).isFailure(),
                    "a resumed session never applies an edit");
            assertEquals(List.of(), twin.assignments());
            session.disconnect();
        }
    }

    @Test
    void anAdapterWithoutSetVariableIsReportedWithoutSendingTheRequest() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.SET_VARIABLE_UNSUPPORTED)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "unsupported set value"), ignored -> { }));
            awaitState(session, DebugCapability.State.STOPPED);

            CapabilityResult<DebugCapability.DebugVariable> result = session.setVariable(22, "answer", "10",
                    session.inspectionGeneration());

            assertEquals("Setting a value is not supported by this adapter", result.failureOf().orElseThrow().reason());
            assertEquals(List.of(), twin.assignments());
            session.disconnect();
        }
    }

    @Test
    void resumingDuringAnAssignmentDiscardsTheLateResult() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.SET_VARIABLE_HOLD)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "stale assignment"), ignored -> { }));
            awaitState(session, DebugCapability.State.STOPPED);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<CapabilityResult<DebugCapability.DebugVariable>> pending = worker.submit(() ->
                        session.setVariable(22, "answer", "7", session.inspectionGeneration()));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (twin.assignments().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(session.resume() instanceof CapabilityResult.Ok<Boolean>);

                twin.connection().releaseHeldAssignment();
                CapabilityResult<DebugCapability.DebugVariable> late = pending.get(2, TimeUnit.SECONDS);
                assertTrue(late.failureOf().orElseThrow().reason().contains("changed while the value was set"),
                        "a result that arrives after the debuggee resumed must not be reported as current");
            } finally {
                worker.shutdownNow();
                session.disconnect();
            }
        }
    }

    @Test
    void resumingDuringEvaluationDiscardsTheLateResult() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.EVALUATE_HOLD)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "stale evaluation"), ignored -> { }));
            awaitState(session, DebugCapability.State.STOPPED);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<CapabilityResult<DebugCapability.EvaluationResult>> pending = worker.submit(() -> session.evaluate(
                        new DebugCapability.EvaluationRequest(1, "answer", session.inspectionGeneration(), true)));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (twin.evaluations().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(session.resume() instanceof CapabilityResult.Ok<Boolean>);

                twin.connection().releaseHeldEvaluation();
                CapabilityResult<DebugCapability.EvaluationResult> late = pending.get(2, TimeUnit.SECONDS);
                assertTrue(late.failureOf().orElseThrow().reason().contains("changed during evaluation"),
                        "a result that arrives after the debuggee resumed must not be reported as current");
            } finally {
                worker.shutdownNow();
                session.disconnect();
            }
        }
    }

    @Test
    void disconnectedSessionRejectsEvaluationWithoutCallingTheAdapter() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.NORMAL)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "disconnected evaluate"), ignored -> { }));
            awaitState(session, DebugCapability.State.STOPPED);
            long generation = session.inspectionGeneration();
            session.disconnect();

            CapabilityResult<DebugCapability.EvaluationResult> result = session.evaluate(
                    new DebugCapability.EvaluationRequest(1, "answer", generation, true));
            assertTrue(result.failureOf().orElseThrow().reason().contains("not active"));
            assertEquals(List.of(), twin.evaluations());
        }
    }

    @Test
    void conditionsAreSentWithSourceBreakpointsAndMalformedOnesAreRejectedPerLine() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.NORMAL)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "conditions"), ignored -> { }));

            List<DebugCapability.SourceBreakpoint> applied = ok(capability.setBreakpoints("/project/Main.java",
                    List.of(conditional(9, "answer > 1"), conditional(4, "(answer > 1"))));

            assertEquals(List.of(4, 9), twin.breakpointLines());
            assertEquals(List.of("(answer > 1", "answer > 1"), twin.breakpointConditions());
            assertEquals(DebugCapability.BreakpointState.REJECTED, applied.getFirst().state());
            assertEquals("Malformed breakpoint condition", applied.getFirst().message());
            assertEquals("(answer > 1", applied.getFirst().condition());
            assertEquals(DebugCapability.BreakpointState.VERIFIED, applied.get(1).state());
            assertEquals("answer > 1", applied.get(1).condition(), "the editor anchor keeps its condition");
            session.disconnect();
        }
    }

    @Test
    void adapterWithoutConditionalBreakpointsRejectsOnlyConditionedLines() throws Exception {
        try (DapTwin twin = new DapTwin(DapTwin.Mode.UNSUPPORTED_CONDITIONS)) {
            JavaDebugCapability capability = enabled(twin.port(), new FakeRun());
            DebugCapability.DebugSession session = ok(capability.attach(
                    new DebugCapability.AttachRequest(5005, "no conditions"), ignored -> { }));

            List<DebugCapability.SourceBreakpoint> applied = ok(capability.setBreakpoints("/project/Main.java",
                    List.of(conditional(9, "answer > 1"), new DebugCapability.SourceBreakpoint("/project/Main.java", 4))));

            assertEquals(List.of(4), twin.breakpointLines(), "unsupported conditions are never sent as unconditional breakpoints");
            assertEquals(DebugCapability.BreakpointState.VERIFIED, applied.getFirst().state());
            assertEquals(DebugCapability.BreakpointState.REJECTED, applied.get(1).state());
            assertEquals("Conditional breakpoints are not supported by this adapter", applied.get(1).message());
            session.disconnect();
        }
    }

    private static DebugCapability.SourceBreakpoint conditional(int line, String condition) {
        return new DebugCapability.SourceBreakpoint("/project/Main.java", line,
                DebugCapability.BreakpointState.PENDING, "", null, "/project/Main.java", condition);
    }

    private static void awaitState(DebugCapability.DebugSession session, DebugCapability.State state)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (session.snapshot().state() != state && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(state, session.snapshot().state());
    }

    private static JavaDebugCapability enabled(int adapterPort, FakeRun run) {
        return new JavaDebugCapability(true, new FakeAdapter(adapterPort), () -> new FakeRegistry(run),
                TEST_TIMEOUT, TEST_TIMEOUT, TEST_TIMEOUT);
    }

    private static RunRequest request() {
        return new RunRequest("maven:quarkus:dev", Path.of("."), Map.of());
    }

    private static <T> T ok(CapabilityResult<T> result) {
        assertTrue(result instanceof CapabilityResult.Ok<T>, result.failureOf().map(CapabilityFailure::reason).orElse(""));
        return ((CapabilityResult.Ok<T>) result).value();
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("Concurrent debug call did not finish", failure);
        }
    }

    private static final class FakeAdapter implements JavaDebugAdapterPort {

        private final int port;
        private final AtomicInteger startCalls = new AtomicInteger();

        private FakeAdapter(int port) {
            this.port = port;
        }

        @Override
        public ProviderHealth debugAdapterHealth() {
            return ProviderHealth.available();
        }

        @Override
        public CapabilityResult<Integer> startDebugAdapter() {
            startCalls.incrementAndGet();
            return CapabilityResult.ok(port);
        }
    }

    private static final class FakeRun implements RunCapability {

        private final AtomicInteger startCalls = new AtomicInteger();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final AtomicReference<Integer> startedPort = new AtomicReference<>(0);
        private volatile Consumer<RunCompletion> completion;

        @Override
        public String identifier() {
            return "test-run";
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.available();
        }

        @Override
        public String provenance() {
            return "test run provider";
        }

        @Override
        public CapabilityResult<List<RunConfiguration>> listConfigurations() {
            return CapabilityResult.ok(List.of(new RunConfiguration("maven:quarkus:dev", "test")));
        }

        @Override
        public DebugSupport debugSupport(String configurationName) {
            return new DebugSupport(true, "");
        }

        @Override
        public CapabilityResult<RunHandle> startDebug(RunRequest request, int port, Consumer<String> outputLines,
                Consumer<RunCompletion> completion) {
            startCalls.incrementAndGet();
            startedPort.set(port);
            this.completion = completion;
            return CapabilityResult.ok(() -> {
                cancelCalls.incrementAndGet();
                cancelled.countDown();
            });
        }

        @Override
        public CapabilityResult<RunHandle> start(RunRequest request, Consumer<String> outputLines) {
            return CapabilityResult.failed(new CapabilityFailure("Unused", ""));
        }

        private void complete(RunCompletion completed) {
            completion.accept(completed);
        }
    }

    private static final class FakeRegistry implements CapabilityRegistry {

        private final RunCapability run;

        private FakeRegistry(RunCapability run) {
            this.run = run;
        }

        @Override public CapabilitySelection<RunCapability> run() {
            return CapabilitySelection.selected(CapabilityType.RUN, run);
        }
        @Override public CapabilitySelection<TerminalCapability> terminal() {
            return unavailable(CapabilityType.TERMINAL);
        }
        @Override public CapabilitySelection<ReadFileCapability> readFile() {
            return unavailable(CapabilityType.READ_FILE);
        }
        @Override public CapabilitySelection<DiagnosticsCapability> diagnostics() {
            return unavailable(CapabilityType.DIAGNOSTICS);
        }
        @Override public CapabilitySelection<CallHierarchyCapability> callHierarchy() {
            return unavailable(CapabilityType.CALL_HIERARCHY);
        }
        @Override public CapabilitySelection<CodeActionCapability> codeActions() {
            return unavailable(CapabilityType.CODE_ACTION);
        }
        @Override public CapabilitySelection<EditorBridge> editorBridge() {
            return unavailable(CapabilityType.EDITOR_BRIDGE);
        }
        @Override public CapabilitySelection<VersionControlCapability> versionControl() {
            return unavailable(CapabilityType.VERSION_CONTROL);
        }
        @Override public List<CapabilityStatus> snapshot() {
            return List.of();
        }
        @Override public List<CapabilityStatusWidgets> statusBarSnapshot() {
            return List.of();
        }

        private static <T> CapabilitySelection<T> unavailable(CapabilityType type) {
            return CapabilitySelection.unavailable(type, "Not used in this test");
        }
    }
}
