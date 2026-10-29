package dev.omatheusmesmo.vintelli.capabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.api.capability.SkippedProvider;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

/**
 * Selection through real CDI bean discovery with the provider order set by a
 * configuration profile: the ranked first provider serves until its health
 * flips, then the registry re-selects the next one and the status row reports
 * the skipped provider with its reason.
 */
@QuarkusTest
@TestProfile(RankedReadFileProfile.class)
class CapabilityRegistrySelectionTest {

    @Inject
    CapabilityRegistry registry;

    @Inject
    PrimaryStubReadFileProvider primary;

    @Test
    void selectsBeansByConfiguredOrderAndReSelectsAfterAHealthFlip() {
        assertEquals("primary-reader", ((CapabilitySelection.Selected<ReadFileCapability>) registry.readFile()).provider().identifier());

        CapabilitySelection<RunCapability> run = registry.run();
        assertTrue(run.isAvailable());
        assertEquals("local", ((CapabilitySelection.Selected<RunCapability>) run).provider().identifier());

        primary.markUnavailable("lsp process exited");

        assertEquals("backup-reader", ((CapabilitySelection.Selected<ReadFileCapability>) registry.readFile()).provider().identifier());

        CapabilityStatus row = statusRow(registry.snapshot(), CapabilityType.READ_FILE);
        assertEquals("backup-reader", row.providerIdentifier());
        assertEquals(List.of(new SkippedProvider("primary-reader", "lsp process exited")), row.skipped());
    }

    private static CapabilityStatus statusRow(List<CapabilityStatus> snapshot, CapabilityType capability) {
        for (CapabilityStatus row : snapshot) {
            if (row.capability() == capability) {
                return row;
            }
        }
        throw new AssertionError("status row of " + capability + " is missing");
    }
}
