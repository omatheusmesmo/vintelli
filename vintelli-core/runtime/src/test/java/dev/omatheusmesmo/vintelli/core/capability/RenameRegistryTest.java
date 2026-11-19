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
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.RenameCapability;

class RenameRegistryTest {

    @Test
    void theRegistryServesTheRenameProviderAndNamesItInTheStatusRows() {
        DefaultCapabilityRegistry registry = registryWith(List.of(new Rename("jdtls", ProviderHealth.available())),
                new CapabilityOrders(Map.of()));

        CapabilitySelection<RenameCapability> selection = registry.rename();

        assertTrue(selection instanceof CapabilitySelection.Selected<RenameCapability>, selection.toString());
        assertEquals("jdtls", ((CapabilitySelection.Selected<RenameCapability>) selection).provider().identifier());
        CapabilityStatus row = statusOf(registry, CapabilityType.RENAME);
        assertTrue(row.isAvailable());
        assertEquals("jdtls", row.providerIdentifier());
    }

    @Test
    void anUnhealthyRenameProviderIsSkippedForTheNextOneInConfiguredOrder() {
        Map<CapabilityType, List<String>> orders = new EnumMap<>(CapabilityType.class);
        orders.put(CapabilityType.RENAME, List.of("broken", "fallback"));
        DefaultCapabilityRegistry registry = registryWith(List.of(
                new Rename("broken", ProviderHealth.unavailable("the server did not start")),
                new Rename("fallback", ProviderHealth.available())), new CapabilityOrders(orders));

        assertEquals("fallback", statusOf(registry, CapabilityType.RENAME).providerIdentifier());
        assertEquals("fallback", ((CapabilitySelection.Selected<RenameCapability>) registry.rename())
                .provider().identifier());
    }

    @Test
    void withoutAProviderTheRowSaysSoInsteadOfVanishing() {
        DefaultCapabilityRegistry registry = registryWith(List.of(), new CapabilityOrders(Map.of()));

        assertFalse(statusOf(registry, CapabilityType.RENAME).isAvailable());
        assertTrue(registry.rename() instanceof CapabilitySelection.Unavailable<RenameCapability>);
    }

    private static DefaultCapabilityRegistry registryWith(List<RenameCapability> rename, CapabilityOrders orders) {
        return new DefaultCapabilityRegistry(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), rename, orders);
    }

    private static CapabilityStatus statusOf(DefaultCapabilityRegistry registry, CapabilityType capability) {
        return registry.snapshot().stream().filter(row -> row.capability() == capability).findFirst().orElseThrow();
    }

    private record Rename(String identifier, ProviderHealth health) implements RenameCapability {

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
        public CapabilityResult<String> prepareRename(Path file, String content, int line, int column) {
            return CapabilityResult.ok("");
        }

        @Override
        public CapabilityResult<WorkspaceEdit> rename(Path file, String content, int line, int column, String newName) {
            return CapabilityResult.ok(new WorkspaceEdit(List.of(), List.of()));
        }
    }
}
