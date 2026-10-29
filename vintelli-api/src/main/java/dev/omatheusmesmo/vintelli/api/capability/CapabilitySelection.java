package dev.omatheusmesmo.vintelli.api.capability;

import java.util.Objects;

/**
 * One capability read through the registry: the typed provider port when a
 * healthy provider exists, otherwise an explicit unavailable state whose
 * reason tool windows show as is. Pattern match on the two cases instead of
 * asking {@link #isAvailable()} and then relying on a nullable provider.
 *
 * @param <T> the port type providers of this capability implement
 */
public sealed interface CapabilitySelection<T> {

    static <T> CapabilitySelection<T> selected(CapabilityType capability, T provider) {
        return new Selected<>(capability, provider);
    }

    static <T> CapabilitySelection<T> unavailable(CapabilityType capability, String reason) {
        return new Unavailable<>(capability, reason);
    }

    default boolean isAvailable() {
        return !(this instanceof Unavailable<T>);
    }

    record Selected<T>(CapabilityType capability, T provider) implements CapabilitySelection<T> {

        public Selected {
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(provider, "provider");
        }
    }

    record Unavailable<T>(CapabilityType capability, String reason) implements CapabilitySelection<T> {

        public Unavailable {
            Objects.requireNonNull(capability, "capability");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("An unavailable selection always needs a reason");
            }
        }
    }
}
