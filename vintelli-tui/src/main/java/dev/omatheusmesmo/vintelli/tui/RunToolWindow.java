package dev.omatheusmesmo.vintelli.tui;

import java.nio.file.Path;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugSession;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugSessionSnapshot;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.icons.FileIcons;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.tfx.Fx;
import dev.tamboui.tfx.Interpolation;
import dev.tamboui.tfx.Motion;
import dev.tamboui.tfx.toolkit.ToolkitEffects;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.CharWidth;
import dev.tamboui.tfx.Effect;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/** Run configurations and their console, backed by the selected RunCapability. */
@Singleton
public final class RunToolWindow implements ToolWindow {

    private static final String PLAY_GLYPH = "";
    private static final int MAX_OUTPUT_LINES = 500;
    private static final String LOADING = "Loading run configurations…";

    private final Provider<ShellThemes> themes;
    private final CapabilityRegistry capabilities;
    private final UiWorkQueue uiWork;
    private final Path workingDirectory;
    private final RunSelectionStore selectionStore;
    private final int debugPort;
    private final IconMode iconMode;
    private volatile boolean debugging;
    private volatile RunCapability.DebugSupport debugSupport = new RunCapability.DebugSupport(false,
            "Select an available run configuration first");
    private String preferredConfiguration;
    private final ArrayDeque<String> output = new ArrayDeque<>();
    private final AtomicLong listingSequence = new AtomicLong();
    private final AtomicLong runSequence = new AtomicLong();
    private final AtomicLong capabilitySequence = new AtomicLong();
    private final AtomicBoolean capabilityRequested = new AtomicBoolean();
    private final AtomicBoolean debugActionPending = new AtomicBoolean();
    private final boolean ideaConfigured;

    private volatile List<RunCapability.RunConfiguration> configurations = List.of();
    private volatile RunCapability provider;
    private volatile CapabilityStateLine.Summary capabilitySummary = CapabilityStateLine.checking(CapabilityType.RUN);
    private volatile String status = LOADING;
    private volatile RunCapability.RunHandle handle;
    private final RunMotion motion;
    private volatile String runIdentifier = "";
    private volatile DebugSession debugSession;
    private volatile EditorBridge debugEditorBridge;
    private volatile Consumer<String> stopAnnouncement = message -> { };
    private int selectedIndex;
    private int configurationRowsStart = -1;
    private int visibleConfigurationStart;
    private int visibleConfigurationCount;
    private Rect lastArea = Rect.ZERO;
    private Rect headerArea = Rect.ZERO;
    private boolean inlineHeader;
    private volatile ToolkitEffects toolkitEffects;
    private volatile RunMotionState pendingEffect;

    /** Standalone construction for registry and render tests. */
    public RunToolWindow(Provider<ShellThemes> themes) {
        this.themes = themes;
        this.capabilities = null;
        this.uiWork = new UiWorkQueue();
        this.workingDirectory = Path.of(".").toAbsolutePath().normalize();
        this.selectionStore = null;
        this.debugPort = 5005;
        this.status = "Run provider unavailable";
        this.capabilitySummary = CapabilityStateLine.notConfigured(CapabilityType.RUN);
        this.ideaConfigured = false;
        this.motion = new RunMotion(System::nanoTime, false);
        this.iconMode = FileIcons.resolve().mode();
    }

    public RunToolWindow(Provider<ShellThemes> themes, CapabilityRegistry capabilities, UiWorkQueue uiWork,
            String workingDirectory) {
        this(themes, capabilities, uiWork, workingDirectory, null);
    }

    public RunToolWindow(Provider<ShellThemes> themes, CapabilityRegistry capabilities, UiWorkQueue uiWork,
            @ConfigProperty(name = "vintelli.local.project-root", defaultValue = ".") String workingDirectory,
            RunSelectionStore selectionStore) {
        this(themes, capabilities, uiWork, workingDirectory, selectionStore, 5005);
    }

    public RunToolWindow(Provider<ShellThemes> themes, CapabilityRegistry capabilities, UiWorkQueue uiWork,
            String workingDirectory, RunSelectionStore selectionStore, int debugPort) {
        this(themes, capabilities, uiWork, workingDirectory, selectionStore, debugPort, Optional.empty());
    }

    public RunToolWindow(Provider<ShellThemes> themes, CapabilityRegistry capabilities, UiWorkQueue uiWork,
            @ConfigProperty(name = "vintelli.local.project-root", defaultValue = ".") String workingDirectory,
            RunSelectionStore selectionStore,
            @ConfigProperty(name = "vintelli.debug.port", defaultValue = "5005") int debugPort,
            @ConfigProperty(name = "vintelli.idea.mcp.url") Optional<String> ideaUrl) {
        this(themes, capabilities, uiWork, workingDirectory, selectionStore, debugPort, ideaUrl,
                "full", Optional.empty(), Optional.empty());
    }

    @Inject
    public RunToolWindow(Provider<ShellThemes> themes, CapabilityRegistry capabilities, UiWorkQueue uiWork,
            @ConfigProperty(name = "vintelli.local.project-root", defaultValue = ".") String workingDirectory,
            RunSelectionStore selectionStore,
            @ConfigProperty(name = "vintelli.debug.port", defaultValue = "5005") int debugPort,
            @ConfigProperty(name = "vintelli.idea.mcp.url") Optional<String> ideaUrl,
            @ConfigProperty(name = "vintelli.motion", defaultValue = "full") String motionMode,
            @ConfigProperty(name = "term") Optional<String> term,
            @ConfigProperty(name = "no.color") Optional<String> noColor) {
        this(themes, capabilities, uiWork, workingDirectory, selectionStore, debugPort, ideaUrl,
                System::nanoTime, reducedMotionEnabled(motionMode, term, noColor, System.getenv("NO_COLOR") != null),
                FileIcons.resolve().mode());
    }

