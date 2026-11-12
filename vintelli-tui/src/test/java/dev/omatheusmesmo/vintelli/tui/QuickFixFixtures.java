package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CodeAction;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CommandRef;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.SourceRange;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.DocumentEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.TextEdit;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Fake code action provider and registry for the quick fix tests. The fake answers with what the
 * test hands it, so the popup states (loading, actions, empty, unavailable, failed) are driven by data.
 */
final class QuickFixFixtures {

    static final String LIGHTBULB = "☼";

    private QuickFixFixtures() {
    }

    static CodeAction replaceCaretLine(Path file) {
        WorkspaceEdit edit = new WorkspaceEdit(
                List.of(new DocumentEdit(file, List.of(new TextEdit(2, 0, 2, 6, "fixed")))), List.of());
        return new CodeAction("Replace line with fixed text", "quickfix", true, edit, null);
    }

    static CodeAction suppressWithCommand() {
        return new CodeAction("Suppress with a comment", "quickfix", false, null,
                new CommandRef("fixture.suppress", "Suppress"));
    }

    static CapabilityRegistry registryWith(CapabilitySelection<CodeActionCapability> codeActions) {
        return new Registry(codeActions);
    }

    static CapabilitySelection<CodeActionCapability> answering(Function<Path, CapabilityResult<List<CodeAction>>> answer) {
        return CapabilitySelection.selected(CapabilityType.CODE_ACTION, new Actions(answer));
    }

    static final class Actions implements CodeActionCapability {

        private final Function<Path, CapabilityResult<List<CodeAction>>> answer;

        Actions(Function<Path, CapabilityResult<List<CodeAction>>> answer) {
            this.answer = answer;
        }

        @Override
        public String identifier() {
            return "fixture-ls";
        }

        @Override
        public String provenance() {
            return "fixture language server";
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.available();
        }

        @Override
        public CapabilityResult<List<CodeAction>> codeActions(Path file, SourceRange range,
                List<Diagnostic> diagnostics) {
            return answer.apply(file);
        }
    }

    static CapabilityResult<List<CodeAction>> failure(String reason, String detail) {
        return CapabilityResult.failed(new CapabilityFailure(reason, detail));
    }

    private static final class Registry implements CapabilityRegistry {

        private final CapabilitySelection<CodeActionCapability> codeActions;

        Registry(CapabilitySelection<CodeActionCapability> codeActions) {
            this.codeActions = codeActions;
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
            return codeActions;
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
