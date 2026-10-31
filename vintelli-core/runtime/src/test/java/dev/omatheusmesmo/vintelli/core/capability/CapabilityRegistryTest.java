package dev.omatheusmesmo.vintelli.core.capability;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability.CallSite;
import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability.SourcePosition;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.ProviderDescriptor;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability.DirEntry;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability.RunConfiguration;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability.RunHandle;
import dev.omatheusmesmo.vintelli.api.capability.SkippedProvider;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability.TerminalOutcome;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability.TerminalRequest;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability.TerminalResult;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidget;
import dev.omatheusmesmo.vintelli.api.statusbar.CapabilityStatusWidgets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registry behavior with hand wired fake providers: ranking through the
 * configured order, fallback to the next ranked provider, re-selection when a
 * provider flips unhealthy and fail fast on duplicate identifiers. The
 * registry runs without CDI here; the list constructor stands in for
 * discovery.
 */
class CapabilityRegistryTest {

    private static final Map<CapabilityType, List<String>> NO_ORDER = Map.of();

    @Test
    void startsCleanWithNoProviderWiredAndReportsUnavailableStatesWithReasons() {
        CapabilityRegistry contract = registryWith(NO_ORDER);

        List<CapabilityStatus> snapshot = contract.snapshot();

        assertEquals(CapabilityType.values().length, snapshot.size());
        for (CapabilityStatus status : snapshot) {
            assertFalse(status.isAvailable(), status.capability().label());
            assertNull(status.providerIdentifier());
            assertEquals(ProviderHealth.State.UNAVAILABLE, status.health().state());
            assertEquals(List.of(), status.skipped());
            assertEquals("no provider is registered for '" + status.capability().label() + "'", status.health().reason());
        }

        CapabilitySelection<RunCapability> run = contract.run();
        assertFalse(run.isAvailable());
        assertEquals("no provider is registered for 'run'", unavailableReasonOf(run));
    }

    @Test
    void picksAMatchingProviderForEveryPortType() {
        TestingRunProvider runProvider = new TestingRunProvider();
        TestingTerminalProvider terminalProvider = new TestingTerminalProvider();
        TestingFileProvider fileProvider = new TestingFileProvider("local-reader");
        TestingDiagnosticsProvider diagnosticsProvider = new TestingDiagnosticsProvider();
        TestingCallHierarchyProvider hierarchyProvider = new TestingCallHierarchyProvider();
        TestingVersionControlProvider versionControlProvider = new TestingVersionControlProvider();
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry(
                List.of(runProvider), List.of(terminalProvider), List.of(fileProvider),
                List.of(diagnosticsProvider), List.of(hierarchyProvider), List.of(),
                List.of(versionControlProvider), orders(NO_ORDER));

        assertEquals("run-standalone", selectedIdentifierOf(registry.run()));
        assertEquals("terminal-standalone", selectedIdentifierOf(registry.terminal()));
        assertEquals("local-reader", selectedIdentifierOf(registry.readFile()));
        assertEquals("diagnostics-standalone", selectedIdentifierOf(registry.diagnostics()));
        assertEquals("hierarchy-standalone", selectedIdentifierOf(registry.callHierarchy()));
        assertEquals("vcs-standalone", selectedIdentifierOf(registry.versionControl()));
        assertEquals("vcs-standalone", statusOf(registry, CapabilityType.VERSION_CONTROL).providerIdentifier());

        CapabilitySelection<ReadFileCapability> read = registry.readFile();
        assertTrue(read instanceof CapabilitySelection.Selected<ReadFileCapability>);
        assertEquals("local-reader", selectedIdentifierOf(read));
    }

