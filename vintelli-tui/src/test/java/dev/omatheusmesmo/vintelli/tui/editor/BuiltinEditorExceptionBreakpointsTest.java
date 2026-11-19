package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BuiltinEditorExceptionBreakpointsTest {

    @TempDir
    Path project;

    @Test
    void uncaughtIsOnByDefaultAndTheTogglesPersistPerProject() throws Exception {
        Path session = project.resolve("editor.properties");
        BuiltinEditor editor = DebugProviderFixture.editor(project, session, null);

        assertEquals(DebugCapability.ExceptionBreakpoints.DEFAULT, editor.exceptionBreakpoints());
        assertEquals("Break on caught exceptions: on", editor.toggleExceptionBreakpoint(true, message -> { }));
        assertEquals("Break on uncaught exceptions: off", editor.toggleExceptionBreakpoint(false, message -> { }));

        BuiltinEditor restored = DebugProviderFixture.editor(project, session, null);
        assertEquals(new DebugCapability.ExceptionBreakpoints(false, true), restored.exceptionBreakpoints());
    }

    @Test
    void aToggleIsSentToTheDebugProviderAndAnUnsupportedFilterIsReported() throws Exception {
        DebugProviderFixture.RecordingDebug debug = new DebugProviderFixture.RecordingDebug(false);
        BuiltinEditor editor = DebugProviderFixture.editor(project, project.resolve("editor.properties"), debug);
        BlockingQueue<String> reports = new LinkedBlockingQueue<>();

        editor.toggleExceptionBreakpoint(true, reports::add);

        assertEquals(new DebugCapability.ExceptionBreakpoints(true, true),
                debug.requests().poll(5, TimeUnit.SECONDS));
        String report = reports.poll(5, TimeUnit.SECONDS);
        assertNotNull(report, "a filter the adapter does not apply is reported");
        assertEquals("The debug adapter does not support breaking on caught exceptions", report);
    }

    @Test
    void aProviderFailureIsReportedWithItsReason() throws Exception {
        DebugProviderFixture.RecordingDebug debug = new DebugProviderFixture.RecordingDebug(true);
        BuiltinEditor editor = DebugProviderFixture.editor(project, project.resolve("editor.properties"), debug);
        BlockingQueue<String> reports = new LinkedBlockingQueue<>();

        editor.toggleExceptionBreakpoint(false, reports::add);

        assertEquals("Exception breakpoints not applied: adapter refused", reports.poll(5, TimeUnit.SECONDS));
    }
}