    static boolean reducedMotionEnabled(String motionMode, Optional<String> term,
            Optional<String> noColor, boolean noColorEnvironment) {
        return "reduced".equalsIgnoreCase(motionMode)
                || term.map(value -> value.equalsIgnoreCase("dumb") || value.equalsIgnoreCase("vt100"))
                        .orElse(false)
                || noColor.isPresent() || noColorEnvironment;
    }

    RunToolWindow(Provider<ShellThemes> themes, CapabilityRegistry capabilities, UiWorkQueue uiWork,
            String workingDirectory, RunSelectionStore selectionStore, int debugPort, Optional<String> ideaUrl,
            LongSupplier clock, boolean reducedMotion) {
        this(themes, capabilities, uiWork, workingDirectory, selectionStore, debugPort, ideaUrl,
                clock, reducedMotion, FileIcons.resolve().mode());
    }

    RunToolWindow(Provider<ShellThemes> themes, CapabilityRegistry capabilities, UiWorkQueue uiWork,
            String workingDirectory, RunSelectionStore selectionStore, int debugPort, Optional<String> ideaUrl,
            LongSupplier clock, boolean reducedMotion, IconMode iconMode) {
        this.debugPort = debugPort;
        this.themes = themes;
        this.capabilities = capabilities;
        this.uiWork = uiWork;
        this.workingDirectory = Path.of(workingDirectory).toAbsolutePath().normalize();
        this.selectionStore = selectionStore;
        this.preferredConfiguration = selectionStore == null ? null : selectionStore.load().orElse(null);
        this.ideaConfigured = CapabilityStateLine.ideaConfigured(ideaUrl);
        this.motion = new RunMotion(clock, reducedMotion);
        this.iconMode = iconMode;
    }

    @PostConstruct
    void initialize() {
        refreshConfigurations();
    }

    @Override
    public String id() {
        return "run";
    }

    @Override
    public String title() {
        return "Run";
    }

    @Override
    public char shortcut() {
        return '4';
    }

    @Override
    public int ordinal() {
        return 0;
    }

    @Override
    public String icon() {
        return motion.state() == RunMotionState.IDLE
                ? iconMode == IconMode.ASCII ? ">" : PLAY_GLYPH
                : motion.glyph(iconMode == IconMode.ASCII);
    }

    String stateGlyph(boolean ascii) {
        return motion.state() == RunMotionState.IDLE ? "4" : motion.glyph(ascii || iconMode == IconMode.ASCII);
    }

    String toolbarGlyph() {
        return motion.state() == RunMotionState.IDLE ? null : motion.glyph(iconMode == IconMode.ASCII);
    }

    String toolbarStateLabel() {
        return motion.state() == RunMotionState.IDLE ? null : motion.state().label();
    }

    String toolbarElapsedText() {
        return motion.active() ? elapsedText(motion.elapsedMillis()) : null;
    }

    String stateThemeRole() {
        return motion.state() == RunMotionState.IDLE ? null : motion.state().themeRole();
    }

    String tabTitle() {
        RunMotionState current = motion.state();
        return current == RunMotionState.IDLE ? title() : title() + " · " + current.label();
    }

    void tickMotion() {
        motion.tick();
    }

    boolean motionAnimating() {
        return motion.animating();
    }

    Duration motionTickRate() {
        return motion.tickRate();
    }

    @Override
    public boolean capturesInput() {
        return false;
    }

    @Override
    public boolean shouldHandleKeyBeforeShell(KeyEvent event) {
        return actionForKey(event) != null;
    }

    @Override
    public String actionForKey(KeyEvent event) {
        if (debugSession != null && debugging && !event.hasCtrl() && !event.hasAlt()) {
            if (event.isCharIgnoreCase('c')) return KeyActions.DEBUG_RESUME;
            if (event.isCharIgnoreCase('p')) return KeyActions.DEBUG_PAUSE;
            if (event.isCharIgnoreCase('o')) return KeyActions.DEBUG_STEP_OVER;
            if (event.isCharIgnoreCase('i')) return KeyActions.DEBUG_STEP_INTO;
            if (event.isCharIgnoreCase('u')) return KeyActions.DEBUG_STEP_OUT;
        }
        if (Keys.is(event, KeyCode.ENTER)) {
            return KeyActions.RUN_EXECUTE;
        }
        if (event.isCharIgnoreCase('r')) {
            return motion.state() == RunMotionState.IDLE || motion.active() || motion.animating()
                    ? KeyActions.RUN_REFRESH : KeyActions.RUN_EXECUTE;
        }
        if (event.isCharIgnoreCase('s')) {
            return motion.active() && handle != null && handle.supportsCancellation() ? KeyActions.RUN_STOP : null;
        }
        if (Keys.is(event, KeyCode.DOWN) || event.isCharIgnoreCase('j')) {
            return KeyActions.RUN_SELECT_NEXT;
        }
        if (Keys.is(event, KeyCode.UP) || event.isCharIgnoreCase('k')) {
            return KeyActions.RUN_SELECT_PREVIOUS;
        }
        return null;
    }

    @Override
    public String actionForMouse(MouseEvent event) {
        if (!event.isClick() || !event.isLeftButton()) {
            return null;
        }
        if (motion.state() != RunMotionState.IDLE && headerArea.contains(event.x(), event.y())) {
            return headerActionAt(event.x() - headerArea.x());
        }
        if (!insideLastArea(event)) {
            return null;
        }
        int row = event.y() - lastArea.y();
        int column = event.x() - lastArea.x();
        if (motion.state() == RunMotionState.IDLE && row == 1 && column >= 0 && column < 18) {
            return KeyActions.RUN_EXECUTE;
        }
        if (motion.state() == RunMotionState.IDLE && row == 1 && column >= 18 && column < 36) {
            return KeyActions.RUN_REFRESH;
        }
        if (debugSession != null && row == (motion.state() == RunMotionState.IDLE ? 1 : 0)) {
            return debugActionAt(column, debugControlsLine());
        }
        return null;
    }

