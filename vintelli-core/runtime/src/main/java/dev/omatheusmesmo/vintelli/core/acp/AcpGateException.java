package dev.omatheusmesmo.vintelli.core.acp;

/**
 * Raised when the client refuses to continue with an agent: an unsupported
 * protocol version in the {@code initialize} answer, or an MCP server the agent
 * did not advertise. The refusal happens locally, before anything is sent.
 */
public final class AcpGateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AcpGateException(String message) {
        super(message);
    }
}
