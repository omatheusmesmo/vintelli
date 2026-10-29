package dev.omatheusmesmo.vintelli.api.capability;

/**
 * Failure of one capability call that the provider resolved but could not
 * serve, for example a removed file, an unknown run configuration or a
 * language server that stopped answering. Ports declare this contract instead
 * of exceptions: a provider returns a failed result for an expected failure
 * and only throws for programming errors, which the registry degrades into
 * unavailable states.
 */
public record CapabilityFailure(String reason, String detail) {

    public CapabilityFailure {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A capability failure always needs a reason");
        }
        detail = detail == null ? "" : detail;
    }
}
