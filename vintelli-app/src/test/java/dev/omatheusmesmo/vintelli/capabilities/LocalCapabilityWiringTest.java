package dev.omatheusmesmo.vintelli.capabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.ProviderDescriptor;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

/**
 * The providers of {@code vintelli-local} reach the assembled app as plain CDI
 * beans: the registry serves all three local capabilities through bean
 * discovery, and removing any of the provider beans breaks this test exactly
 * the way an unregistered provider would in production.
 */
@QuarkusTest
@TestProfile(LocalProvidersRankedProfile.class)
class LocalCapabilityWiringTest {

    @Inject
    CapabilityRegistry registry;

    @Test
    void servesRunTerminalAndReadFileThroughDiscoveredLocalBeans() {
        assertSelectedLocal("run", registry.run());
        assertSelectedLocal("terminal", registry.terminal());
        assertSelectedLocal("read-file", registry.readFile());
    }

    @Test
    void keepsDiagnosticsAndCallHierarchyExplicitlyUnavailable() {
        var diagnostics = registry.diagnostics();
        var callHierarchy = registry.callHierarchy();
        assertTrue(!diagnostics.isAvailable() && !callHierarchy.isAvailable(),
                "no provider exists for these two capabilities yet");
    }

    private void assertSelectedLocal(String capability, CapabilitySelection<? extends ProviderDescriptor> selection) {
        assertTrue(selection.isAvailable(), capability + " has no registered provider");
        assertEquals("local", ((CapabilitySelection.Selected<? extends ProviderDescriptor>) selection)
                .provider().identifier(), capability + " is not served by the local provider");
    }
}
