package dev.omatheusmesmo.vintelli.acp;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Arrays;
import java.util.Map;

/**
 * Points the probe at a foreign ACP agent installed by the CI native job. The
 * command line comes from {@code VINTELLI_FOREIGN_ACP_COMMAND}, for example
 * {@code <binary> acp}; the probe spawns it like any other agent.
 */
public final class ForeignAcpAgentResource implements QuarkusTestResourceLifecycleManager {

    static final String COMMAND_VARIABLE = "VINTELLI_FOREIGN_ACP_COMMAND";

    @Override
    public Map<String, String> start() {
        String[] tokens = System.getenv(COMMAND_VARIABLE).trim().split("\\s+");
        String args = String.join(",", Arrays.copyOfRange(tokens, 1, tokens.length));
        return Map.of(
                "vintelli.acp.probe.enabled", "true",
                "vintelli.acp.probe.agent-command", tokens[0],
                "vintelli.acp.probe.agent-args", args);
    }

    @Override
    public void stop() {
    }
}
