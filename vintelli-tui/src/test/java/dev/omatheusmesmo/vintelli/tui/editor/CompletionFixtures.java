package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Suggestion;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * Completion providers and registries for the completion tests: the provider answers with what the test hands
 * it and records every request, so the popup states are driven by data.
 */
public final class CompletionFixtures {

    private CompletionFixtures() {
    }

    /**
     * A completion provider that records the content of every request and answers from a function of the call
     * index and the content.
     */
    public static final class Provider implements CompletionCapability {

        private final BiFunction<Integer, String, CapabilityResult<List<Suggestion>>> answer;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> contents = new CopyOnWriteArrayList<>();

        public Provider(BiFunction<Integer, String, CapabilityResult<List<Suggestion>>> answer) {
            this.answer = answer;
        }

        @Override
        public CapabilityResult<List<Suggestion>> complete(Path file, String content, int line, int column) {
            contents.add(content);
            return answer.apply(calls.getAndIncrement(), content);
        }

        public List<String> contents() {
            return new ArrayList<>(contents);
        }

        @Override
        public String identifier() {
            return "fixture-completion";
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

    public static CapabilityRegistry registryWith(CapabilitySelection<CompletionCapability> completion) {
        return new Registry(completion);
    }

    private static final class Registry implements CapabilityRegistry {

        private final CapabilitySelection<CompletionCapability> completion;

        Registry(CapabilitySelection<CompletionCapability> completion) {
            this.completion = completion;
        }

        @Override
        public CapabilitySelection<CompletionCapability> completion() {
            return completion;
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
