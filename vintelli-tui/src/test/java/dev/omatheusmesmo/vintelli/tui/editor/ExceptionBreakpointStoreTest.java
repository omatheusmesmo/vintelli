package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.ExceptionBreakpoints;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExceptionBreakpointStoreTest {

    @TempDir
    Path temporary;

    @Test
    void roundTripsTheChoiceAndDoesNotLeakIntoAnotherProject() throws Exception {
        Path first = Files.createDirectories(temporary.resolve("first"));
        Path second = Files.createDirectories(temporary.resolve("second"));
        ExceptionBreakpointStore store = new ExceptionBreakpointStore(temporary.resolve("settings/exceptions.properties"));

        assertEquals(Optional.empty(), store.load(first), "nothing is stored before the first save");

        store.save(first, new ExceptionBreakpoints(false, true));

        assertEquals(Optional.of(new ExceptionBreakpoints(false, true)), store.load(first));
        assertEquals(Optional.empty(), store.load(second));
    }

    @Test
    void aLaterSaveReplacesTheEarlierChoice() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        ExceptionBreakpointStore store = new ExceptionBreakpointStore(temporary.resolve("exceptions.properties"));

        store.save(project, new ExceptionBreakpoints(true, true));
        store.save(project, new ExceptionBreakpoints(false, false));

        assertEquals(Optional.of(new ExceptionBreakpoints(false, false)), store.load(project));
    }
}
