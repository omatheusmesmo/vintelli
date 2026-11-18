package dev.omatheusmesmo.vintelli.core.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Suggestion;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;

class CompletionRegistryTest {

    @Test
    void theRegistryServesTheCompletionProviderAndNamesItInTheStatusRows() {
        DefaultCapabilityRegistry registry = registryWith(List.of(new Completion("jdtls", ProviderHealth.available())),
                new CapabilityOrders(Map.of()));

        CapabilitySelection<CompletionCapability> selection = registry.completion();

        assertTrue(selection instanceof CapabilitySelection.Selected<CompletionCapability>, selection.toString());
        assertEquals("jdtls", ((CapabilitySelection.Selected<CompletionCapability>) selection).provider().identifier());
        CapabilityStatus row = statusOf(registry, CapabilityType.COMPLETION);
        assertTrue(row.isAvailable());
        assertEquals("jdtls", row.providerIdentifier());
    }

    @Test
    void anUnhealthyCompletionProviderIsSkippedForTheNextOneInConfiguredOrder() {
        Map<CapabilityType, List<String>> orders = new EnumMap<>(CapabilityType.class);
        orders.put(CapabilityType.COMPLETION, List.of("broken", "fallback"));
        DefaultCapabilityRegistry registry = registryWith(List.of(
                new Completion("broken", ProviderHealth.unavailable("the server did not start")),
                new Completion("fallback", ProviderHealth.available())), new CapabilityOrders(orders));

        assertEquals("fallback", statusOf(registry, CapabilityType.COMPLETION).providerIdentifier());
        assertEquals("fallback", ((CapabilitySelection.Selected<CompletionCapability>) registry.completion())
                .provider().identifier());
    }

    @Test
    void withoutAProviderTheRowSaysSoInsteadOfVanishing() {
        DefaultCapabilityRegistry registry = registryWith(List.of(), new CapabilityOrders(Map.of()));

        assertFalse(statusOf(registry, CapabilityType.COMPLETION).isAvailable());
        assertTrue(registry.completion() instanceof CapabilitySelection.Unavailable<CompletionCapability>);
    }

    private static DefaultCapabilityRegistry registryWith(List<CompletionCapability> completion,
            CapabilityOrders orders) {
        return new DefaultCapabilityRegistry(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), completion, orders);
    }

    private static CapabilityStatus statusOf(DefaultCapabilityRegistry registry, CapabilityType capability) {
        return registry.snapshot().stream().filter(row -> row.capability() == capability).findFirst().orElseThrow();
    }

    private record Completion(String identifier, ProviderHealth health) implements CompletionCapability {

        @Override
        public String identifier() {
            return identifier;
        }

        @Override
        public ProviderHealth health() {
            return health;
        }

        @Override
        public String provenance() {
            return identifier + " over stdio";
        }

        @Override
        public CapabilityResult<List<Suggestion>> complete(Path file, String content, int line, int column) {
            return CapabilityResult.ok(List.of());
        }
    }
}
