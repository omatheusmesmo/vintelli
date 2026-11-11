package dev.omatheusmesmo.vintelli.tui;

import java.util.List;
import java.util.Objects;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.SkippedProvider;

/**
 * Text a tool window shows for one capability: the active provider with its provenance, the higher ranked
 * providers that were skipped, or the unavailable reason as the registry reports it. {@link #read} calls the
 * registry and must run off the render thread; rendering only formats the stored {@link Summary}.
 */
final class CapabilityStateLine {

    private CapabilityStateLine() {
    }

    /**
     * One headline line for the capability and one line per skipped provider, each already formatted.
     */
    record Summary(String headline, List<String> skipped) {

        Summary {
            Objects.requireNonNull(headline, "headline");
            skipped = List.copyOf(skipped);
        }
    }

    static String label(CapabilityType capability) {
        return switch (capability) {
            case RUN -> "Run";
            case TERMINAL -> "Terminal";
            case READ_FILE -> "Read File";
            case DIAGNOSTICS -> "Problems";
            case CALL_HIERARCHY -> "Call Hierarchy";
            case CODE_ACTION -> "Code Actions";
            case DEBUG -> "Debug";
            case EDITOR_BRIDGE -> "Editor";
            case VERSION_CONTROL -> "VCS";
        };
    }

    static Summary checking(CapabilityType capability) {
        return new Summary(label(capability) + ": checking provider…", List.of());
    }

    static Summary notConfigured(CapabilityType capability) {
        return new Summary(label(capability) + " unavailable: capability registry is not configured", List.of());
    }

    /**
     * Formats the row of {@code capability} from a status snapshot. A missing row is reported as unavailable
     * rather than failing, so a registry that omits a capability still renders an explicit state.
     */
    static Summary of(List<CapabilityStatus> statuses, CapabilityType capability) {
        String label = label(capability);
        CapabilityStatus row = statuses == null ? null : statuses.stream()
                .filter(status -> status.capability() == capability)
                .findFirst()
                .orElse(null);
        if (row == null) {
            return new Summary(label + " unavailable: no provider status reported", List.of());
        }
        if (!row.isAvailable()) {
            return new Summary(label + " unavailable: " + row.health().reason(), List.of());
        }
        List<String> skipped = row.skipped().stream().map(CapabilityStateLine::skippedLine).toList();
        return new Summary(label + ": " + row.providerIdentifier() + " (" + row.provenance() + ")", skipped);
    }

    /**
     * Reads the snapshot of {@code registry} and formats {@code capability}. Blocking: call it off the render
     * thread. A failing read becomes an explicit unavailable state instead of an exception.
     */
    static Summary read(CapabilityRegistry registry, CapabilityType capability) {
        if (registry == null) {
            return notConfigured(capability);
        }
        try {
            return of(registry.snapshot(), capability);
        } catch (RuntimeException failure) {
            String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return new Summary(label(capability) + " unavailable: status read failed: " + detail, List.of());
        }
    }

    private static String skippedLine(SkippedProvider skipped) {
        return "skipped " + skipped.identifier() + ": " + skipped.reason();
    }
}
