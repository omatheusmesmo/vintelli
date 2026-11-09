package dev.omatheusmesmo.vintelli.tui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.statusbar.CapabilityStatusWidgets;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidget;
import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidgetContributor;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;

/**
 * Builds the segments of the status bar: the breadcrumb on the left, and on the
 * right the shell message, the degraded-provider indicator, the caret, the line
 * separator, the encoding, the indent and the read-only lock. The provider
 * states go to a popup on the indicator, see {@link #providerLines()}. It holds
 * no TamboUI type: {@link StatusBarElement} draws the segments, and the shell
 * hands the breadcrumb to the editor navigation.
 */
final class StatusBar {

    /** One breadcrumb segment: its label, the path it opens and its line, or -1 for a path. */
    record Breadcrumb(String label, Path path, int line) {
    }

    /** The breadcrumb the shell navigates, the selected segment and whether the keyboard has it. */
    record Navigation(List<Breadcrumb> segments, int selected, boolean focused) {

        static final Navigation NONE = new Navigation(List.of(), 0, false);

        Navigation {
            segments = List.copyOf(segments);
        }
    }

    /**
     * The standard Unicode marks follow the Unicode probe, and the private-use
     * lock glyph follows the icon face. Each has an ASCII form.
     */
    record Glyphs(boolean unicode, IconMode icons) {

        String divider() {
            return unicode ? " › " : " > ";
        }

        String warning() {
            return unicode ? "⚠" : "!";
        }

        String readOnly() {
            return icons == IconMode.NERD ? "" : "Read-only";
        }
    }

    static final String BREADCRUMB_PREFIX = "crumb-";
    static final String PROVIDERS_ID = "providers";
    static final String GAP = "  ";

    private static final String IDEA_SUFFIX = " | IDEA MCP: disconnected";
    private static final int COMPACT_MESSAGE_WIDTH = 8;
    private static final int PRIORITY_CARET = 100;
    private static final int PRIORITY_FILE = 90;
    private static final int PRIORITY_PROVIDERS = 85;
    private static final int PRIORITY_MESSAGE = 80;
    private static final int PRIORITY_SYMBOL = 60;
    private static final int PRIORITY_LOCK = 40;
    private static final int PRIORITY_ENCODING = 45;
    private static final int PRIORITY_INDENT = 30;
    private static final int PRIORITY_ANCESTOR = 10;

    private final Supplier<String> message;
    private final BooleanSupplier inputBlocking;
    private final CapabilityRegistry registry;
    private final BuiltinEditor editor;
    private final List<StatusBarWidgetContributor> contributors;
    private final Glyphs glyphs;
    private final Supplier<Navigation> navigation;

    StatusBar(Supplier<String> message, BooleanSupplier inputBlocking, CapabilityRegistry registry,
            BuiltinEditor editor, List<? extends StatusBarWidgetContributor> contributors, Glyphs glyphs,
            Supplier<Navigation> navigation) {
        this.message = message;
        this.inputBlocking = inputBlocking;
        this.registry = registry;
        this.editor = editor;
        this.contributors = List.copyOf(contributors);
        this.glyphs = glyphs;
        this.navigation = navigation;
    }

    /**
     * The breadcrumb segments, the leftmost first. The file is the most important
     * path segment, the ancestors go from the root up, and the symbols go last.
     * While a prompt takes input there is none.
     */
    List<StatusBarLayout.Segment> left() {
        if (inputBlocking.getAsBoolean()) {
            return List.of();
        }
        List<Breadcrumb> crumbs = navigation().segments();
        int fileIndex = -1;
        for (int i = 0; i < crumbs.size(); i++) {
            if (crumbs.get(i).line() < 0) {
                fileIndex = i;
            }
        }
        List<StatusBarLayout.Segment> segments = new ArrayList<>();
        for (int i = 0; i < crumbs.size(); i++) {
            Breadcrumb crumb = crumbs.get(i);
            int priority = crumb.line() >= 0 ? PRIORITY_SYMBOL : i == fileIndex ? PRIORITY_FILE : PRIORITY_ANCESTOR + i;
            segments.add(StatusBarLayout.Segment.of(BREADCRUMB_PREFIX + i, priority, crumb.label()));
        }
        return List.copyOf(segments);
    }

