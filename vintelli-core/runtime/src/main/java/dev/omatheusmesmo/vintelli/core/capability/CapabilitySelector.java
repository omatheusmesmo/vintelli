package dev.omatheusmesmo.vintelli.core.capability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;

/**
 * Pure selection engine for one capability. Providers listed in the configured
 * order are ranked first, unlisted providers follow sorted by identifier, and
 * the first healthy provider in that ranking wins. The selector never throws
 * for missing or unhealthy providers: it always returns an explicit state, so
 * a capability without a single provider degrades visibly instead of breaking
 * the app.
 */
final class CapabilitySelector {

    private CapabilitySelector() {
    }

    /**
     * Ranks the given provider snapshots and picks the first healthy one.
     *
     * @param capability capability being resolved, used in failure reasons
     * @param providers snapshots of the registered providers, any order
     * @param providerOrder configured order of provider identifiers, may be empty
     * @return a choice that is either selected (with the providers skipped
     *         ranked before it) or unavailable with a reason
     */
    static ProviderChoice choose(CapabilityType capability, List<ProviderSnapshot> providers, List<String> providerOrder) {
        List<ProviderSnapshot> ranked = rank(providers, providerOrder);
        if (ranked.isEmpty()) {
            return ProviderChoice.unavailable("no provider is registered for '" + capability.label() + "'");
        }
        List<ProviderSnapshot> skipped = new ArrayList<>(ranked.size());
        for (ProviderSnapshot candidate : ranked) {
            if (candidate.isAvailable()) {
                return ProviderChoice.selected(candidate, skipped);
            }
            skipped.add(candidate);
        }
        return ProviderChoice.unavailable(unhealthyReason(capability, ranked));
    }

    /**
     * Providers mentioned in the configured order come first, in that order;
     * the unlisted ones follow sorted by identifier, so the result stays
     * deterministic when several providers match. Duplicate identifiers keep
     * their encounter order.
     */
    static List<ProviderSnapshot> rank(List<ProviderSnapshot> providers, List<String> providerOrder) {
        boolean[] taken = new boolean[providers.size()];
        List<ProviderSnapshot> ranked = new ArrayList<>(providers.size());
        for (String identifier : providerOrder) {
            for (int index = 0; index < providers.size(); index++) {
                if (!taken[index] && identifier.equals(providers.get(index).identifier())) {
                    taken[index] = true;
                    ranked.add(providers.get(index));
                }
            }
        }
        List<ProviderSnapshot> unlisted = new ArrayList<>();
        for (int index = 0; index < providers.size(); index++) {
            if (!taken[index]) {
                unlisted.add(providers.get(index));
            }
        }
        unlisted.sort(Comparator.comparing(ProviderSnapshot::identifier));
        ranked.addAll(unlisted);
        return List.copyOf(ranked);
    }

    private static String unhealthyReason(CapabilityType capability, List<ProviderSnapshot> ranked) {
        String detail = ranked.stream()
                .map(provider -> provider.identifier() + " (" + provider.health().reason() + ")")
                .collect(Collectors.joining(", "));
        return "no healthy provider for '" + capability.label() + "': " + detail;
    }
}
