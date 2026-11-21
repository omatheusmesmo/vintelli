package dev.omatheusmesmo.vintelli.tui.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import dev.tamboui.terminal.BackendProvider;
import org.jline.terminal.Terminal.MouseTracking;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;

class TrackingJLineBackendTest {

    @Test
    void drivesJLineMouseTrackingTransitionsAndResetsOnClose() throws Exception {
        RecordingTerminal terminal = new RecordingTerminal();
        try (terminal) {
            try (TrackingJLineBackend backend = new TrackingJLineBackend(terminal)) {
                backend.enableMouseCapture(false);
                backend.enableMouseCapture(true);
                backend.disableMouseCapture();
            }
            assertEquals(List.of(MouseTracking.Button, MouseTracking.Any, MouseTracking.Off),
                    terminal.transitions());

            terminal.clearTransitions();
            try (TrackingJLineBackend backend = new TrackingJLineBackend(terminal)) {
                backend.enableMouseCapture(true);
            }
            assertEquals(List.of(MouseTracking.Any, MouseTracking.Off), terminal.transitions());
        }
    }

    @Test
    void rewritesUnderlineColorsToColonFormInWriteRaw() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DumbTerminal terminal = new DumbTerminal(InputStream.nullInputStream(), output);
                TrackingJLineBackend backend = new TrackingJLineBackend(terminal)) {
            backend.writeRaw("\u001b[4;58;2;1;2;3m");
            backend.flush();
        }
        String written = output.toString(StandardCharsets.UTF_8);
        assertTrue(written.contains("\u001b[4;58:2::1:2:3m"), written);
    }

    @Test
    void serviceLoaderDiscoversVintelliJLine3Provider() {
        List<BackendProvider> matched = ServiceLoader.load(BackendProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(provider -> TrackingJLineBackendProvider.NAME.equals(provider.name()))
                .toList();
        assertEquals(1, matched.size());
        assertInstanceOf(TrackingJLineBackendProvider.class, matched.getFirst());
    }

    private static final class RecordingTerminal extends DumbTerminal {

        private final List<MouseTracking> transitions = new ArrayList<>();

        RecordingTerminal() throws IOException {
            super(InputStream.nullInputStream(), new ByteArrayOutputStream());
        }

        @Override
        public boolean trackMouse(MouseTracking tracking) {
            transitions.add(tracking);
            return true;
        }

        List<MouseTracking> transitions() {
            return List.copyOf(transitions);
        }

        void clearTransitions() {
            transitions.clear();
        }
    }
}

