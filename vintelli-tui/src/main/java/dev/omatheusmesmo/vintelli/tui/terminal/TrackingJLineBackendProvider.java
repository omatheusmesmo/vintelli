package dev.omatheusmesmo.vintelli.tui.terminal;

import java.io.IOException;

import dev.tamboui.terminal.Backend;
import dev.tamboui.terminal.BackendFactory;
import dev.tamboui.terminal.BackendProvider;

/**
 * {@link BackendProvider} for {@link TrackingJLineBackend}, registered under
 * {@value #NAME} so {@link BackendFactory} can resolve it through
 * {@code ServiceLoader}.
 */
public final class TrackingJLineBackendProvider implements BackendProvider {

    public static final String NAME = "vintelli-jline3";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Backend create() throws IOException {
        return new TrackingJLineBackend();
    }
}

