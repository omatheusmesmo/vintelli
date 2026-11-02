package dev.omatheusmesmo.vintelli.acp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Test twin that records remote session close and child-process shutdown. */
public final class AcpChatServiceLifecycleResource extends EchoAcpAgentResource {

    private static Path lifecycleLog;

    @Override
    public Map<String, String> start() {
        try {
            lifecycleLog = Files.createTempFile("vintelli-acp-lifecycle-", ".log");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return super.start();
    }

    @Override
    protected List<String> twinArguments() {
        return List.of("--lifecycle-log=" + lifecycleLog);
    }

    static Path lifecycleLog() {
        return lifecycleLog;
    }

    @Override
    public void stop() {
        super.stop();
        try {
            Files.deleteIfExists(lifecycleLog);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
