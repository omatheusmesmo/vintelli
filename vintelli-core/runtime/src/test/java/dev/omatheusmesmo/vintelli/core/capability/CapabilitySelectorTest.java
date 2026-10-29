package dev.omatheusmesmo.vintelli.core.capability;

import org.junit.jupiter.api.Test;

import java.util.List;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.ProviderDescriptor;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ranking, fallback and health flip behavior of the selection engine behind
 * the capability registry.
 */
class CapabilitySelectorTest {

    private static ProviderSnapshot snapshot(String identifier, ProviderHealth health) {
        return new ProviderSnapshot(identifier, "provenance of " + identifier, health);
    }

    @Test
    void ranksProvidersByConfiguredOrderBeforeDiscoveryOrder() {
        List<ProviderSnapshot> providers = List.of(
                snapshot("secondary", ProviderHealth.available()),
                snapshot("primary", ProviderHealth.available()));

        ProviderChoice choice = CapabilitySelector.choose(
                CapabilityType.READ_FILE, providers, List.of("primary", "secondary"));

        assertTrue(choice.isAvailable());
        assertEquals("primary", choice.selected().identifier());
        assertEquals("provenance of primary", choice.selected().provenance());
        assertEquals(List.of(), choice.skipped());
    }

    @Test
    void ranksUnlistedProvidersAlphabeticallyAfterTheConfiguredOnes() {
        List<ProviderSnapshot> providers = List.of(
                snapshot("zulu", ProviderHealth.available()),
                snapshot("alpha", ProviderHealth.available()));

        ProviderChoice choice = CapabilitySelector.choose(
                CapabilityType.RUN, providers, List.of("bravo"));

        assertTrue(choice.isAvailable());
        assertEquals("alpha", choice.selected().identifier());
    }

    @Test
    void fallsBackToTheNextRankedProviderAndReportsTheSkippedOne() {
        List<ProviderSnapshot> providers = List.of(
                snapshot("alpha", ProviderHealth.available()),
                snapshot("bravo", ProviderHealth.unavailable("socket closed")));

        ProviderChoice choice = CapabilitySelector.choose(
                CapabilityType.DIAGNOSTICS, providers, List.of("bravo", "alpha"));

        assertTrue(choice.isAvailable());
        assertEquals("alpha", choice.selected().identifier());
        assertEquals(1, choice.skipped().size());
        assertEquals("bravo", choice.skipped().get(0).identifier());
        assertEquals("socket closed", choice.skipped().get(0).health().reason());
    }

    @Test
    void namesEveryUnhealthyProviderAndItsReasonWhenNoHealthyProviderRemains() {
        List<ProviderSnapshot> providers = List.of(
                snapshot("alpha", ProviderHealth.unavailable("server did not start")),
                snapshot("bravo", ProviderHealth.unavailable("connection refused")));

        ProviderChoice choice = CapabilitySelector.choose(
                CapabilityType.TERMINAL, providers, List.of("alpha", "bravo"));

        assertFalse(choice.isAvailable());
        assertEquals("no healthy provider for 'terminal': alpha (server did not start), bravo (connection refused)",
                choice.unavailableReason());
    }

    @Test
    void reportsTheCapabilityLabelWhenNoProviderIsWiredAtAll() {
        ProviderChoice choice = CapabilitySelector.choose(
                CapabilityType.RUN, List.of(), List.of("anything"));

        assertFalse(choice.isAvailable());
        assertEquals("no provider is registered for 'run'", choice.unavailableReason());
        assertEquals(List.of(), choice.skipped());
    }

    @Test
    void reSelectionFollowsAHealthFlipBetweenTwoReads() {
        FlippingProvider primary = new FlippingProvider("primary");
        List<ProviderSnapshot> providers = List.of(
                snapshot("secondary", ProviderHealth.available()),
                new ProviderSnapshot(primary.identifier(), primary.provenance(), primary.health()));

        ProviderChoice first = CapabilitySelector.choose(
                CapabilityType.READ_FILE, providers, List.of("primary", "secondary"));
        assertTrue(first.isAvailable());
        assertEquals("primary", first.selected().identifier());

        primary.markUnavailable("lsp process exited");
        ProviderSnapshot refreshed = new ProviderSnapshot(primary.identifier(), primary.provenance(), primary.health());
        ProviderChoice second = CapabilitySelector.choose(
                CapabilityType.READ_FILE, List.of(
                        snapshot("secondary", ProviderHealth.available()), refreshed),
                List.of("primary", "secondary"));

        assertTrue(second.isAvailable());
        assertEquals("secondary", second.selected().identifier());
        assertEquals("primary", second.skipped().get(0).identifier());
    }

    @Test
    void degradesProvidersThatThrowWhileBeingRead() {
        ThrowingProvider throwing = new ThrowingProvider();

        ProviderSnapshot degraded = ProviderSnapshot.of("throwy", throwing);

        assertFalse(degraded.isAvailable());
        assertEquals("throwy", degraded.identifier());
        assertTrue(degraded.health().reason().contains("health check failed"));
    }

    @Test
    void refusesToBuildASnapshotWithoutARealIdentifier() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ProviderSnapshot.of(" ", new ThrowingProvider()));

        assertTrue(failure.getMessage().contains("identifier"));
    }

    private static final class FlippingProvider implements ProviderDescriptor {

        private final String identifier;
        private volatile ProviderHealth health;

        private FlippingProvider(String identifier) {
            this.identifier = identifier;
            this.health = ProviderHealth.available();
        }

        private void markUnavailable(String reason) {
            this.health = ProviderHealth.unavailable(reason);
        }

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
            return "flipping test provider";
        }
    }

    private static final class ThrowingProvider implements ProviderDescriptor {

        @Override
        public String identifier() {
            return "throwy";
        }

        @Override
        public ProviderHealth health() {
            throw new IllegalStateException("database is gone");
        }

        @Override
        public String provenance() {
            return "throwing test provider";
        }
    }
}