    private String headerActionAt(int column) {
        String controls = runHeaderControls();
        if (controls.isEmpty()) return null;
        int start = CharWidth.of(runHeader().substring(0, runHeader().lastIndexOf(controls)));
        int width = CharWidth.of(controls);
        if (column < start || column >= start + width) return null;
        if (motion.active()) {
            return handle != null && handle.supportsCancellation() ? KeyActions.RUN_STOP : null;
        }
        return KeyActions.RUN_EXECUTE;
    }

    private static String debugActionAt(int column, String controls) {
        String[] labels = { "Resume", "Pause", "Over", "Into", "Out" };
        String[] actions = { KeyActions.DEBUG_RESUME, KeyActions.DEBUG_PAUSE,
                KeyActions.DEBUG_STEP_OVER, KeyActions.DEBUG_STEP_INTO, KeyActions.DEBUG_STEP_OUT };
        int start = controls.indexOf(labels[0]);
        int buttonStart = CharWidth.of(controls.substring(0, start));
        for (int index = 0; index < labels.length; index++) {
            int width = CharWidth.of(labels[index]);
            if (column >= buttonStart && column < buttonStart + width) return actions[index];
            buttonStart += width + 2;
        }
        return null;
    }

    @Override
    public boolean handleMouse(MouseEvent event) {
        if (!event.isClick() || !event.isLeftButton() || !insideLastArea(event)) {
            return false;
        }
        int row = event.y() - lastArea.y() - (inlineHeader && motion.state() != RunMotionState.IDLE ? 1 : 0);
        int index = visibleConfigurationStart + row - configurationRowsStart;
        if (configurationRowsStart >= 0 && row >= configurationRowsStart
                && row < configurationRowsStart + visibleConfigurationCount
                && index >= 0 && index < configurations.size()) {
            selectConfiguration(configurations.get(index).identifier());
            return true;
        }
        return false;
    }

    @Override
    public boolean handleKey(KeyEvent event) {
        return false;
    }

    @Override
    public boolean performAction(String actionId) {
        return performAction(actionId, null);
    }

    @Override
    public boolean performAction(String actionId, KeyEvent event) {
        if (KeyActions.DEBUG_RESUME.equals(actionId) || KeyActions.DEBUG_PAUSE.equals(actionId)
                || KeyActions.DEBUG_STEP_OVER.equals(actionId) || KeyActions.DEBUG_STEP_INTO.equals(actionId)
                || KeyActions.DEBUG_STEP_OUT.equals(actionId)) {
            requestDebugAction(actionId);
            return true;
        }
        if (KeyActions.RUN_DEBUG.equals(actionId)) {
            startSelected(true);
            return true;
        }
        if (KeyActions.RUN_EXECUTE.equals(actionId)) {
            startSelected(false);
            return true;
        }
        if (KeyActions.RUN_REFRESH.equals(actionId)) {
            refreshConfigurations();
            return true;
        }
        if (KeyActions.RUN_SELECT_NEXT.equals(actionId)) {
            selectIndex(Math.min(Math.max(0, configurations.size() - 1), selectedIndex + 1));
            return true;
        }
        if (KeyActions.RUN_SELECT_PREVIOUS.equals(actionId)) {
            selectIndex(Math.max(0, selectedIndex - 1));
            return true;
        }
        if (KeyActions.RUN_STOP.equals(actionId)) {
            stopCurrent();
            return true;
        }
        return false;
    }

    @Override
    public void render(Frame frame, Rect area) {
        render(frame, area, false);
    }

    void renderBody(Frame frame, Rect area) {
        render(frame, area, true);
    }

    private void render(Frame frame, Rect area, boolean separateHeader) {
        if (capabilities != null && capabilityRequested.compareAndSet(false, true)) {
            refreshCapabilityLine();
        }
        configurationRowsStart = -1;
        visibleConfigurationCount = 0;
        boolean showRunHeader = motion.state() != RunMotionState.IDLE;
        inlineHeader = showRunHeader && !separateHeader;
        List<String> lines = new ArrayList<>();
        if (!showRunHeader) {
            lines.add(capabilitySummary.headline());
            lines.add(controlsLine());
            lines.add(status);
        } else if (debugSession != null) {
            lines.add(debugControlsLine());
            lines.add(debugSnapshotLine());
        }
        if (!motion.active()) {
            if (!debugSupport.available()) lines.add("Debug unavailable: " + debugSupport.reason());
            lines.addAll(debugUnavailableActions());
            lines.addAll(capabilitySummary.skipped());
        } else {
            lines.addAll(debugUnavailableActions());
        }

        Rect bodyArea = inlineHeader && area.height() > 0
                ? new Rect(area.x(), area.y() + 1, area.width(), area.height() - 1) : area;
        lastArea = bodyArea;
        int innerRows = Math.max(0, bodyArea.height());
        int bodyRows = Math.max(0, innerRows - lines.size());
        if (motion.active()) {
            appendConsole(lines, bodyRows);
        } else if (configurations.isEmpty()) {
            int consoleRows = consoleRows(bodyRows);
            int messageRows = bodyRows - consoleRows;
            if (messageRows > 0) {
                lines.add(emptyStateLine());
            }
            if (messageRows > 1 && !status.equals(LOADING)
                    && !status.startsWith("Run configuration listing failed")) {
                lines.add("Open a Maven/Gradle project or connect an IDEA provider.");
            }
            appendConsole(lines, consoleRows);
        } else {
            int consoleRows = consoleRows(bodyRows);
            int configurationRows = bodyRows - consoleRows;
            lines.add("Configurations");
            configurationRowsStart = lines.size();
            visibleConfigurationCount = Math.min(configurations.size(), Math.max(0, configurationRows - 1));
            if (visibleConfigurationCount > 0) {
                visibleConfigurationStart = Math.max(0,
                        Math.min(selectedIndex, configurations.size() - visibleConfigurationCount));
            }
            for (int offset = 0; offset < visibleConfigurationCount; offset++) {
                int index = visibleConfigurationStart + offset;
                RunCapability.RunConfiguration configuration = configurations.get(index);
                String marker = index == selectedIndex ? "› " : "  ";
                lines.add(marker + configuration.identifier() + summary(configuration.summary()));
            }
            appendConsole(lines, consoleRows);
        }

        ShellThemes activeThemes = themes.get();
        Style contentStyle = activeThemes.resolver("tool-window").toStyle();
        if (inlineHeader && area.height() > 0) {
            renderHeader(frame, new Rect(area.x(), area.y(), area.width(), 1));
        } else if (!showRunHeader) {
            headerArea = Rect.ZERO;
        }
        frame.renderWidget(Paragraph.builder()
                .text(Text.from(String.join("\n", lines)))
                .style(contentStyle)
                .build(), bodyArea);
    }

