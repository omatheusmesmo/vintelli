package dev.omatheusmesmo.vintelli.tui.terminal;

import java.io.IOException;

import dev.tamboui.backend.jline3.JLineBackend;
import org.jline.terminal.Terminal;

/**
 * The JLine terminal backend the shell draws with, rewriting its SGR output to
 * the form terminals read. TamboUI draws every cell through {@code writeRaw(String)},
 * so the rewrite sits on that one path and every other sequence passes through
 * unchanged.
 */
public final class UnderlineColorBackend extends JLineBackend {

    public UnderlineColorBackend() throws IOException {
        super();
    }

    public UnderlineColorBackend(Terminal terminal) {
        super(terminal);
    }

    @Override
    public void writeRaw(String data) {
        super.writeRaw(SgrCompatibility.forTerminals(data));
    }
}
