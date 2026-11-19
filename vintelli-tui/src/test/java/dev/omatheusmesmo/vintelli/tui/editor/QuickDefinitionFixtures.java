package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A navigation provider that answers every definition request with the locations the test hands it, and a registry
 * whose read file capability reads the local filesystem, for the quick definition tests.
 */
public final class QuickDefinitionFixtures {

    private QuickDefinitionFixtures() {
    }

    /**
     * A navigation provider with a fixed definition answer and a fixed outline for every file.
     */
    public static final class Navigation implements NavigationCapability {

        private final CapabilityResult<List<Location>> definition;
        private final List<DocumentSymbol> outline;

        public Navigation(CapabilityResult<List<Location>> definition, List<DocumentSymbol> outline) {
            this.definition = definition;
            this.outline = outline;
        }

        @Override
        public CapabilityResult<List<Location>> definition(SourcePosition position) {
            return definition;
        }

        @Override
        public CapabilityResult<List<Location>> references(SourcePosition position, boolean includeDeclaration) {
            return CapabilityResult.failed(new CapabilityFailure("unavailable", "not under test"));
        }

        @Override
        public CapabilityResult<List<DocumentSymbol>> documentSymbols(Path file) {
            return CapabilityResult.ok(outline);
        }

        @Override
        public CapabilityResult<List<WorkspaceSymbol>> workspaceSymbols(String query) {
            return CapabilityResult.failed(new CapabilityFailure("unavailable", "not under test"));
        }

        @Override
        public String identifier() {
            return "fixture-navigation";
        }

        @Override
        public String provenance() {
            return "fixture language server";
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.available();
        }
    }

    /**
     * A method outline entry that spans the given lines, with its selection on the first one.
     */
    public static NavigationCapability.DocumentSymbol method(String name, int startLine, int endLine) {
        return new NavigationCapability.DocumentSymbol(name, 6, startLine, 0, endLine, 5, startLine, 9, startLine,
                9 + name.length(), List.of());
    }

    public static CapabilityRegistry registryReadingFiles() {
        return new Registry();
    }

    private static final class Registry implements CapabilityRegistry {

        @Override
        public CapabilitySelection<ReadFileCapability> readFile() {
            return CapabilitySelection.selected(CapabilityType.READ_FILE, new LocalFiles());
        }

        @Override
        public CapabilitySelection<RunCapability> run() {
            return unavailable(CapabilityType.RUN);
        }

        @Override
        public CapabilitySelection<TerminalCapability> terminal() {
            return unavailable(CapabilityType.TERMINAL);
        }

        @Override
        public CapabilitySelection<DiagnosticsCapability> diagnostics() {
            return unavailable(CapabilityType.DIAGNOSTICS);
        }

        @Override
        public CapabilitySelection<CallHierarchyCapability> callHierarchy() {
            return unavailable(CapabilityType.CALL_HIERARCHY);
        }

        @Override
        public CapabilitySelection<CodeActionCapability> codeActions() {
            return unavailable(CapabilityType.CODE_ACTION);
        }

        @Override
        public CapabilitySelection<EditorBridge> editorBridge() {
            return unavailable(CapabilityType.EDITOR_BRIDGE);
        }

        @Override
        public CapabilitySelection<VersionControlCapability> versionControl() {
            return unavailable(CapabilityType.VERSION_CONTROL);
        }

        @Override
        public List<CapabilityStatus> snapshot() {
            return List.of();
        }

        private static <T> CapabilitySelection<T> unavailable(CapabilityType capability) {
            return CapabilitySelection.unavailable(capability, "not available in test");
        }
    }

    private static final class LocalFiles implements ReadFileCapability {

        @Override
        public CapabilityResult<String> readText(Path file) {
            try {
                return CapabilityResult.ok(Files.readString(file));
            } catch (IOException failure) {
                return CapabilityResult.failed(new CapabilityFailure("The file cannot be read", file.toString()));
            }
        }

        @Override
        public CapabilityResult<List<DirEntry>> list(Path directory) {
            return CapabilityResult.failed(new CapabilityFailure("unavailable", "not under test"));
        }

        @Override
        public String identifier() {
            return "fixture-files";
        }

        @Override
        public String provenance() {
            return "local filesystem";
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.available();
        }
    }
}