    void renderHeader(Frame frame, Rect area) {
        headerArea = area;
        applyPendingEffect();
        ShellThemes activeThemes = themes.get();
        Line header = Line.from(Span.styled(runHeader(), activeThemes.resolver(motion.state().themeRole()).toStyle()));
        frame.renderWidget(Paragraph.builder().text(Text.from(header))
                .style(activeThemes.resolver("tool-window").toStyle()).build(), area);
    }

    void bindMotionEffects(ToolkitEffects effects) {
        this.toolkitEffects = effects;
    }

    /** Receives the exception a debug session stops on, so the shell can show it in its status line. */
    void bindStopAnnouncement(Consumer<String> announcement) {
        this.stopAnnouncement = announcement;
    }

    private void applyPendingEffect() {
        ToolkitEffects effects = toolkitEffects;
        RunMotionState state = pendingEffect;
        if (effects == null || state == null) {
            return;
        }
        pendingEffect = null;
        effects.clear();
        if (motion.reduced() || state != motion.state()
                || (state == RunMotionState.SUCCEEDED || state == RunMotionState.FAILED) && !motion.animating()) {
            return;
        }
        ShellThemes activeThemes = themes.get();
        switch (state) {
            case STARTING -> effects.addEffect("tool-tabs-bottom", Fx.sweepIn(Motion.LEFT_TO_RIGHT, 4, 0,
                    activeThemes.color("focus-border"), 600, Interpolation.QuadOut));
            case SUCCEEDED, FAILED -> effects.addEffect(RunHeaderElement.ID, completionEffect(state, activeThemes));
            default -> { }
        }
    }

    Effect completionEffect(RunMotionState state, ShellThemes activeThemes) {
        return switch (state) {
            case SUCCEEDED -> Fx.fadeFrom(activeThemes.color("focus-border"),
                    activeThemes.color("chrome-fg-strong"), 600, Interpolation.QuadOut);
            case FAILED -> Fx.paintBg(activeThemes.color("editor-keyword"), 400, Interpolation.QuadOut);
            default -> throw new IllegalArgumentException("no completion effect for " + state);
        };
    }

    private String runHeader() {
        RunMotionState state = motion.state();
        String elapsed = motion.active() ? "   " + elapsedText(motion.elapsedMillis()) : "";
        return motion.glyph(iconMode == IconMode.ASCII) + " " + state.label() + "   " + runIdentifier
                + elapsed + "   " + runHeaderControls();
    }

    private String runHeaderControls() {
        String stop = iconMode == IconMode.ASCII ? "#" : "■";
        if (motion.active()) {
            return handle == null || !handle.supportsCancellation() ? "Stop unavailable" : stop + " Stop [s]";
        }
        if (motion.animating()) return "";
        return iconMode == IconMode.ASCII ? "Rerun [r]" : "↻ Rerun [r]";
    }

    private static String elapsedText(long elapsedMillis) {
        long seconds = Math.max(0, elapsedMillis / 1_000);
        return "%02d:%02d".formatted(seconds / 60, seconds % 60);
    }

    private String emptyStateLine() {
        if (status.equals(LOADING)) {
            return LOADING;
        }
        if (status.startsWith("Run configuration listing failed")) {
            return "Configuration listing failed.";
        }
        if (provider == null) {
            return status;
        }
        return "No run configurations available.";
    }

    private static int consoleRows(int availableRows) {
        if (availableRows <= 1) {
            return availableRows;
        }
        return Math.min(availableRows - 1, Math.max(2, availableRows / 3));
    }

    private String controlsLine() {
        String play = iconMode == IconMode.ASCII ? ">" : "▶";
        String refresh = iconMode == IconMode.ASCII ? "R" : "↻";
        String stop = iconMode == IconMode.ASCII ? "#" : "■";
        return play + " Run [Enter]     " + refresh + " Refresh [r]     "
                + (handle == null || !handle.supportsCancellation() ? "Stop unavailable" : stop + " Stop")
                + (debugSession == null ? "" : "     " + debugControlsLine());
    }

    private static String debugControlsLine() {
        return "Resume  Pause  Over  Into  Out";
    }

    private String debugSnapshotLine() {
        DebugSession active = debugSession;
        if (active == null) return "";
        DebugSessionSnapshot snapshot = active.snapshot();
        DebugCapability.DebugFrame frame = snapshot.currentFrame();
        if (frame == null) return "Debugger " + snapshot.state().name().toLowerCase(Locale.ROOT);
        String source = frame.sourcePath().isBlank() ? frame.name() : frame.sourcePath();
        return "Debugger " + snapshot.state().name().toLowerCase(Locale.ROOT) + " · " + source + ":" + frame.line();
    }

    private void appendConsole(List<String> lines, int rows) {
        if (rows <= 0) {
            return;
        }
        String pulse = motion.outputPulse(iconMode == IconMode.ASCII);
        lines.add(pulse.isEmpty() ? "Console" : "Console   " + pulse);
        int outputRows = rows - 1;
        synchronized (output) {
            int skip = Math.max(0, output.size() - outputRows);
            int index = 0;
            for (String line : output) {
                if (index++ >= skip) {
                    lines.add(line);
                }
            }
        }
    }

    private static String summary(String summary) {
        return summary.isBlank() ? "" : "  ·  " + summary;
    }

