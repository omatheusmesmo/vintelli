package dev.omatheusmesmo.vintelli.core.acp;

/**
 * An agent asked the client for a file outside the session root.
 */
public final class AcpAccessDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AcpAccessDeniedException(String message) {
        super(message);
    }
}
