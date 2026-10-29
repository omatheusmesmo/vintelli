package dev.omatheusmesmo.vintelli.core.capability;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.Opened;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.SkippedProvider;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editor bridging through the registry with hand wired fake providers: the
 * builtin adapter serves by default without any configuration and without an
 * external device, the configured order picks another adapter, and an
 * unavailable adapter degrades with a clear status message so the next adapter
 * still gets tried.
 */
class EditorBridgeCapabilityTest {

    private static final Map<CapabilityType, List<String>> NO_ORDER = Map.of();

    private static final Path PROJECT_FILE = Path.of("src", "Main.java");

    @Test
    void servesTheBuiltinEditorByDefaultWithoutAnyExternalTooling() {
        FakeEditorProvider builtin = new FakeEditorProvider("builtin", "builtin text area");
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(builtin), List.of(), orders(NO_ORDER));

        EditorBridge editor = providerOf(registry.editorBridge());

        assertEquals("builtin", editor.identifier());
        assertEquals("builtin text area", editor.provenance());
        assertEquals(Optional.empty(), editor.currentFile(), "the builtin editor reports nothing before an open request");

        assertEquals(ProviderHealth.State.AVAILABLE, statusOf(registry).health().state());

        CapabilityResult<Opened> opened = editor.open(new OpenRequest(PROJECT_FILE, 17));

