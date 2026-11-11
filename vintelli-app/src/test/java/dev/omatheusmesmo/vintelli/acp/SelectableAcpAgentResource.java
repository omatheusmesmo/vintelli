package dev.omatheusmesmo.vintelli.acp;

import java.util.HashMap;
import java.util.Map;

public final class SelectableAcpAgentResource extends EchoAcpAgentResource {

    @Override
    public Map<String, String> start() {
        Map<String, String> properties = new HashMap<>(super.start());
        properties.put("vintelli.acp.enabled", "true");
        properties.put("vintelli.acp.agents.opencode.command", properties.get("vintelli.acp.probe.agent-command"));
        properties.put("vintelli.acp.agents.opencode.args", properties.get("vintelli.acp.probe.agent-args"));
        properties.put("vintelli.acp.agents.pi.command", "vintelli-missing-agent-executable");
        return properties;
    }
}
