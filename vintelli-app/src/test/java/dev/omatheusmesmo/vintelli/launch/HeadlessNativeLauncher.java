package dev.omatheusmesmo.vintelli.launch;

import java.io.IOException;

import io.quarkus.test.common.DefaultNativeImageLauncher;

/** Starts the HTTP test fixture with the explicit server command. */
public final class HeadlessNativeLauncher extends DefaultNativeImageLauncher {

    @Override
    public void start(String[] programArgs, boolean handleIo) throws IOException {
        super.start(handleIo && programArgs.length == 0 ? new String[] { "serve" } : programArgs, handleIo);
    }
}
