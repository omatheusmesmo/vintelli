package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.api.capability.SkippedProvider;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;

class CapabilityStateLineTest {

    @Test
    void availableRowNamesTheProviderAndItsProvenance() {
        CapabilityStateLine.Summary summary = CapabilityStateLine.of(List.of(
                CapabilityStatus.available(CapabilityType.DIAGNOSTICS, "jdtls", "jdtls over stdio", List.of())),
                CapabilityType.DIAGNOSTICS);

        assertEquals("Problems: jdtls (jdtls over stdio)", summary.headline());
        assertEquals(List.of(), summary.skipped());
    }

    @Test
    void skippedProvidersKeepTheirIdentifierAndReasonOneLineEach() {
        CapabilityStateLine.Summary summary = CapabilityStateLine.of(List.of(
                CapabilityStatus.available(CapabilityType.DIAGNOSTICS, "jdtls", "jdtls over stdio", List.of(
                        new SkippedProvider("idea", "not connected"),
                        new SkippedProvider("lsp4j", "handshake timed out")))),
                CapabilityType.DIAGNOSTICS);

        assertEquals("Problems: jdtls (jdtls over stdio)", summary.headline());
        assertEquals(List.of("skipped idea: not connected", "skipped lsp4j: handshake timed out"), summary.skipped());
    }

    @Test
    void unavailableRowPrintsTheRegistryReasonAsIs() {
        CapabilityStateLine.Summary summary = CapabilityStateLine.of(List.of(
                CapabilityStatus.unavailable(CapabilityType.DIAGNOSTICS, "no language server is installed")),
                CapabilityType.DIAGNOSTICS);

        assertEquals("Problems unavailable: no language server is installed", summary.headline());
        assertEquals(List.of(), summary.skipped());
    }

    @Test
    void missingRowIsAnExplicitUnavailableStateInsteadOfAFailure() {
        CapabilityStateLine.Summary absent = CapabilityStateLine.of(List.of(
                CapabilityStatus.available(CapabilityType.RUN, "idea", "IDEA MCP", List.of())),
                CapabilityType.DIAGNOSTICS);
        CapabilityStateLine.Summary none = CapabilityStateLine.of(null, CapabilityType.READ_FILE);

        assertEquals("Problems unavailable: no provider status reported", absent.headline());
        assertEquals("Read File unavailable: no provider status reported", none.headline());
    }

    @Test
    void labelsMatchTheStatusBarWording() {
        assertEquals("Read File", CapabilityStateLine.label(CapabilityType.READ_FILE));
        assertEquals("Problems", CapabilityStateLine.label(CapabilityType.DIAGNOSTICS));
        assertEquals("Run", CapabilityStateLine.label(CapabilityType.RUN));
        assertEquals("Terminal", CapabilityStateLine.label(CapabilityType.TERMINAL));
    }

    @Test
    void readUsesTheSnapshotOfTheRegistry() {
        CapabilityRegistry registry = new SnapshotRegistry(List.of(
                CapabilityStatus.available(CapabilityType.TERMINAL, "pty", "local shell", List.of())));

        assertEquals("Terminal: pty (local shell)",
                CapabilityStateLine.read(registry, CapabilityType.TERMINAL).headline());
    }

    @Test
    void failingSnapshotBecomesAnExplicitUnavailableState() {
        CapabilityRegistry registry = new SnapshotRegistry(null);

        CapabilityStateLine.Summary summary = CapabilityStateLine.read(registry, CapabilityType.READ_FILE);

        assertTrue(summary.headline().startsWith("Read File unavailable: status read failed: "), summary.headline());
        assertTrue(summary.headline().contains("registry is down"), summary.headline());
    }

    @Test
    void missingRegistryIsReportedAsNotConfigured() {
        assertEquals("Run unavailable: capability registry is not configured",
                CapabilityStateLine.read(null, CapabilityType.RUN).headline());
    }

    private record SnapshotRegistry(List<CapabilityStatus> rows) implements CapabilityRegistry {
        @Override public CapabilitySelection<RunCapability> run() { throw new UnsupportedOperationException(); }
        @Override public CapabilitySelection<TerminalCapability> terminal() { throw new UnsupportedOperationException(); }
        @Override public CapabilitySelection<ReadFileCapability> readFile() { throw new UnsupportedOperationException(); }
        @Override public CapabilitySelection<DiagnosticsCapability> diagnostics() { throw new UnsupportedOperationException(); }
        @Override public CapabilitySelection<CallHierarchyCapability> callHierarchy() { throw new UnsupportedOperationException(); }
        @Override public CapabilitySelection<CodeActionCapability> codeActions() { throw new UnsupportedOperationException(); }
        @Override public CapabilitySelection<EditorBridge> editorBridge() { throw new UnsupportedOperationException(); }
        @Override public CapabilitySelection<VersionControlCapability> versionControl() { throw new UnsupportedOperationException(); }
        @Override public List<CapabilityStatus> snapshot() {
            if (rows == null) {
                throw new IllegalStateException("registry is down");
            }
            return rows;
        }
    }
}
