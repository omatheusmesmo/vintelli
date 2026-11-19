package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import jakarta.enterprise.inject.Instance;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * Builds a {@link BuiltinEditor} whose debug capability is a test double, so exception breakpoint
 * choices reach a provider.
 */
public final class DebugProviderFixture {

    private DebugProviderFixture() {
    }

    /** An editor on the project; a null debug provider leaves the editor without capabilities. */
    public static BuiltinEditor editor(Path project, Path session, DebugCapability debug) {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        if (debug == null) {
            return new BuiltinEditor(project, session, themes::editorPalette);
        }
        Instance<CapabilityRegistry> capabilities = instanceOf(new Registry(debug));
        return new BuiltinEditor(project, session, themes::editorPalette, capabilities);
    }

    @SuppressWarnings("unchecked")
    private static Instance<CapabilityRegistry> instanceOf(CapabilityRegistry registry) {
        return (Instance<CapabilityRegistry>) Proxy.newProxyInstance(Instance.class.getClassLoader(),
                new Class<?>[] {Instance.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "isResolvable" -> true;
                    case "get" -> registry;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A debug provider that records the exception choices it receives and applies or refuses them. */
    public static final class RecordingDebug implements DebugCapability {

        private final BlockingQueue<ExceptionBreakpoints> requests = new LinkedBlockingQueue<>();
        private final boolean failing;

        public RecordingDebug(boolean failing) {
            this.failing = failing;
        }

        public BlockingQueue<ExceptionBreakpoints> requests() {
            return requests;
        }

        @Override public CapabilityResult<ExceptionBreakpoints> setExceptionBreakpoints(ExceptionBreakpoints value) {
            requests.add(value);
            if (failing) {
                return CapabilityResult.failed(new CapabilityFailure("adapter refused", ""));
            }
            return CapabilityResult.ok(new ExceptionBreakpoints(value.uncaught(), false));
        }

        @Override public CapabilityResult<DebugSession> launch(DebugRequest request,
                Consumer<String> outputLines) {
            return CapabilityResult.failed(new CapabilityFailure("unused", ""));
        }

        @Override public CapabilityResult<DebugSession> attach(AttachRequest request,
                Consumer<String> outputLines) {
            return CapabilityResult.failed(new CapabilityFailure("unused", ""));
        }

        @Override public List<DebugSessionSnapshot> snapshot() { return List.of(); }
        @Override public String identifier() { return "recording"; }
        @Override public String provenance() { return "recording debug provider"; }
        @Override public ProviderHealth health() { return ProviderHealth.available(); }
    }

    private record Registry(DebugCapability provider) implements CapabilityRegistry {
        @Override public CapabilitySelection<DebugCapability> debug() {
            return CapabilitySelection.selected(CapabilityType.DEBUG, provider);
        }
        @Override public CapabilitySelection<RunCapability> run() { return none(CapabilityType.RUN); }
        @Override public CapabilitySelection<TerminalCapability> terminal() { return none(CapabilityType.TERMINAL); }
        @Override public CapabilitySelection<ReadFileCapability> readFile() { return none(CapabilityType.READ_FILE); }
        @Override public CapabilitySelection<DiagnosticsCapability> diagnostics() { return none(CapabilityType.DIAGNOSTICS); }
        @Override public CapabilitySelection<CallHierarchyCapability> callHierarchy() { return none(CapabilityType.CALL_HIERARCHY); }
        @Override public CapabilitySelection<CodeActionCapability> codeActions() { return none(CapabilityType.CODE_ACTION); }
        @Override public CapabilitySelection<EditorBridge> editorBridge() { return none(CapabilityType.EDITOR_BRIDGE); }
        @Override public CapabilitySelection<VersionControlCapability> versionControl() { return none(CapabilityType.VERSION_CONTROL); }
        @Override public List<CapabilityStatus> snapshot() { return List.of(); }

        private static <T> CapabilitySelection<T> none(CapabilityType type) {
            return CapabilitySelection.unavailable(type, "not available in test");
        }
    }
}