        assertFalse(opened.isFailure());
        assertEquals(new Opened(PROJECT_FILE, 17), valueOf(opened),
                "the file is opened and the cursor sits on the requested zero based line");
        assertEquals(Optional.of(PROJECT_FILE), editor.currentFile(), "the builtin editor reports the current file");
    }

    @Test
    void picksTheConfiguredAdapterAtTheRequestedLineBeforeTheBuiltinDefault() {
        FakeEditorProvider builtin = new FakeEditorProvider("builtin", "builtin text area");
        FakeEditorProvider generic = new FakeEditorProvider("generic", "editor process");
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(generic, builtin),
                List.of(), orders(Map.of(CapabilityType.EDITOR_BRIDGE, List.of("generic", "builtin"))));

        EditorBridge editor = providerOf(registry.editorBridge());

        assertEquals("generic", editor.identifier());

        CapabilityResult<Opened> opened = editor.open(new OpenRequest(PROJECT_FILE, 3));

        assertFalse(opened.isFailure());
        assertEquals(new Opened(PROJECT_FILE, 3), valueOf(opened));
        assertEquals(Optional.of(new OpenRequest(PROJECT_FILE, 3)), generic.lastRequest(),
                "the configured adapter receives the file and the zero based line");
        assertTrue(builtin.lastRequest().isEmpty(), "the builtin default must not serve a ranked away request");
    }

    @Test
    void triesTheNextAdapterAndReportsAClearStatusWhenTheConfiguredOneFlipsUnavailable() {
        FakeEditorProvider builtin = new FakeEditorProvider("builtin", "builtin text area");
        FakeEditorProvider generic = new FakeEditorProvider("generic", "editor process");
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(builtin, generic),
                List.of(), orders(Map.of(CapabilityType.EDITOR_BRIDGE, List.of("builtin", "generic"))));

        assertEquals("builtin", selectedIdentifierOf(registry.editorBridge()));
        builtin.markUnavailable();

        CapabilitySelection<EditorBridge> selection = registry.editorBridge();

        assertTrue(selection.isAvailable());
        assertEquals("generic", selectedIdentifierOf(selection), "the next adapter serves");

        CapabilityResult<Opened> opened = providerOf(selection).open(new OpenRequest(PROJECT_FILE, 3));

        assertFalse(opened.isFailure());
        assertEquals(new Opened(PROJECT_FILE, 3), valueOf(opened));
        assertEquals(Optional.of(new OpenRequest(PROJECT_FILE, 3)), generic.lastRequest(),
                "the retried open reaches the next adapter at the requested line");
        assertTrue(builtin.lastRequest().isEmpty(), "the unavailable adapter is not called again");

        CapabilityStatus row = statusOf(registry);
        assertEquals("generic", row.providerIdentifier());
        assertEquals(List.of(new SkippedProvider("builtin", "builtin adapter unavailable")), row.skipped());
    }

    @Test
    void ranksUnlistedAdaptersByIdentifierSoTheBuiltinOneWinsRegardlessOfRegistrationOrder() {
        FakeEditorProvider nvim = new FakeEditorProvider("nvim", "neovim over msgpack-rpc");
        FakeEditorProvider generic = new FakeEditorProvider("generic", "editor process");
        FakeEditorProvider builtin = new FakeEditorProvider("builtin", "builtin text area");
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(nvim, generic, builtin),
                List.of(), orders(NO_ORDER));

        assertEquals("builtin", selectedIdentifierOf(registry.editorBridge()));

        builtin.markUnavailable();
        assertEquals("generic", selectedIdentifierOf(registry.editorBridge()));

        generic.markUnavailable();
        assertEquals("nvim", selectedIdentifierOf(registry.editorBridge()));
        assertEquals(
                List.of(new SkippedProvider("builtin", "builtin adapter unavailable"),
                        new SkippedProvider("generic", "generic adapter unavailable")),
                statusOf(registry).skipped());
    }

    @Test
    void ranksAListedAdapterBeforeTheUnlistedOnesAndFallsBackToTheIdentifierOrder() {
        FakeEditorProvider builtin = new FakeEditorProvider("builtin", "builtin text area");
        FakeEditorProvider generic = new FakeEditorProvider("generic", "editor process");
        FakeEditorProvider nvim = new FakeEditorProvider("nvim", "neovim over msgpack-rpc");
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(builtin, generic, nvim),
                List.of(), orders(Map.of(CapabilityType.EDITOR_BRIDGE, List.of("nvim"))));

        assertEquals("nvim", selectedIdentifierOf(registry.editorBridge()));

        nvim.markUnavailable();

        assertEquals("builtin", selectedIdentifierOf(registry.editorBridge()),
                "after the listed adapter the unlisted ones follow sorted by identifier");
        assertEquals(List.of(new SkippedProvider("nvim", "nvim adapter unavailable")),
                statusOf(registry).skipped());
    }

    @Test
    void ignoresTheProviderOrderOfOtherCapabilities() {
        FakeEditorProvider builtin = new FakeEditorProvider("builtin", "builtin text area");
        FakeEditorProvider generic = new FakeEditorProvider("generic", "editor process");
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(generic, builtin),
                List.of(), orders(Map.of(CapabilityType.READ_FILE, List.of("generic"),
                        CapabilityType.CALL_HIERARCHY, List.of("generic"))));

        assertEquals("builtin", selectedIdentifierOf(registry.editorBridge()));
    }

    @Test
    void keepsTheReasonWhenEveryRegisteredAdapterIsUnavailable() {
        FakeEditorProvider only = new FakeEditorProvider("builtin", "builtin text area");
        only.markUnavailable();
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(only), List.of(), orders(NO_ORDER));

        CapabilitySelection<EditorBridge> selection = registry.editorBridge();

        assertFalse(selection.isAvailable());
        assertEquals("no healthy provider for 'editor-bridge': builtin (builtin adapter unavailable)",
                unavailableReasonOf(selection));
        assertEquals(ProviderHealth.State.UNAVAILABLE, statusOf(registry).health().state());
        assertTrue(statusOf(registry).health().reason().contains("builtin adapter unavailable"),
                "the status message carries the adapter reason");
    }

    @Test
    void reportsAnUnavailableSelectionWithAReasonWhenNoAdapterIsRegistered() {
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), orders(NO_ORDER));

        CapabilitySelection<EditorBridge> selection = registry.editorBridge();

        assertFalse(selection.isAvailable());
        assertEquals("no provider is registered for 'editor-bridge'", unavailableReasonOf(selection));
        assertEquals(ProviderHealth.State.UNAVAILABLE, statusOf(registry).health().state());
    }

    @Test
    void rejectsOpenRequestsAndConfirmationsWithoutAFileOrWithANegativeLine() {
        assertThrows(NullPointerException.class, () -> new OpenRequest(null, 0));
        assertThrows(IllegalArgumentException.class, () -> new OpenRequest(PROJECT_FILE, -1));
        assertThrows(NullPointerException.class, () -> new Opened(null, 0));
        assertThrows(IllegalArgumentException.class, () -> new Opened(PROJECT_FILE, -1));
    }

    private static Opened valueOf(CapabilityResult<Opened> result) {
        return ((CapabilityResult.Ok<Opened>) result).value();
    }

    private static EditorBridge providerOf(CapabilitySelection<EditorBridge> selection) {
        return ((CapabilitySelection.Selected<EditorBridge>) selection).provider();
    }

    private static String selectedIdentifierOf(CapabilitySelection<EditorBridge> selection) {
        return ((CapabilitySelection.Selected<EditorBridge>) selection).provider().identifier();
    }

    private static String unavailableReasonOf(CapabilitySelection<EditorBridge> selection) {
        return ((CapabilitySelection.Unavailable<EditorBridge>) selection).reason();
    }

    private static CapabilityStatus statusOf(DefaultCapabilityRegistry registry) {
        for (CapabilityStatus row : registry.snapshot()) {
            if (row.capability() == CapabilityType.EDITOR_BRIDGE) {
                return row;
            }
        }
        throw new AssertionError("status row of the editor bridge is missing");
    }

    private static CapabilityOrders orders(Map<CapabilityType, List<String>> orders) {
        Map<CapabilityType, List<String>> copy = new EnumMap<>(CapabilityType.class);
        copy.putAll(orders);
        return new CapabilityOrders(copy);
    }

    /**
     * Stands in for the builtin editor adapter: opens a file at the requested
     * line entirely in process, records the request so a test can prove which
     * adapter served and reports the opened file as the current file, the part
     * of the contract only editors with an open file can tell.
     */
    private static final class FakeEditorProvider implements EditorBridge {

        private final String identifier;
        private final String provenance;
        private volatile ProviderHealth health = ProviderHealth.available();
        private volatile OpenRequest lastRequest;

        private FakeEditorProvider(String identifier, String provenance) {
            this.identifier = identifier;
            this.provenance = provenance;
        }

        private void markUnavailable() {
            health = ProviderHealth.unavailable(identifier + " adapter unavailable");
        }

        private Optional<OpenRequest> lastRequest() {
            return Optional.ofNullable(lastRequest);
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
            return provenance;
        }

        @Override
        public CapabilityResult<Opened> open(OpenRequest request) {
            lastRequest = request;
            return CapabilityResult.ok(new Opened(request.file(), request.line()));
        }

        @Override
        public Optional<Path> currentFile() {
            return Optional.ofNullable(lastRequest == null ? null : lastRequest.file());
        }
    }
}