    /**
     * The label of the run widget in the main toolbar: the selected
     * configuration, or {@code Run} when none is listed. {@code null} when no
     * Run provider was selected, which hides the widget. Reads volatile state
     * only, so the toolbar may call it while it paints.
     */
    String selectedConfigurationLabel() {
        RunCapability current = provider;
        if (capabilities == null || current == null) {
            return null;
        }
        List<RunCapability.RunConfiguration> listed = configurations;
        int index = selectedIndex;
        return index < 0 || index >= listed.size() ? "Run" : listed.get(index).identifier();
    }

    boolean selectConfiguration(String identifier) {
        for (int index = 0; index < configurations.size(); index++) {
            if (configurations.get(index).identifier().equals(identifier)) {
                selectIndex(index);
                return true;
            }
        }
        return false;
    }

    private void selectIndex(int index) {
        if (index < 0 || index >= configurations.size()) return;
        selectedIndex = index;
        refreshDebugSupport();
        preferredConfiguration = configurations.get(index).identifier();
        if (selectionStore != null) {
            try {
                selectionStore.save(preferredConfiguration);
            } catch (IOException failure) {
                status = "Cannot save run selection: " + message(failure);
                append(status);
            }
        }
    }

    boolean debugAvailable() {
        return debugSupport.available() && !motion.active();
    }

    boolean debugging() {
        return debugging && motion.active();
    }

    DebugSession activeDebugSession() { return debugSession; }

    boolean debugSessionSuspended() {
        DebugSession active = debugSession;
        return active != null && active.snapshot().state() == DebugCapability.State.STOPPED;
    }

    EditorBridge debugEditorBridge() { return debugEditorBridge; }

    private void refreshDebugSupport() {
        RunCapability current = provider;
        debugSupport = current == null || configurations.isEmpty() || selectedIndex >= configurations.size()
                ? new RunCapability.DebugSupport(false, "Select an available run configuration first")
                : current.debugSupport(configurations.get(selectedIndex).identifier());
    }

    /** True while a configuration runs; the main toolbar shows Stop in its Run slot then. */
    boolean running() {
        return motion.active();
    }

    /** True when a running handle supports cancellation, the same check the Stop control makes. */
    boolean canStop() {
        RunCapability.RunHandle active = handle;
        return active != null && active.supportsCancellation();
    }

    private boolean insideLastArea(MouseEvent event) {
        return event.x() >= lastArea.x() && event.x() < lastArea.x() + lastArea.width()
                && event.y() >= lastArea.y() && event.y() < lastArea.y() + lastArea.height();
    }

    void refreshConfigurations() {
        if (capabilities == null) {
            return;
        }
        long sequence = listingSequence.incrementAndGet();
        status = LOADING;
        refreshCapabilityLine();
        Thread.ofVirtual().name("vintelli-run-list").start(() -> {
            CapabilitySelection<RunCapability> selection;
            try {
                selection = capabilities.run();
            } catch (RuntimeException failure) {
                uiWork.execute(() -> {
                    if (sequence == listingSequence.get()) {
                        provider = null;
                        configurations = List.of();
                        status = "Run provider selection failed: " + message(failure);
                        append(status);
                    }
                });
                return;
            }
            if (selection instanceof CapabilitySelection.Unavailable<RunCapability> unavailable) {
                uiWork.execute(() -> {
                    if (sequence == listingSequence.get()) {
                        provider = null;
                        configurations = List.of();
                        status = unavailable.reason();
                    }
                });
                return;
            }
            RunCapability selected = ((CapabilitySelection.Selected<RunCapability>) selection).provider();
            CapabilityResult<List<RunCapability.RunConfiguration>> result;
            try {
                result = selected.listConfigurations();
            } catch (RuntimeException failure) {
                uiWork.execute(() -> showListingFailure(sequence, selected,
                        "Run configuration listing failed: " + message(failure)));
                return;
            }
            uiWork.execute(() -> {
                if (sequence != listingSequence.get()) {
                    return;
                }
                provider = selected;
                if (result instanceof CapabilityResult.Failed<List<RunCapability.RunConfiguration>> failed) {
                    configurations = List.of();
                    status = "Run configuration listing failed: " + failed.failure().reason();
                    append(status);
                } else {
                    configurations = ((CapabilityResult.Ok<List<RunCapability.RunConfiguration>>) result).value();
                    selectedIndex = 0;
                    for (int index = 0; index < configurations.size(); index++) {
                        if (configurations.get(index).identifier().equals(preferredConfiguration)) selectedIndex = index;
                    }
                    refreshDebugSupport();
                    status = configurations.isEmpty() ? "No configurations found" : "Ready";
                }
            });
        });
    }

    private void refreshCapabilityLine() {
        capabilityRequested.set(true);
        long sequence = capabilitySequence.incrementAndGet();
        Thread.ofVirtual().name("vintelli-run-capability").start(() -> {
            CapabilityStateLine.Summary summary = CapabilityStateLine.read(capabilities, CapabilityType.RUN,
                    ideaConfigured);
            uiWork.execute(() -> {
                if (sequence == capabilitySequence.get()) {
                    capabilitySummary = summary;
                }
            });
        });
    }

    private void showListingFailure(long sequence, RunCapability selected, String failure) {
        if (sequence != listingSequence.get()) {
            return;
        }
        provider = selected;
        configurations = List.of();
        status = failure;
        append(failure);
    }

    private void startSelected(boolean debug) {
        if (capabilities == null) {
            status = "Run provider unavailable";
            return;
        }
        if (motion.active()) {
            return;
        }
        if (configurations.isEmpty() || selectedIndex >= configurations.size()) {
            status = "Select an available run configuration first";
            return;
        }
        if (debug && !debugSupport.available()) {
            status = "Debug unavailable: " + debugSupport.reason();
            append(status);
            return;
        }
        startRun(configurations.get(selectedIndex).identifier(), List.of(), debug);
    }

