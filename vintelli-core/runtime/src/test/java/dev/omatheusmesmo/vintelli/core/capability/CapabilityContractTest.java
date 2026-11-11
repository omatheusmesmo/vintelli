package dev.omatheusmesmo.vintelli.core.capability;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability.RunRequest;
import dev.omatheusmesmo.vintelli.api.capability.SkippedProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Invariants of the public records in {@code vintelli-api} that providers and
 * consumers rely on: caller supplied collections are copied defensively, and an
 * unavailable state can never be built without a reason. The api module has no
 * test dependencies by design, so these run next to its implementation.
 */
class CapabilityContractTest {

    @Test
    void runRequestKeepsItsOwnImmutableCopyOfTheEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("JAVA_HOME", "/opt/java");

        RunRequest request = new RunRequest("compile", Path.of("."), environment);
        environment.put("LEAKED", "yes");

        assertEquals(Map.of("JAVA_HOME", "/opt/java"), request.environment());
        assertThrows(UnsupportedOperationException.class, () -> request.environment().put("MUTATED", "yes"));
    }

    @Test
    void statusRowKeepsItsOwnImmutableCopyOfTheSkippedProviders() {
        List<SkippedProvider> skipped = new ArrayList<>();
        skipped.add(new SkippedProvider("jdtls", "still indexing"));

        CapabilityStatus row = CapabilityStatus.available(CapabilityType.DIAGNOSTICS, "idea", "IDEA MCP server", skipped);
        skipped.add(new SkippedProvider("leaked", "added after construction"));

        assertEquals(List.of(new SkippedProvider("jdtls", "still indexing")), row.skipped());
        assertThrows(UnsupportedOperationException.class,
                () -> row.skipped().add(new SkippedProvider("mutated", "no")));
    }

    @Test
    void statusRowWithoutAProviderIsAlwaysUnavailableWithAReason() {
        CapabilityStatus row = new CapabilityStatus(CapabilityType.RUN, null, null, null, null);

        assertFalse(row.isAvailable());
        assertEquals("unavailable", row.health().reason());
        assertEquals(List.of(), row.skipped());
    }

    @Test
    void unavailableStatesAlwaysCarryAReason() {
        assertThrows(IllegalArgumentException.class, () -> ProviderHealth.unavailable(null));
        assertThrows(IllegalArgumentException.class, () -> ProviderHealth.unavailable("  "));
        assertThrows(IllegalArgumentException.class,
                () -> CapabilitySelection.unavailable(CapabilityType.RUN, ""));
        assertThrows(IllegalArgumentException.class, () -> new SkippedProvider("jdtls", null));
    }

    @Test
    void snapshotRowsAreHandedOutAsAnImmutableList() {
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), new CapabilityOrders(Map.of()));

        List<CapabilityStatus> snapshot = registry.snapshot();

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(CapabilityStatus.unavailable(CapabilityType.RUN, "mutated")));
        assertEquals(CapabilityType.values().length, registry.snapshot().size());
    }

    @Test
    void debugSnapshotCopiesThreadsAndNormalizesOptionalText() {
        List<DebugCapability.DebugThread> threads = new ArrayList<>();
        threads.add(new DebugCapability.DebugThread(1, "main"));

        DebugCapability.DebugSessionSnapshot snapshot = new DebugCapability.DebugSessionSnapshot(
                "session-1", "maven:test", DebugCapability.State.STOPPED, threads, null, "breakpoint");
        threads.clear();

        assertEquals(List.of(new DebugCapability.DebugThread(1, "main")), snapshot.threads());
        assertEquals("", snapshot.reason());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.threads().add(new DebugCapability.DebugThread(2, "worker")));
    }

    @Test
    void debugAttachRequestOnlyAcceptsValidPortsAndCarriesNoRemoteHost() {
        DebugCapability.AttachRequest request = new DebugCapability.AttachRequest(5005, "local target");

        assertEquals(5005, request.port());
        assertEquals("local target", request.label());
        assertThrows(IllegalArgumentException.class, () -> new DebugCapability.AttachRequest(0, "invalid"));
        assertThrows(IllegalArgumentException.class, () -> new DebugCapability.AttachRequest(65536, "invalid"));
    }
}