    /**
     * The right group in display order. While the project or a version control
     * prompt takes input, only the shell message stays.
     */
    List<StatusBarLayout.Segment> right() {
        List<StatusBarLayout.Segment> segments = new ArrayList<>();
        String shellMessage = message.get().replace(IDEA_SUFFIX, "");
        if (degradedCount() > 0 && !inputBlocking.getAsBoolean()) {
            segments.add(degradedIndicator());
        }
        segments.add(new StatusBarLayout.Segment(StatusBarLayout.MESSAGE_ID, PRIORITY_MESSAGE, shellMessage,
                compactShell(shellMessage)));
        if (inputBlocking.getAsBoolean() || editor == null) {
            return List.copyOf(segments);
        }
        editor.statusBarInfo().ifPresent(info -> {
            String cursor = info.line() + ":" + info.column();
            segments.add(StatusBarLayout.Segment.of("caret", PRIORITY_CARET, cursor));
            segments.add(StatusBarLayout.Segment.of("line-separator", PRIORITY_ENCODING, info.lineSeparator()));
            segments.add(StatusBarLayout.Segment.of("encoding", PRIORITY_ENCODING, info.encoding()));
            if (!info.indent().isBlank()) {
                segments.add(StatusBarLayout.Segment.of("indent", PRIORITY_INDENT, info.indent()));
            }
            if (info.readOnly()) {
                segments.add(StatusBarLayout.Segment.of("read-only", PRIORITY_LOCK, glyphs.readOnly()));
            }
        });
        return List.copyOf(segments);
    }

    /**
     * The provider lines of the popup: the capability states first, each with the
     * widgets its provider contributes, then the contributors of the tool windows.
     */
    List<String> providerLines() {
        List<String> lines = new ArrayList<>();
        if (registry != null) {
            for (CapabilityStatusWidgets capabilityStatusWidgets : registry.statusBarSnapshot()) {
                lines.add(providerText(capabilityStatusWidgets.status()));
                for (StatusBarWidget widget : capabilityStatusWidgets.widgets()) {
                    lines.add(widget.text());
                }
            }
        }
        for (StatusBarWidgetContributor contributor : contributors) {
            for (StatusBarWidget widget : contributor.statusBarWidgets()) {
                lines.add(widget.text());
            }
        }
        return List.copyOf(lines);
    }

    /** The number of capabilities without a healthy provider. Zero hides the indicator. */
    int degradedCount() {
        if (registry == null) {
            return 0;
        }
        return (int) registry.statusBarSnapshot().stream()
                .filter(widgets -> !widgets.status().isAvailable())
                .count();
    }

    String divider() {
        return glyphs.divider();
    }

    String gap() {
        return GAP;
    }

    Navigation navigation() {
        return navigation.get() == null ? Navigation.NONE : navigation.get();
    }

    static String providerText(CapabilityStatus status) {
        String label = CapabilityStateLine.label(status.capability());
        if (status.isAvailable()) {
            return label + ": " + status.providerIdentifier() + " (" + status.provenance() + ")";
        }
        return label + ": unavailable: " + status.health().reason();
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

    private StatusBarLayout.Segment degradedIndicator() {
        int count = degradedCount();
        String plural = count == 1 ? "provider" : "providers";
        return new StatusBarLayout.Segment(PROVIDERS_ID, PRIORITY_PROVIDERS,
                glyphs.warning() + " " + count + " " + plural + " degraded",
                glyphs.warning() + " " + count + " degraded");
    }

    private static boolean isTypeOrCallable(int kind) {
        return kind == 5 || kind == 6 || kind == 9 || kind == 11 || kind == 12 || kind == 23;
    }
}
