package dev.omatheusmesmo.vintelli.api.capability;

import java.util.Objects;
import java.util.Optional;

/**
 * Typed outcome of one capability call: either a value or a
 * {@link CapabilityFailure}. Ports never declare checked exceptions, so a call
 * after a healthy selection (which can race with the provider going
 * unavailable) is handled through a failed result instead of a crash.
 *
 * @param <T> the value type of the successful outcome
 */
public sealed interface CapabilityResult<T> {

    static <T> CapabilityResult<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> CapabilityResult<T> failed(CapabilityFailure failure) {
        return new Failed<>(failure);
    }

    default boolean isFailure() {
        return this instanceof Failed<T>;
    }

    /**
     * @return the failure of this result, empty when the outcome is ok
     */
    default Optional<CapabilityFailure> failureOf() {
        if (this instanceof Failed<T> failed) {
            return Optional.of(failed.failure());
        }
        return Optional.empty();
    }

    record Ok<T>(T value) implements CapabilityResult<T> {

        public Ok {
            Objects.requireNonNull(value, "value");
        }
    }

    record Failed<T>(CapabilityFailure failure) implements CapabilityResult<T> {

        public Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }
}
