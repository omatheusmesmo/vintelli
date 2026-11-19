package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import dev.omatheusmesmo.vintelli.api.capability.RenameCapability;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;

import java.nio.file.Path;
import java.util.List;

/**
 * Rename providers and a registry for the rename tests: the provider answers what the test hands it, and the registry
 * serves only the rename capability, reporting every other capability as not available in the test.
 */
public final class RenameFixtures {

    private RenameFixtures() {
    }

    public static CapabilityRegistry registryWith(CapabilitySelection<RenameCapability> rename) {
        return new Registry(rename);
    }

    public static RenameCapability answering(CapabilityResult<String> suggestion,
            CapabilityResult<WorkspaceEdit> edit) {
        return new Answering(suggestion, edit);
    }

    private record Answering(CapabilityResult<String> suggestion, CapabilityResult<WorkspaceEdit> edit)
            implements RenameCapability {

        @Override
        public CapabilityResult<String> prepareRename(Path file, String content, int line, int column) {
            return suggestion;
        }

        @Override
        public CapabilityResult<WorkspaceEdit> rename(Path file, String content, int line, int column, String newName) {
            return edit;
        }

        @Override
        public String identifier() {
            return "fixture-ls";
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.available();
        }

        @Override
        public String provenance() {
            return "fixture language server";
        }
    }

    private record Registry(CapabilitySelection<RenameCapability> rename) implements CapabilityRegistry {

        @Override
        public CapabilitySelection<RenameCapability> rename() {
            return rename;
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
        public CapabilitySelection<ReadFileCapability> readFile() {
            return unavailable(CapabilityType.READ_FILE);
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
}
