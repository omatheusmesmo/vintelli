package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.HoverCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * Hover providers and a registry for the quick documentation tests: the provider answers with what the test hands
 * it, optionally after a gate opens, and records every request it receives.
 */
public final class QuickDocumentationFixtures {

    private QuickDocumentationFixtures() {
    }

    /**
     * A hover provider that answers with a fixed result. When a gate is given the answer waits until the test opens it,
     * which keeps the request in flight for as long as the test needs.
     */
    public static final class Provider implements HoverCapability {

        private final CapabilityResult<Documentation> answer;
        private final CountDownLatch gate;
        private final List<String> contents = new CopyOnWriteArrayList<>();
        private final List<String> positions = new CopyOnWriteArrayList<>();

        public Provider(CapabilityResult<Documentation> answer) {
            this(answer, null);
        }

        public Provider(CapabilityResult<Documentation> answer, CountDownLatch gate) {
            this.answer = answer;
            this.gate = gate;
        }

        @Override
        public CapabilityResult<Documentation> hover(Path file, String content, int line, int column) {
            contents.add(content);
            positions.add(line + ":" + column);
            awaitGate();
            return answer;
        }

        public List<String> contents() {
            return new ArrayList<>(contents);
        }

        public List<String> positions() {
            return new ArrayList<>(positions);
        }

        @Override
        public String identifier() {
            return "fixture-hover";
        }

        @Override
        public String provenance() {
            return "fixture language server";
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.available();
        }

        private void awaitGate() {
            if (gate == null) {
                return;
            }
            try {
                gate.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("the fixture gate was interrupted", interrupted);
            }
        }
    }

    public static CapabilityRegistry registryWith(CapabilitySelection<HoverCapability> hover) {
        return new Registry(hover);
    }

    private static final class Registry implements CapabilityRegistry {

        private final CapabilitySelection<HoverCapability> hover;

        Registry(CapabilitySelection<HoverCapability> hover) {
            this.hover = hover;
        }

        @Override
        public CapabilitySelection<HoverCapability> hover() {
            return hover;
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