    /**
     * Runs a named build tool goal with extra arguments, such as a Maven phase
     * in one module, and shows its output here. A run that is already active
     * keeps running and the status line says so.
     */
    public void startGoal(String identifier, List<String> arguments) {
        if (capabilities == null) {
            status = "Run provider unavailable";
            return;
        }
        if (motion.active()) {
            status = "A run is already active; stop it first";
            return;
        }
        startRun(identifier, arguments, false);
    }

    private void startRun(String identifier, List<String> arguments, boolean debug) {
        clearExecutionMarker();
        debugEditorBridge = null;
        RunCapability selected = provider;
        if (selected == null) {
            status = "Run provider unavailable; refresh configurations";
            return;
        }
        long sequence = runSequence.incrementAndGet();
        motion.start();
        pendingEffect = RunMotionState.STARTING;
        runIdentifier = identifier;
        debugging = debug;
        handle = null;
        debugSession = null;
        status = "Starting " + identifier;
        output.clear();
        append("Starting " + identifier + " via " + selected.identifier());
        AtomicReference<RunCapability.RunCompletion> completion = new AtomicReference<>();
        Thread.ofVirtual().name("vintelli-run-start").start(() -> {
            CapabilitySelection<RunCapability> selection;
            try {
                selection = capabilities.run();
            } catch (RuntimeException failure) {
                uiWork.execute(() -> {
                    if (sequence == runSequence.get()) {
                        failRun("Run provider selection failed: " + message(failure));
                    }
                });
                return;
            }
            if (selection instanceof CapabilitySelection.Unavailable<RunCapability> unavailable) {
                uiWork.execute(() -> {
                    if (sequence == runSequence.get()) {
                        provider = null;
                        configurations = List.of();
                        refreshCapabilityLine();
                        failRun(unavailable.reason());
                    }
                });
                return;
            }
            RunCapability current = ((CapabilitySelection.Selected<RunCapability>) selection).provider();
            if (!sameProvider(selected, current)) {
                uiWork.execute(() -> {
                    if (sequence == runSequence.get()) {
                        provider = current;
                        configurations = List.of();
                        selectedIndex = 0;
                        refreshCapabilityLine();
                        failRun("Run provider changed; refresh configurations");
                    }
                });
                return;
            }
            DebugCapability debugProvider = null;
            EditorBridge debugEditor = null;
            if (debug) {
                CapabilitySelection<DebugCapability> debugSelection;
                try {
                    debugSelection = capabilities.debug();
                } catch (RuntimeException failure) {
                    uiWork.execute(() -> {
                        if (sequence == runSequence.get()) {
                            failRun("Debug provider selection failed: " + message(failure));
                        }
                    });
                    return;
                }
                if (debugSelection instanceof CapabilitySelection.Unavailable<DebugCapability> unavailable) {
                    uiWork.execute(() -> {
                        if (sequence == runSequence.get()) {
                            failRun("Debug unavailable: " + unavailable.reason());
                        }
                    });
                    return;
                }
                debugProvider = ((CapabilitySelection.Selected<DebugCapability>) debugSelection).provider();
                try {
                    CapabilitySelection<EditorBridge> editorSelection = capabilities.editorBridge();
                    if (editorSelection instanceof CapabilitySelection.Selected<EditorBridge> editor) {
                        debugEditor = editor.provider();
                        DebugCapability.ExceptionBreakpoints stored = debugEditor.exceptionBreakpoints();
                        stored.reportOf(debugProvider.setExceptionBreakpoints(stored)).ifPresent(message ->
                                uiWork.execute(() -> {
                                    if (sequence == runSequence.get()) {
                                        status = message;
                                    }
                                }));
                    }
                    CapabilityResult<Map<String, List<DebugCapability.SourceBreakpoint>>> synchronizedSources =
                            synchronizeBreakpoints(debugProvider, debugEditor);
                    if (synchronizedSources instanceof CapabilityResult.Failed<Map<String, List<DebugCapability.SourceBreakpoint>>> failed) {
                        uiWork.execute(() -> {
                            if (sequence == runSequence.get()) {
                                failRun("Breakpoint synchronization failed: " + failed.failure().reason());
                            }
                        });
                        return;
                    }
                } catch (RuntimeException failure) {
                    uiWork.execute(() -> {
                        if (sequence == runSequence.get()) {
                            failRun("Breakpoint synchronization failed: " + message(failure));
                        }
                    });
                    return;
                }
            }
            CapabilityResult<RunCapability.RunHandle> result;
            DebugSession attachedSession = null;
            try {
                var request = new RunCapability.RunRequest(identifier, workingDirectory, Map.of(), arguments);
                Consumer<String> outputLines = line -> uiWork.execute(() -> {
                    if (sequence == runSequence.get()) {
                        motion.outputLine();
                        append(line);
                    }
                });
                Consumer<RunCapability.RunCompletion> ended = value -> {
                    completion.set(value);
                    uiWork.execute(() -> completeRun(sequence, value));
                };
                result = debug ? selected.startDebug(request, debugPort, outputLines, ended)
                        : selected.start(request, outputLines, ended);
                if (debug && result instanceof CapabilityResult.Ok<RunCapability.RunHandle> started) {
                    CapabilityResult<DebugSession> attached;
                    try {
                        attached = debugProvider.attach(new DebugCapability.AttachRequest(debugPort, identifier), outputLines);
                    } catch (RuntimeException failure) {
                        started.value().cancel();
                        uiWork.execute(() -> {
                            if (sequence == runSequence.get()) {
                                failRun("Debug attach failed: " + message(failure));
                            }
                        });
                        return;
                    }
                    if (attached instanceof CapabilityResult.Failed<DebugSession> failed) {
                        started.value().cancel();
                        uiWork.execute(() -> {
                            if (sequence == runSequence.get()) {
                                failRun("Debug attach failed: " + failed.failure().reason());
                            }
                        });
                        return;
                    }
                    attachedSession = ((CapabilityResult.Ok<DebugSession>) attached).value();
                    CapabilityResult<Map<String, List<DebugCapability.SourceBreakpoint>>> synchronizedSources =
                            synchronizeBreakpoints(debugProvider, debugEditor);
                    if (synchronizedSources instanceof CapabilityResult.Failed<Map<String, List<DebugCapability.SourceBreakpoint>>> failed) {
                        disconnect(attachedSession);
                        started.value().cancel();
                        uiWork.execute(() -> {
                            if (sequence == runSequence.get()) {
                                failRun("Breakpoint synchronization failed: " + failed.failure().reason());
                            }
                        });
                        return;
                    }
                }
            } catch (RuntimeException failure) {
                uiWork.execute(() -> {
                    if (sequence == runSequence.get()) {
                        failRun("Run failed: " + message(failure));
                    }
                });
                return;
            }
            DebugSession session = attachedSession;
            EditorBridge activeDebugEditor = debugEditor;
            uiWork.execute(() -> {
                if (sequence != runSequence.get()) {
                    disconnectAsync(session);
                    return;
                }
                if (result instanceof CapabilityResult.Failed<RunCapability.RunHandle> failed) {
                    failRun("Run failed: " + failed.failure().reason()
                            + (failed.failure().detail().isBlank() ? "" : " — " + failed.failure().detail()));
                    return;
                }
                if (completion.get() != null) {
                    disconnectAsync(session);
                    completeRun(sequence, completion.get());
                } else {
                    handle = ((CapabilityResult.Ok<RunCapability.RunHandle>) result).value();
                    debugSession = session;
                    debugEditorBridge = activeDebugEditor;
                    motion.running();
                    pendingEffect = RunMotionState.RUNNING;
                    status = (debug ? "Debug 127.0.0.1:" + debugPort + " · " : "Running ") + identifier;
                    if (session != null) watchDebugSession(sequence, session, activeDebugEditor);
                }
            });
        });
    }

