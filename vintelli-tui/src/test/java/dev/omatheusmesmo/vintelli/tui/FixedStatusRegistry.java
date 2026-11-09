package dev.omatheusmesmo.vintelli.tui;

import java.util.List;

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
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.api.statusbar.CapabilityStatusWidgets;

/** A registry whose status rows are fixed by the test, with every capability otherwise unconfigured. */
final class FixedStatusRegistry implements CapabilityRegistry {

    private final List<CapabilityStatusWidgets> rows;

    FixedStatusRegistry(List<CapabilityStatusWidgets> rows) {
        this.rows = List.copyOf(rows);
    }

    static FixedStatusRegistry withStatuses(List<CapabilityStatus> statuses) {
        return new FixedStatusRegistry(statuses.stream()
                .map(status -> new CapabilityStatusWidgets(status, List.of())).toList());
    }

    @Override
    public CapabilitySelection<RunCapability> run() {
        return CapabilitySelection.unavailable(CapabilityType.RUN, "not configured");
    }

    @Override
    public CapabilitySelection<TerminalCapability> terminal() {
        return CapabilitySelection.unavailable(CapabilityType.TERMINAL, "not configured");
    }

    @Override
    public CapabilitySelection<ReadFileCapability> readFile() {
        return CapabilitySelection.unavailable(CapabilityType.READ_FILE, "not configured");
    }

    @Override
    public CapabilitySelection<DiagnosticsCapability> diagnostics() {
        return CapabilitySelection.unavailable(CapabilityType.DIAGNOSTICS, "not configured");
    }

    @Override
    public CapabilitySelection<CallHierarchyCapability> callHierarchy() {
        return CapabilitySelection.unavailable(CapabilityType.CALL_HIERARCHY, "not configured");
    }

    @Override
    public CapabilitySelection<CodeActionCapability> codeActions() {
        return CapabilitySelection.unavailable(CapabilityType.CODE_ACTION, "not configured");
    }

    @Override
    public CapabilitySelection<EditorBridge> editorBridge() {
        return CapabilitySelection.unavailable(CapabilityType.EDITOR_BRIDGE, "not configured");
    }

    @Override
    public CapabilitySelection<VersionControlCapability> versionControl() {
        return CapabilitySelection.unavailable(CapabilityType.VERSION_CONTROL, "not configured");
    }

    @Override
    public List<CapabilityStatus> snapshot() {
        return rows.stream().map(CapabilityStatusWidgets::status).toList();
    }

    @Override
    public List<CapabilityStatusWidgets> statusBarSnapshot() {
        return rows;
    }
}
