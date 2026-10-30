package dev.omatheusmesmo.vintelli.core.acp;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcpAgentGateTest {

    private static final AcpSchema.McpServerStdio STDIO = new AcpSchema.McpServerStdio("echo", "echo", List.of(), List.of());
    private static final AcpSchema.McpServerHttp HTTP = new AcpSchema.McpServerHttp("docs", "https://example.invalid/mcp", List.of());
    private static final AcpSchema.McpServerSse SSE = new AcpSchema.McpServerSse("docs", "https://example.invalid/sse", List.of());

    @Test
    void acceptsTheProtocolVersionThisClientSpeaks() {
        assertDoesNotThrow(() -> AcpAgentGate.requireSupportedVersion(initialize(1)));
    }

    @Test
    void refusesAHigherOrLowerVersionAndAMissingOne() {
        assertRefused(() -> AcpAgentGate.requireSupportedVersion(initialize(2)), "version 2");
        assertRefused(() -> AcpAgentGate.requireSupportedVersion(initialize(0)), "version 0");
        assertRefused(() -> AcpAgentGate.requireSupportedVersion(initialize(null)), "version null");
    }

    @Test
    void stdioServersAreAlwaysSent() {
        assertDoesNotThrow(() -> AcpAgentGate.requireMcpServerAdvertised(STDIO, capabilities(false, false)));
    }

    @Test
    void httpServersAreSentOnlyToAgentsAdvertisingHttp() {
        assertDoesNotThrow(() -> AcpAgentGate.requireMcpServerAdvertised(HTTP, capabilities(true, false)));
        assertRefused(() -> AcpAgentGate.requireMcpServerAdvertised(HTTP, capabilities(false, true)), "http");
    }

    @Test
    void sseServersAreSentOnlyToAgentsAdvertisingSse() {
        assertDoesNotThrow(() -> AcpAgentGate.requireMcpServerAdvertised(SSE, capabilities(false, true)));
        assertRefused(() -> AcpAgentGate.requireMcpServerAdvertised(SSE, capabilities(true, false)), "sse");
    }

    private static AcpSchema.InitializeResponse initialize(Integer version) {
        return new AcpSchema.InitializeResponse(version, AcpSchema.AgentCapabilities.builder().build(), List.of());
    }

    private static NegotiatedCapabilities capabilities(boolean http, boolean sse) {
        AcpSchema.McpCapabilities mcp = new AcpSchema.McpCapabilities(http, sse);
        return NegotiatedCapabilities.fromAgent(AcpSchema.AgentCapabilities.builder().mcpCapabilities(mcp).build());
    }

    private static void assertRefused(Executable action, String expectedFragment) {
        AcpGateException refusal = assertThrows(AcpGateException.class, action);
        assertTrue(refusal.getMessage().contains(expectedFragment), refusal.getMessage());
    }
}