    private void stopCurrent() {
        RunCapability.RunHandle active = handle;
        if (active == null) {
            status = motion.active() ? "Run is starting; stop is not available yet" : "No run is active";
            return;
        }
        if (!active.supportsCancellation()) {
            status = "Stop is unavailable for this provider";
            return;
        }
        DebugSession activeDebugSession = debugSession;
        debugSession = null;
        clearExecutionMarker();
        debugEditorBridge = null;
        status = "Stop requested";
        motion.stopping();
        pendingEffect = RunMotionState.STOPPING;
        Thread.ofVirtual().name("vintelli-run-stop").start(() -> {
            try {
                disconnect(activeDebugSession);
                active.cancel();
            } catch (RuntimeException failure) {
                uiWork.execute(() -> status = "Could not stop run: " + message(failure));
            }
        });
    }

    private void failRun(String failure) {
        disconnectAsync(debugSession);
        debugSession = null;
        clearExecutionMarker();
        debugEditorBridge = null;
        motion.failed();
        pendingEffect = RunMotionState.FAILED;
        debugging = false;
        handle = null;
        status = failure;
        append(failure);
    }

    private void completeRun(long sequence, RunCapability.RunCompletion completion) {
        if (sequence != runSequence.get()) {
            return;
        }
        synchronized (output) {
            if (!completion.summary().equals(output.peekLast())) {
                append(completion.summary());
            }
        }
        motion.completed(completion);
        pendingEffect = motion.state();
        debugging = false;
        disconnectAsync(debugSession);
        debugSession = null;
        clearExecutionMarker();
        debugEditorBridge = null;
        handle = null;
        status = completion.summary();
    }