    @Test
    void reportsTheUnavailableStateOfTheVcsCapabilityThroughTheStatusRow() {
        DefaultCapabilityRegistry contract = registryWith(NO_ORDER);

        CapabilitySelection<VersionControlCapability> selection = contract.versionControl();

        assertFalse(selection.isAvailable());
        assertEquals("no provider is registered for 'vcs'", unavailableReasonOf(selection));
        CapabilityStatus row = statusOf(contract, CapabilityType.VERSION_CONTROL);
        assertFalse(row.isAvailable());
        assertNull(row.providerIdentifier());
        assertEquals("no provider is registered for 'vcs'", row.health().reason());
        CapabilityStatusWidgets barRow = statusBarOf(contract, CapabilityType.VERSION_CONTROL);
        assertFalse(barRow.status().isAvailable());
        assertEquals("no provider is registered for 'vcs'", barRow.status().health().reason());
        assertTrue(barRow.widgets().isEmpty());
    }

    @Test
    void picksTheProviderTheConfiguredOrderRanksFirst() {
        TestingFileProvider far = new TestingFileProvider("far-reader");
        TestingFileProvider near = new TestingFileProvider("near-reader");
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry(
                List.of(), List.of(), List.of(near, far), List.of(), List.of(), List.of(),
                List.of(), orders(Map.of(CapabilityType.READ_FILE, List.of("near-reader", "far-reader"))));

        CapabilitySelection<ReadFileCapability> selection = registry.readFile();

        assertTrue(selection instanceof CapabilitySelection.Selected<ReadFileCapability>);
        assertEquals("near-reader", selectedIdentifierOf(registry.readFile()));
        assertEquals("filesystem", selectedProvenanceOf(registry.readFile()));
        assertEquals("near-reader", statusOf(registry, CapabilityType.READ_FILE).providerIdentifier());
    }

    @Test
    void fallsBackAndReSelectsWhenTheRankedFirstProviderFlipsUnhealthy() {
        TestingFileProvider primary = new TestingFileProvider("primary");
        TestingFileProvider backup = new TestingFileProvider("backup");
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry(
                List.of(), List.of(), List.of(backup, primary), List.of(), List.of(), List.of(),
                List.of(), orders(Map.of(CapabilityType.READ_FILE, List.of("primary", "backup"))));

        assertTrue(registry.readFile().isAvailable());
        assertEquals("primary", selectedIdentifierOf(registry.readFile()));

        primary.markUnavailable("lsp process exited");

        CapabilitySelection<ReadFileCapability> selection = registry.readFile();
        assertTrue(selection instanceof CapabilitySelection.Selected<ReadFileCapability>);
        assertEquals("backup", selectedIdentifierOf(selection));

        CapabilityStatus row = statusOf(registry, CapabilityType.READ_FILE);
        assertEquals("backup", row.providerIdentifier());
        assertEquals("filesystem", row.provenance());
        assertEquals(1, row.skipped().size());
        SkippedProvider skipped = row.skipped().get(0);
        assertEquals("primary", skipped.identifier());
        assertEquals("lsp process exited", skipped.reason());
    }

    @Test
    void statusBarSnapshotUsesTheLiveSelectedProviderContributionAndKeepsUnavailableReasons() {
        TestingFileProvider primary = new TestingFileProvider("primary");
        TestingFileProvider backup = new TestingFileProvider("backup");
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry(
                List.of(), List.of(), List.of(primary, backup), List.of(), List.of(), List.of(), List.of(),
                orders(Map.of(CapabilityType.READ_FILE, List.of("primary", "backup"))));

        CapabilityStatusWidgets initial = statusBarOf(registry, CapabilityType.READ_FILE);
        assertEquals("primary", initial.status().providerIdentifier());
        assertEquals("Reader: primary", initial.widgets().getFirst().text());

        primary.markUnavailable("primary disconnected");
        CapabilityStatusWidgets fallback = statusBarOf(registry, CapabilityType.READ_FILE);
        assertEquals("backup", fallback.status().providerIdentifier());
        assertEquals("Reader: backup", fallback.widgets().getFirst().text());
        assertEquals("primary disconnected", fallback.status().skipped().getFirst().reason());

        backup.markUnavailable("backup disconnected");
        CapabilityStatusWidgets unavailable = statusBarOf(registry, CapabilityType.READ_FILE);
        assertFalse(unavailable.status().isAvailable());
        assertEquals("no healthy provider for 'read-file': primary (primary disconnected), backup (backup disconnected)",
                unavailable.status().health().reason());
        assertTrue(unavailable.widgets().isEmpty());
    }

