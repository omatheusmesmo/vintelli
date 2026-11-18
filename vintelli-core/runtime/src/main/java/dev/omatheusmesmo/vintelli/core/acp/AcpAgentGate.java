package dev.omatheusmesmo.vintelli.core.acp;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Checks the SDK does not perform on its own: acp-core sends MCP servers without
 * looking at the agent's capabilities, so HTTP or SSE MCP servers are only sent
 * to agents that advertise them. The protocol version is checked by the SDK
 * itself since acp-core 0.81.0, which refuses any answer other than version 1
 * with {@code AcpVersionException} and closes the connection.
 */
public final class AcpAgentGate {

    private AcpAgentGate() {
    }

    public static void requireMcpServerAdvertised(AcpSchema.McpServer server, NegotiatedCapabilities agent) {
        String transport = transportOf(server);
        if (!isAdvertised(transport, agent)) {
            throw new AcpGateException("agent did not advertise the MCP transport " + transport);
        }
    }

    private static String transportOf(AcpSchema.McpServer server) {
        return switch (server) {
            case AcpSchema.McpServerStdio _ -> "stdio";
            case AcpSchema.McpServerHttp _ -> "http";
            case AcpSchema.McpServerSse _ -> "sse";
            default -> "unknown";
        };
    }

    private static boolean isAdvertised(String transport, NegotiatedCapabilities agent) {
        return switch (transport) {
            case "stdio" -> true;
            case "http" -> agent.supportsMcpHttp();
            case "sse" -> agent.supportsMcpSse();
            default -> false;
        };
    }
}
