package dev.omatheusmesmo.vintelli.acp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers two stdio twins as named agents: {@code sessions} advertises session
 * listing and loading, and {@code plain} advertises neither.
 */
public final class AcpSessionsAgentResource extends EchoAcpAgentResource {

    private Path plainArgumentFile;

    @Override
    protected List<String> twinArguments() {
        return List.of("--sessions");
    }

    @Override
    public Map<String, String> start() {
        Map<String, String> properties = new HashMap<>(super.start());
        properties.put("vintelli.acp.enabled", "true");
        properties.put("vintelli.acp.agents.sessions.name", "Sessions agent");
        properties.put("vintelli.acp.agents.sessions.command", properties.get("vintelli.acp.probe.agent-command"));
        properties.put("vintelli.acp.agents.sessions.args",
                listValue(properties.get("vintelli.acp.probe.agent-args")));
        properties.put("vintelli.acp.agents.plain.name", "Plain agent");
        properties.put("vintelli.acp.agents.plain.command", COMMAND);
        properties.put("vintelli.acp.agents.plain.args",
                listValue(AgentArgumentFile.launcherArgument(plainArguments())));
        return properties;
    }

    /**
     * The agent arguments are a list property, whose converter treats a backslash as an escape, so a
     * Windows path in the argument file reference needs its backslashes doubled to survive.
     */
    private static String listValue(String argument) {
        return argument.replace("\\", "\\\\");
    }

    private Path plainArguments() {
        try {
            plainArgumentFile = AgentArgumentFile.write(
                    List.of("-cp", System.getProperty("java.class.path"), EchoAcpAgent.class.getName()));
            return plainArgumentFile;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public void stop() {
        super.stop();
        try {
            if (plainArgumentFile != null) {
                Files.deleteIfExists(plainArgumentFile);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
