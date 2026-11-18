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
import dev.omatheusmesmo.vintelli.api.capability.HoverCapability;
import dev.omatheusmesmo.vintelli.api.capability.HoverCapability.Documentation;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;

class HoverRegistryTest {

    @Test
    void theRegistryServesTheHoverProviderAndNamesItInTheStatusRows() {
        DefaultCapabilityRegistry registry = registryWith(List.of(new Hover("jdtls", ProviderHealth.available())),
                new CapabilityOrders(Map.of()));

        CapabilitySelection<HoverCapability> selection = registry.hover();

        assertTrue(selection instanceof CapabilitySelection.Selected<HoverCapability>, selection.toString());
        assertEquals("jdtls", ((CapabilitySelection.Selected<HoverCapability>) selection).provider().identifier());
        CapabilityStatus row = statusOf(registry, CapabilityType.HOVER);
        assertTrue(row.isAvailable());
        assertEquals("jdtls", row.providerIdentifier());
    }

    @Test
    void anUnhealthyHoverProviderIsSkippedForTheNextOneInConfiguredOrder() {
        Map<CapabilityType, List<String>> orders = new EnumMap<>(CapabilityType.class);
        orders.put(CapabilityType.HOVER, List.of("broken", "fallback"));
        DefaultCapabilityRegistry registry = registryWith(List.of(
                new Hover("broken", ProviderHealth.unavailable("the server did not start")),
                new Hover("fallback", ProviderHealth.available())), new CapabilityOrders(orders));

        assertEquals("fallback", statusOf(registry, CapabilityType.HOVER).providerIdentifier());
        assertEquals("fallback", ((CapabilitySelection.Selected<HoverCapability>) registry.hover())
                .provider().identifier());
    }

    @Test
    void withoutAProviderTheRowSaysSoInsteadOfVanishing() {
        DefaultCapabilityRegistry registry = registryWith(List.of(), new CapabilityOrders(Map.of()));

        assertFalse(statusOf(registry, CapabilityType.HOVER).isAvailable());
        assertTrue(registry.hover() instanceof CapabilitySelection.Unavailable<HoverCapability>);
    }

    private static DefaultCapabilityRegistry registryWith(List<HoverCapability> hover, CapabilityOrders orders) {
        return new DefaultCapabilityRegistry(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), hover, orders);
    }

    private static CapabilityStatus statusOf(DefaultCapabilityRegistry registry, CapabilityType capability) {
        return registry.snapshot().stream().filter(row -> row.capability() == capability).findFirst().orElseThrow();
    }

    private record Hover(String identifier, ProviderHealth health) implements HoverCapability {

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
        public CapabilityResult<Documentation> hover(Path file, String content, int line, int column) {
            return CapabilityResult.ok(new Documentation(""));
        }
    }
}