    @Test
    void ordinaryRegistrySnapshotDoesNotRequestProviderStatusBarWidgets() {
        TestingFileProvider provider = new TestingFileProvider("reader");
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry(
                List.of(), List.of(), List.of(provider), List.of(), List.of(), List.of(), List.of(), orders(NO_ORDER));

        registry.snapshot();
        assertEquals(0, provider.widgetReads.get());

        registry.statusBarSnapshot();
        assertEquals(1, provider.widgetReads.get());
    }

    @Test
    void keepsTheExplicitReasonWhenEveryRegisteredProviderIsUnhealthy() {
        TestingFileProvider only = new TestingFileProvider("only-reader");
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry(
                List.of(), List.of(), List.of(only), List.of(), List.of(), List.of(), List.of(), orders(NO_ORDER));
        only.markUnavailable("declined the project root");

        CapabilitySelection<ReadFileCapability> selection = registry.readFile();

        assertFalse(selection.isAvailable());
        assertEquals("no healthy provider for 'read-file': only-reader (declined the project root)",
                unavailableReasonOf(selection));
    }

    @Test
    void failsFastWhenTwoProvidersShareAnIdentifier() {
        TestingFileProvider first = new TestingFileProvider("dup");
        TestingFileProvider second = new TestingFileProvider("dup");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new DefaultCapabilityRegistry(
                        List.of(), List.of(), List.of(first, second), List.of(), List.of(), List.of(), List.of(), orders(NO_ORDER)));

