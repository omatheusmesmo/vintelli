package dev.omatheusmesmo.vintelli.capabilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.Opened;
import dev.omatheusmesmo.vintelli.api.capability.SkippedProvider;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

/**
 * Editor bridging through real CDI bean discovery with the provider order set
 * by a configuration profile: the ranked first adapter opens files at the
 * requested line, and when its health flips the registry re-selects the
 * builtin adapter and the status row reports the skipped adapter with its
 * reason.
 */
@QuarkusTest
@TestProfile(RankedEditorBridgeProfile.class)
class EditorBridgeSelectionTest {

    private static final Path PROJECT_FILE = Path.of("README.md");

    @Inject
    CapabilityRegistry registry;

    @Inject
    GenericStubEditorProvider generic;

    @Test
    void opensFilesThroughTheConfiguredAdapterAndReSelectsTheBuiltinOneAfterAHealthFlip() {
        CapabilitySelection.Selected<EditorBridge> selection = selected();
        assertEquals("generic", selection.provider().identifier());

        CapabilityResult<Opened> opened = selection.provider().open(new OpenRequest(PROJECT_FILE, 3));

        assertFalse(opened.isFailure());
        Opened value = valueOf(opened);
        assertEquals(PROJECT_FILE, value.file());
        assertEquals(3, value.line(), "the configured adapter places the cursor on the zero based line");

        generic.markUnavailable("editor process exited");

        CapabilitySelection<EditorBridge> reSelected = registry.editorBridge();
        assertEquals("builtin", providerOf(reSelected).identifier());

        CapabilityStatus row = statusRow();
        assertEquals("builtin", row.providerIdentifier());
        assertEquals("stub builtin text area", row.provenance());
        assertEquals(1, row.skipped().size());
        SkippedProvider skipped = row.skipped().get(0);
        assertEquals("generic", skipped.identifier());
        assertEquals("editor process exited", skipped.reason());
    }

    private CapabilitySelection.Selected<EditorBridge> selected() {
        return (CapabilitySelection.Selected<EditorBridge>) registry.editorBridge();
    }

    private static EditorBridge providerOf(CapabilitySelection<EditorBridge> selection) {
        return ((CapabilitySelection.Selected<EditorBridge>) selection).provider();
    }

    private CapabilityStatus statusRow() {
        for (CapabilityStatus row : registry.snapshot()) {
            if (row.capability() == CapabilityType.EDITOR_BRIDGE) {
                return row;
            }
        }
        throw new AssertionError("status row of the editor bridge is missing");
    }

    private static Opened valueOf(CapabilityResult<Opened> result) {
        return ((CapabilityResult.Ok<Opened>) result).value();
    }
}
