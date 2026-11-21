package dev.omatheusmesmo.vintelli.tui.terminal;

import java.io.IOException;

import dev.tamboui.backend.jline3.JLineBackend;
import org.jline.terminal.Terminal;
import org.jline.terminal.Terminal.MouseTracking;

/**
 * {@link JLineBackend} that drives JLine's {@link Terminal#trackMouse(MouseTracking)}
 * alongside the DECSET SGR escape sequences and rewrites SGR underline colors to
 * the colon form terminals read. On Windows, JLine's {@code AbstractWindowsTerminal}
 * drops console mouse input records unless {@code trackMouse} enables
 * {@code ENABLE_MOUSE_INPUT} on the input handle.
 */
public final class TrackingJLineBackend extends JLineBackend {

    public TrackingJLineBackend() throws IOException {
        super();
    }

    public TrackingJLineBackend(Terminal terminal) {
        super(terminal);
    }

    @Override
    public void writeRaw(String data) {
        super.writeRaw(SgrCompatibility.forTerminals(data));
    }

    @Override
    public void enableMouseCapture(boolean motion) throws IOException {
        super.enableMouseCapture(motion);
        jlineTerminal().trackMouse(motion ? MouseTracking.Any : MouseTracking.Button);
    }

    @Override
    public void disableMouseCapture() throws IOException {
        try {
            jlineTerminal().trackMouse(MouseTracking.Off);
        } finally {
            super.disableMouseCapture();
        }
    }
}

