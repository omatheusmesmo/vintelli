package dev.omatheusmesmo.vintelli.acp;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wires the {@link EchoAcpAgent} stdio twin to the application under test
 * through the config properties it returns, which also reach the native
 * runner: the agent is spawned as a plain JVM with the test classpath, so the
 * probe client in the native image talks to a real ACP agent process. The
 * classpath travels in a JDK argument file (see {@link AgentArgumentFile}) so
 * the spawned command line stays short on every platform.
 */
public class EchoAcpAgentResource implements QuarkusTestResourceLifecycleManager {

    static final String COMMAND = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    private Path argumentFile;

    /**
     * Extra arguments handed to the twin after its main class.
     */
    protected List<String> twinArguments() {
        return List.of();
    }

    @Override
    public Map<String, String> start() {
        List<String> javaArguments = new ArrayList<>(
                List.of("-cp", System.getProperty("java.class.path"), EchoAcpAgent.class.getName()));
        javaArguments.addAll(twinArguments());
        try {
            argumentFile = AgentArgumentFile.write(javaArguments);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.of(
                "vintelli.acp.probe.enabled", "true",
                "vintelli.acp.probe.agent-command", COMMAND,
                "vintelli.acp.probe.agent-args", AgentArgumentFile.launcherArgument(argumentFile));
    }

    @Override
    public void stop() {
        if (argumentFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(argumentFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
