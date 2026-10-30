package dev.omatheusmesmo.vintelli.core.acp;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Checks the SDK does not perform on its own (acp-core 0.80.0 accepts any
 * protocol version and sends MCP servers without looking at the agent's
 * capabilities): the agent must answer the protocol version this client speaks,
 * and HTTP or SSE MCP servers are only sent to agents that advertise them.
 */
public final class AcpAgentGate {

    public static final int SUPPORTED_PROTOCOL_VERSION = 1;

    private AcpAgentGate() {
    }

    public static void requireSupportedVersion(AcpSchema.InitializeResponse response) {
        Integer agentVersion = response.protocolVersion();
        if (agentVersion == null || agentVersion != SUPPORTED_PROTOCOL_VERSION) {
            throw new AcpGateException("agent answered protocol version " + agentVersion
                    + ", this client speaks only version " + SUPPORTED_PROTOCOL_VERSION);
        }
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