        assertTrue(failure.getMessage().contains("dup"));
        assertTrue(failure.getMessage().contains("read-file"));
    }

    @Test
    void failsFastWhenAProviderIdentifierIsBlank() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new DefaultCapabilityRegistry(
                        List.of(), List.of(), List.of(new BlankIdentifierProvider()), List.of(), List.of(), List.of(),
                        List.of(), orders(NO_ORDER)));

        assertTrue(failure.getMessage().contains("blank identifier"), failure.getMessage());
        assertTrue(failure.getMessage().contains("read-file"), failure.getMessage());
    }

    @Test
    void failsFastWhenAProviderIdentifierIsNull() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new DefaultCapabilityRegistry(
                        List.of(), List.of(), List.of(new NullIdentifierProvider()), List.of(), List.of(), List.of(),
                        List.of(), orders(NO_ORDER)));

        assertTrue(failure.getMessage().contains("identifier"), failure.getMessage());
        assertTrue(failure.getMessage().contains("read-file"), failure.getMessage());
    }

    @Test
    void failsFastWhenAProviderThrowsWhileReportingItsIdentifier() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new DefaultCapabilityRegistry(
                        List.of(), List.of(), List.of(new ExplodingIdentifierProvider()), List.of(), List.of(), List.of(),
                        List.of(), orders(NO_ORDER)));

        assertTrue(failure.getMessage().contains("identifier"), failure.getMessage());
        assertTrue(failure.getMessage().contains("read-file"), failure.getMessage());
    }

    @Test
    void degradesAProviderThatThrowsWhileReportingHealth() {
        ThrowingFileProvider broken = new ThrowingFileProvider();
        TestingFileProvider healthy = new TestingFileProvider("healthy-reader");
        DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry(
                List.of(), List.of(), List.of(broken, healthy), List.of(), List.of(), List.of(), List.of(), orders(NO_ORDER));

        CapabilityStatus row = statusOf(registry, CapabilityType.READ_FILE);

        assertEquals("healthy-reader", row.providerIdentifier());
        assertEquals(1, row.skipped().size());
        assertEquals("broken-reader", row.skipped().get(0).identifier());
        assertTrue(row.skipped().get(0).reason().contains("health check failed"));
    }

    private static <T extends ProviderDescriptor> String selectedIdentifierOf(CapabilitySelection<T> selection) {
        return ((CapabilitySelection.Selected<T>) selection).provider().identifier();
    }

    private static <T extends ProviderDescriptor> String selectedProvenanceOf(CapabilitySelection<T> selection) {
        return ((CapabilitySelection.Selected<T>) selection).provider().provenance();
    }

    private static <T extends ProviderDescriptor> String unavailableReasonOf(CapabilitySelection<T> selection) {
        return ((CapabilitySelection.Unavailable<T>) selection).reason();
    }

    private static CapabilityStatus statusOf(DefaultCapabilityRegistry registry, CapabilityType capability) {
        List<CapabilityStatus> rows = registry.snapshot();
        for (CapabilityStatus row : rows) {
            if (row.capability() == capability) {
                return row;
            }
        }
        throw new AssertionError("status row of " + capability + " is missing");
    }

    private static CapabilityStatusWidgets statusBarOf(DefaultCapabilityRegistry registry,
            CapabilityType capability) {
        return registry.statusBarSnapshot().stream()
                .filter(row -> row.status().capability() == capability)
                .findFirst().orElseThrow();
    }

    private static DefaultCapabilityRegistry registryWith(Map<CapabilityType, List<String>> orders) {
        return new DefaultCapabilityRegistry(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), new CapabilityOrders(orders));
    }

    private static CapabilityOrders orders(Map<CapabilityType, List<String>> orders) {
        Map<CapabilityType, List<String>> copy = new EnumMap<>(CapabilityType.class);
        copy.putAll(orders);
        return new CapabilityOrders(copy);
    }

    private static final class TestingFileProvider implements ReadFileCapability {

        private final String identifier;
        private volatile ProviderHealth health = ProviderHealth.available();
        private final AtomicInteger widgetReads = new AtomicInteger();

        private TestingFileProvider(String identifier) {
            this.identifier = identifier;
        }

        private void markUnavailable(String reason) {
            health = ProviderHealth.unavailable(reason);
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
            return "filesystem";
        }

        @Override
        public List<StatusBarWidget> statusBarWidgets(CapabilityType capability) {
            widgetReads.incrementAndGet();
            if (capability != CapabilityType.READ_FILE) {
                return List.of();
            }
            return List.of(new StatusBarWidget("read-provider", 60, "Reader: " + identifier,
                    "R:" + identifier));
        }

        @Override
        public CapabilityResult<String> readText(Path file) {
            return CapabilityResult.ok("text of " + file);
        }

        @Override
        public CapabilityResult<List<DirEntry>> list(Path directory) {
            return CapabilityResult.ok(List.of(new DirEntry("project", true)));
        }
    }

    private static final class ThrowingFileProvider implements ReadFileCapability {

        @Override
        public String identifier() {
            return "broken-reader";
        }

        @Override
        public ProviderHealth health() {
            throw new IllegalStateException("health probe exploded");
        }

        @Override
        public String provenance() {
            return "filesystem";
        }

        @Override
        public CapabilityResult<String> readText(Path file) {
            return CapabilityResult.failed(new CapabilityFailure("cannot read", file.toString()));
        }

        @Override
        public CapabilityResult<List<DirEntry>> list(Path directory) {
            return CapabilityResult.failed(new CapabilityFailure("cannot list", directory.toString()));
        }
    }

    private static final class BlankIdentifierProvider implements ReadFileCapability {

        @Override
        public String identifier() {
            return "   ";
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.available();
        }

        @Override
        public String provenance() {
            return "filesystem";
        }

        @Override
        public CapabilityResult<String> readText(Path file) {
            return CapabilityResult.ok("text");
        }

        @Override
        public CapabilityResult<List<DirEntry>> list(Path directory) {
            return CapabilityResult.ok(List.of());
        }
    }

    private static final class NullIdentifierProvider implements ReadFileCapability {

        @Override
        public String identifier() {
            return null;
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.available();
        }

        @Override
        public String provenance() {
            return "filesystem";
        }

        @Override
        public CapabilityResult<String> readText(Path file) {
            return CapabilityResult.ok("text");
        }

        @Override
        public CapabilityResult<List<DirEntry>> list(Path directory) {
            return CapabilityResult.ok(List.of());
        }
    }

    private static final class ExplodingIdentifierProvider implements ReadFileCapability {

        @Override
        public String identifier() {
            throw new IllegalArgumentException("identifier service exploded");
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.available();
        }

        @Override
        public String provenance() {
            return "filesystem";
        }

        @Override
        public CapabilityResult<String> readText(Path file) {
            return CapabilityResult.ok("text");
        }

        @Override
        public CapabilityResult<List<DirEntry>> list(Path directory) {
            return CapabilityResult.ok(List.of());
        }
    }

    private static final class TestingRunProvider implements RunCapability {

        @Override
        public CapabilityResult<List<RunConfiguration>> listConfigurations() {
            return CapabilityResult.ok(List.of(new RunConfiguration("compile", "stub")));
        }

        @Override
        public CapabilityResult<RunHandle> start(RunRequest request, Consumer<String> outputLines) {
            return CapabilityResult.failed(new CapabilityFailure("not started", "stub provider"));
        }

        @Override
        public String identifier() {
            return "run-standalone";
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.available();
        }

        @Override
        public String provenance() {
            return "stub runner";
        }
    }

    private static final class TestingTerminalProvider implements TerminalCapability {

        @Override
        public CapabilityResult<TerminalResult> execute(TerminalRequest request) {
            return CapabilityResult.ok(new TerminalResult(TerminalOutcome.EXECUTED, 0, "stub output"));
        }

        @Override
        public String identifier() {
            return "terminal-standalone";
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.available();
        }

        @Override
        public String provenance() {
            return "stub shell";
        }
    }

    private static final class TestingDiagnosticsProvider implements DiagnosticsCapability {

        @Override
        public CapabilityResult<List<Diagnostic>> diagnostics(Path file) {
            return CapabilityResult.ok(List.of());
        }

        @Override
        public String identifier() {
            return "diagnostics-standalone";
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.available();
        }

        @Override
        public String provenance() {
            return "stub diagnostics";
        }
    }

    private static final class TestingCallHierarchyProvider implements CallHierarchyCapability {

        @Override
        public CapabilityResult<List<CallSite>> callers(SourcePosition position) {
            return CapabilityResult.ok(List.of());
        }

        @Override
        public CapabilityResult<List<CallSite>> callees(SourcePosition position) {
            return CapabilityResult.ok(List.of());
        }

        @Override
        public String identifier() {
            return "hierarchy-standalone";
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.available();
        }

        @Override
        public String provenance() {
            return "stub hierarchy";
        }
    }

    private static final class TestingVersionControlProvider implements VersionControlCapability {

        @Override
        public CapabilityResult<VersionControlCapability.Status> status() {
            return CapabilityResult.ok(new VersionControlCapability.Status("main", null, null, List.of()));
        }

        @Override
        public CapabilityResult<String> diff(Path path) {
            return CapabilityResult.ok("");
        }

        @Override
        public CapabilityResult<String> diffWorkingTree() {
            return CapabilityResult.ok("");
        }

        @Override
        public String identifier() {
            return "vcs-standalone";
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.available();
        }

        @Override
        public String provenance() {
            return "stub vcs";
        }
    }
}