    private void requestDebugAction(String actionId) {
        DebugSession session = debugSession;
        if (session == null) {
            status = "No debug session is active";
            return;
        }
        DebugSessionSnapshot snapshot = session.snapshot();
        DebugCapability.SteppingAction action = actionId.equals(KeyActions.DEBUG_RESUME)
                ? DebugCapability.SteppingAction.RESUME
                : actionId.equals(KeyActions.DEBUG_PAUSE) ? DebugCapability.SteppingAction.PAUSE
                : actionId.equals(KeyActions.DEBUG_STEP_OVER) ? DebugCapability.SteppingAction.STEP_OVER
                : actionId.equals(KeyActions.DEBUG_STEP_INTO) ? DebugCapability.SteppingAction.STEP_INTO
                : DebugCapability.SteppingAction.STEP_OUT;
        if (debugActionPending.get()) {
            status = "A debugger action is already in progress";
            return;
        }
        boolean pauseProbe = action == DebugCapability.SteppingAction.PAUSE
                && !snapshot.capabilities().known(action);
        if (!snapshot.capabilities().supports(action) && !pauseProbe) {
            status = actionLabel(action) + " unavailable: unsupported by this adapter";
            return;
        }
        boolean requiresStopped = action != DebugCapability.SteppingAction.PAUSE;
        if (requiresStopped && snapshot.state() != DebugCapability.State.STOPPED) {
            status = actionLabel(action) + " unavailable: the session is not stopped";
            return;
        }
        if (!requiresStopped && snapshot.state() != DebugCapability.State.RUNNING) {
            status = "Pause unavailable: the session is not running";
            return;
        }
        if (pauseProbe) status = "Pause support is not yet confirmed; probing the adapter";
        else status = actionLabel(action);
        if (!debugActionPending.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("vintelli-debug-action").start(() -> {
            CapabilityResult<Boolean> result = invokeDebugAction(session, action);
            uiWork.execute(() -> {
                debugActionPending.set(false);
                if (debugSession != session) return;
                if (result instanceof CapabilityResult.Failed<Boolean> failed) {
                    status = actionLabel(action) + " failed: " + failed.failure().reason();
                } else {
                    status = actionLabel(action) + " requested";
                    if (action == DebugCapability.SteppingAction.RESUME) clearExecutionMarker();
                }
            });
        });
    }

    private static CapabilityResult<Boolean> invokeDebugAction(DebugSession session,
            DebugCapability.SteppingAction action) {
        try {
            return switch (action) {
                case RESUME -> session.resume();
                case PAUSE -> session.pause();
                case STEP_OVER -> session.stepOver();
                case STEP_INTO -> session.stepInto();
                case STEP_OUT -> session.stepOut();
            };
        } catch (RuntimeException failure) {
            return CapabilityResult.failed(new CapabilityFailure(message(failure), ""));
        }
    }

    private List<String> debugUnavailableActions() {
        DebugSession active = debugSession;
        if (active == null) return List.of();
        DebugSessionSnapshot snapshot = active.snapshot();
        List<String> unavailable = new ArrayList<>();
        for (DebugCapability.SteppingAction action : DebugCapability.SteppingAction.values()) {
            boolean stateAllows = action == DebugCapability.SteppingAction.PAUSE
                    ? snapshot.state() == DebugCapability.State.RUNNING
                    : snapshot.state() == DebugCapability.State.STOPPED;
            if (!stateAllows) {
                unavailable.add(actionLabel(action) + " unavailable: session is "
                        + snapshot.state().name().toLowerCase(Locale.ROOT));
            } else if (!snapshot.capabilities().supports(action)) {
                if (!snapshot.capabilities().known(action)) {
                    unavailable.add(actionLabel(action) + " support is not yet confirmed; action will probe the adapter");
                } else {
                    unavailable.add(actionLabel(action) + " unavailable: unsupported by this adapter");
                }
            } else if (debugActionPending.get()) {
                unavailable.add(actionLabel(action) + " unavailable: another debugger action is in progress");
            }
        }
        return unavailable;
    }

    private void watchDebugSession(long sequence, DebugSession session, EditorBridge editor) {
        Thread.ofVirtual().name("vintelli-debug-state").start(() -> {
            DebugCapability.DebugFrame applied = null;
            Optional<DebugCapability.ExceptionStop> announced = Optional.empty();
            while (sequence == runSequence.get() && debugSession == session) {
                DebugCapability.DebugSessionSnapshot snapshot = session.snapshot();
                Optional<DebugCapability.ExceptionStop> exception = snapshot.state() == DebugCapability.State.STOPPED
                        ? session.exceptionStop() : Optional.empty();
                if (!exception.equals(announced)) {
                    announced = exception;
                    exception.ifPresent(stop -> uiWork.execute(() -> {
                        if (debugSession == session) {
                            status = stop.summary();
                            stopAnnouncement.accept(stop.summary());
                        }
                    }));
                }
                DebugCapability.DebugFrame frame = snapshot.state() == DebugCapability.State.STOPPED
                        ? snapshot.currentFrame() : null;
                if (!Objects.equals(frame, applied)) {
                    applied = frame;
                    if (editor != null) {
                        uiWork.execute(() -> {
                            if (debugSession == session) editor.updateExecutionLocation(frame);
                        });
                    }
                }
                if (snapshot.state() == DebugCapability.State.TERMINATED
                        || snapshot.state() == DebugCapability.State.DISCONNECTED
                        || snapshot.state() == DebugCapability.State.FAILED) {
                    if (editor != null) uiWork.execute(() -> editor.updateExecutionLocation(null));
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    private void clearExecutionMarker() {
        EditorBridge editor = debugEditorBridge;
        if (editor != null) uiWork.execute(() -> editor.updateExecutionLocation(null));
    }

    private static String actionLabel(DebugCapability.SteppingAction action) {
        return switch (action) {
            case RESUME -> "Resume";
            case PAUSE -> "Pause";
            case STEP_OVER -> "Step over";
            case STEP_INTO -> "Step into";
            case STEP_OUT -> "Step out";
        };
    }

    private static void disconnect(DebugSession session) {
        if (session == null) {
            return;
        }
        try {
            session.disconnect();
        } catch (RuntimeException ignored) {
            // The run handle still owns process cleanup if the adapter has already disappeared.
        }
    }

    private static CapabilityResult<Map<String, List<DebugCapability.SourceBreakpoint>>> synchronizeBreakpoints(
            DebugCapability provider, EditorBridge editor) {
        while (true) {
            EditorBridge.SourceBreakpointSnapshot snapshot = editor == null
                    ? new EditorBridge.SourceBreakpointSnapshot(0, Map.of())
                    : editor.sourceBreakpointSnapshot();
            Map<String, List<DebugCapability.SourceBreakpoint>> requested = new java.util.TreeMap<>();
            snapshot.sources().forEach((source, points) -> requested.put(source.toString(), points));
            CapabilityResult<Map<String, List<DebugCapability.SourceBreakpoint>>> result =
                    provider.replaceBreakpoints(requested);
            if (result instanceof CapabilityResult.Failed<Map<String, List<DebugCapability.SourceBreakpoint>>>) {
                return result;
            }
            if (editor == null) {
                return result;
            }
            Map<Path, List<DebugCapability.SourceBreakpoint>> updates = new java.util.HashMap<>();
            ((CapabilityResult.Ok<Map<String, List<DebugCapability.SourceBreakpoint>>>) result).value()
                    .forEach((source, points) -> updates.put(Path.of(source), points));
            if (editor.updateSourceBreakpoints(snapshot.generation(), updates)) {
                return result;
            }
        }
    }

    private static void disconnectAsync(DebugSession session) {
        if (session != null) {
            Thread.ofVirtual().name("vintelli-debug-detach").start(() -> disconnect(session));
        }
    }

    private static boolean sameProvider(RunCapability first, RunCapability second) {
        return first == second;
    }

    private void append(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        synchronized (output) {
            output.addLast(line);
            while (output.size() > MAX_OUTPUT_LINES) {
                output.removeFirst();
            }
        }
    }

    private static String message(Exception failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    String statusMessage() {
        return status;
    }

    RunMotionState runState() {
        return motion.state();
    }

    String providerLabel() {
        return capabilitySummary.headline();
    }

    List<String> skippedProviderLines() {
        return capabilitySummary.skipped();
    }

    List<RunCapability.RunConfiguration> configurations() {
        return configurations;
    }

    int selectedIndex() {
        return selectedIndex;
    }

    Rect lastArea() {
        return lastArea;
    }

    List<String> consoleLines() {
        synchronized (output) {
            return List.copyOf(output);
        }
    }
}
