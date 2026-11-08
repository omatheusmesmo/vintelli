package dev.omatheusmesmo.vintelli.tui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.statusbar.CapabilityStatusWidgets;
import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidget;
import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidgetContributor;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;

/**
 * Builds the widgets of the status bar from the shell message, the capability
 * snapshot, the editor caret and the contributors, and the segments of the
 * editor breadcrumb. It holds no TamboUI type: {@link StatusBarElement} draws
 * the widgets, and the shell hands the segments to the editor navigation bar.
 */
final class StatusBar {

    /** One breadcrumb segment: its label, the path it opens and its line, or -1 for a path. */
    record Breadcrumb(String label, Path path, int line) {
    }

    private static final String IDEA_SUFFIX = " | IDEA MCP: disconnected";
    private static final int COMPACT_MESSAGE_WIDTH = 8;
    private static final int COMPACT_PROVIDER_WIDTH = 7;
    private static final int COMPACT_REASON_WIDTH = 12;
    private static final int CAPABILITY_ORDER = 10;

    private final Supplier<String> message;
    private final BooleanSupplier inputBlocking;
    private final CapabilityRegistry registry;
    private final BuiltinEditor editor;
    private final List<StatusBarWidgetContributor> contributors;

    StatusBar(Supplier<String> message, BooleanSupplier inputBlocking, CapabilityRegistry registry,
            BuiltinEditor editor, List<? extends StatusBarWidgetContributor> contributors) {
        this.message = message;
        this.inputBlocking = inputBlocking;
        this.registry = registry;
        this.editor = editor;
        this.contributors = List.copyOf(contributors);
    }

    /**
     * The widgets in the order the layout sorts them. While the project or a
     * version control prompt takes input, only the shell message stays.
     */
    List<StatusBarWidget> widgets() {
        List<StatusBarWidget> widgets = new ArrayList<>();
        String shellMessage = message.get().replace(IDEA_SUFFIX, "");
        widgets.add(new StatusBarWidget("shell", 0, shellMessage, compactShell(shellMessage)));
        if (inputBlocking.getAsBoolean()) {
            return List.copyOf(widgets);
        }
        if (registry != null) {
            int order = CAPABILITY_ORDER;
            for (CapabilityStatusWidgets capabilityStatusWidgets : registry.statusBarSnapshot()) {
                widgets.add(capabilityWidget(capabilityStatusWidgets.status(), order++));
                widgets.addAll(capabilityStatusWidgets.widgets());
            }
        }
        if (editor != null) {
            editor.statusBarInfo().ifPresent(info -> {
                String cursor = "L" + info.line() + ":C" + info.column();
                widgets.add(new StatusBarWidget("editor-caret", 8, "Caret " + cursor, cursor));
                String details = info.encoding() + " · " + info.lineSeparator() + " · " + info.fileType();
                widgets.add(new StatusBarWidget("editor-format", 101, details,
                        info.encoding().replace("-", "") + " " + info.lineSeparator() + " " + info.fileType()));
            });
        }
        for (StatusBarWidgetContributor contributor : contributors) {
            widgets.addAll(contributor.statusBarWidgets());
        }
        return List.copyOf(widgets);
    }

    static StatusBarWidget capabilityWidget(CapabilityStatus status, int order) {
        String label = CapabilityStateLine.label(status.capability());
        String shortLabel = shortLabel(status.capability());
        String id = "capability-" + status.capability().label();
        if (status.isAvailable()) {
            return new StatusBarWidget(id, order,
                    label + ": " + status.providerIdentifier() + " (" + status.provenance() + ")",
                    shortLabel + ":" + StatusBarLayout.truncate(status.providerIdentifier(), COMPACT_PROVIDER_WIDTH));
        }
        return new StatusBarWidget(id, order, label + ": unavailable: " + status.health().reason(),
                shortLabel + ":!" + compactReason(status.health().reason()));
    }

    static String compactShell(String message) {
        if (message.startsWith("Tool window: ")) {
            return message;
        }
        if (message.startsWith("Vintelli ready") || message.equals("Project files loaded")) {
            return "Ready";
        }
        if (message.startsWith("Loading project files")) {
            return "Loading";
        }
        if (message.startsWith("No project files")) {
            return "No files";
        }
        if (message.startsWith("Project files unavailable")) {
            return "Project!";
        }
        return StatusBarLayout.truncate(message, COMPACT_MESSAGE_WIDTH);
    }

    /** The segments from the project root down to {@code relative}, the root segment first. */
    static List<Breadcrumb> pathBreadcrumbs(Path root, Path relative) {
        List<Breadcrumb> paths = new ArrayList<>();
        String rootLabel = root.getFileName() == null ? root.toString() : root.getFileName().toString();
        paths.add(new Breadcrumb(rootLabel, root, -1));
        Path current = root;
        for (Path part : relative) {
            current = current.resolve(part);
            paths.add(new Breadcrumb(part.toString(), current, -1));
        }
        return List.copyOf(paths);
    }

    /** The type and callable symbols that contain the caret, each opening its line in {@code file}. */
    static List<Breadcrumb> symbolBreadcrumbs(Path file, List<NavigationCapability.DocumentSymbol> containing) {
        return containing.stream()
                .filter(symbol -> isTypeOrCallable(symbol.kind()))
                .map(symbol -> new Breadcrumb(symbol.name(), file, symbol.selectionStartLine()))
                .toList();
    }

    private static boolean isTypeOrCallable(int kind) {
        return kind == 5 || kind == 6 || kind == 9 || kind == 11 || kind == 12 || kind == 23;
    }

    private static String shortLabel(CapabilityType capability) {
        return switch (capability) {
            case RUN -> "R";
            case TERMINAL -> "T";
            case READ_FILE -> "RF";
            case DIAGNOSTICS -> "P";
            case CALL_HIERARCHY -> "CH";
            case CODE_ACTION -> "CA";
            case EDITOR_BRIDGE -> "E";
            case VERSION_CONTROL -> "VCS";
        };
    }

    private static String compactReason(String reason) {
        return StatusBarLayout.truncate(reason, COMPACT_REASON_WIDTH);
    }
}
