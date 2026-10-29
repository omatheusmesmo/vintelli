package dev.omatheusmesmo.vintelli.api.capability;

import java.util.Objects;

/**
 * Health of a capability provider at one point in time. An
 * {@link State#UNAVAILABLE} provider always carries a human readable reason so
 * tool windows and the status bar can show a clear message instead of failing.
 */
public record ProviderHealth(State state, String reason) {

    public enum State {
        AVAILABLE,
        UNAVAILABLE
    }

    private static final ProviderHealth HEALTHY = new ProviderHealth(State.AVAILABLE, null);

    /**
     * @return the shared healthy state, with no reason
     */
    public static ProviderHealth available() {
        return HEALTHY;
    }

    /**
     * @param reason why the provider cannot serve requests right now, never blank
     * @return an UNAVAILABLE state carrying the given reason
     */
    public static ProviderHealth unavailable(String reason) {
        return new ProviderHealth(State.UNAVAILABLE, reason);
    }

    public boolean isAvailable() {
        return state == State.AVAILABLE;
    }

    public ProviderHealth {
        Objects.requireNonNull(state, "state");
        if (state == State.UNAVAILABLE && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("An unavailable provider always needs a reason");
        }
        if (state == State.AVAILABLE) {
            reason = null;
        }
    }
}
