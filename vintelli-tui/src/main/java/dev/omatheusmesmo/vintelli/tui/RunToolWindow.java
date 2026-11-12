package dev.omatheusmesmo.vintelli.tui;

import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugSession;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
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
    private volatile boolean debugging;
    private volatile RunCapability.DebugSupport debugSupport = new RunCapability.DebugSupport(false,
            "Select an available run configuration first");
    private String preferredConfiguration;
    private final ArrayDeque<String> output = new ArrayDeque<>();
    private final AtomicLong listingSequence = new AtomicLong();
    private final AtomicLong runSequence = new AtomicLong();
    private final AtomicLong capabilitySequence = new AtomicLong();
    private final AtomicBoolean capabilityRequested = new AtomicBoolean();
    private final boolean ideaConfigured;

    private volatile List<RunCapability.RunConfiguration> configurations = List.of();
    private volatile RunCapability provider;
    private volatile CapabilityStateLine.Summary capabilitySummary = CapabilityStateLine.checking(CapabilityType.RUN);
    private volatile String status = LOADING;
    private volatile RunCapability.RunHandle handle;
    private volatile DebugSession debugSession;
    private volatile boolean starting;
    private volatile boolean running;
    private int selectedIndex;
    private int configurationRowsStart = -1;
    private int visibleConfigurationStart;
    private int visibleConfigurationCount;
    private Rect lastArea = Rect.ZERO;

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

    @Inject
    public RunToolWindow(Provider<ShellThemes> themes, CapabilityRegistry capabilities, UiWorkQueue uiWork,
            @ConfigProperty(name = "vintelli.local.project-root", defaultValue = ".") String workingDirectory,
            RunSelectionStore selectionStore,
            @ConfigProperty(name = "vintelli.debug.port", defaultValue = "5005") int debugPort,
            @ConfigProperty(name = "vintelli.idea.mcp.url") Optional<String> ideaUrl) {
        this.debugPort = debugPort;
        this.themes = themes;
        this.capabilities = capabilities;
        this.uiWork = uiWork;
        this.workingDirectory = Path.of(workingDirectory).toAbsolutePath().normalize();
        this.selectionStore = selectionStore;
        this.preferredConfiguration = selectionStore == null ? null : selectionStore.load().orElse(null);
        this.ideaConfigured = CapabilityStateLine.ideaConfigured(ideaUrl);
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
        return PLAY_GLYPH;
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
        if (event.isKey(KeyCode.ENTER)) {
            return KeyActions.RUN_EXECUTE;
        }
        if (event.isCharIgnoreCase('r')) {
            return KeyActions.RUN_REFRESH;
        }
        if (event.isKey(KeyCode.DOWN) || event.isCharIgnoreCase('j')) {
            return KeyActions.RUN_SELECT_NEXT;
        }
        if (event.isKey(KeyCode.UP) || event.isCharIgnoreCase('k')) {
            return KeyActions.RUN_SELECT_PREVIOUS;
        }
        return null;
    }

    @Override
    public String actionForMouse(MouseEvent event) {
        if (!event.isClick() || !event.isLeftButton() || !insideLastArea(event)) {
            return null;
        }
        int row = event.y() - lastArea.y();
        int column = event.x() - lastArea.x();
        if (row == 1 && column >= 0 && column < 18) {
            return KeyActions.RUN_EXECUTE;
        }
        if (row == 1 && column >= 18 && column < 36) {
            return KeyActions.RUN_REFRESH;
        }
        if (row == 1 && column >= 36 && column < 56 && handle != null) {
            return handle.supportsCancellation() ? KeyActions.RUN_STOP : null;
        }
        return null;
    }

    @Override
    public boolean handleMouse(MouseEvent event) {
        if (!event.isClick() || !event.isLeftButton() || !insideLastArea(event)) {
            return false;
        }
        int row = event.y() - lastArea.y();
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
        if (capabilities != null && capabilityRequested.compareAndSet(false, true)) {
            refreshCapabilityLine();
        }
        lastArea = area;
        configurationRowsStart = -1;
        visibleConfigurationCount = 0;
        List<String> lines = new ArrayList<>();
        lines.add(capabilitySummary.headline());
        lines.add("▶ Run [Enter]     ↻ Refresh [r]     "
                + (handle == null || !handle.supportsCancellation()
                        ? "Stop unavailable" : "■ Stop"));
        lines.add(status);
        if (!debugSupport.available()) lines.add("Debug unavailable: " + debugSupport.reason());
        lines.addAll(capabilitySummary.skipped());

        int innerRows = Math.max(0, area.height());
        int bodyRows = Math.max(0, innerRows - lines.size());
        if (configurations.isEmpty()) {
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
        frame.renderWidget(Paragraph.builder()
                .text(Text.from(String.join("\n", lines)))
                .style(contentStyle)
                .build(), area);
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

    private void appendConsole(List<String> lines, int rows) {
        if (rows <= 0) {
            return;
        }
        lines.add("Console");
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
                append("Cannot save run selection: " + message(failure));
            }
        }
    }

    boolean debugAvailable() {
        return debugSupport.available() && !starting && !running;
    }

    boolean debugging() {
        return debugging && (starting || running);
    }

    private void refreshDebugSupport() {
        RunCapability current = provider;
        debugSupport = current == null || configurations.isEmpty() || selectedIndex >= configurations.size()
                ? new RunCapability.DebugSupport(false, "Select an available run configuration first")
                : current.debugSupport(configurations.get(selectedIndex).identifier());
    }

    /** True while a configuration runs; the main toolbar shows Stop in its Run slot then. */
    boolean running() {
        return handle != null;
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
        if (starting || running) {
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
        if (starting || running) {
            status = "A run is already active; stop it first";
            return;
        }
        startRun(identifier, arguments, false);
    }

    private void startRun(String identifier, List<String> arguments, boolean debug) {
        RunCapability selected = provider;
        if (selected == null) {
            status = "Run provider unavailable; refresh configurations";
            return;
        }
        long sequence = runSequence.incrementAndGet();
        starting = true;
        debugging = debug;
        running = true;
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
            uiWork.execute(() -> {
                if (sequence != runSequence.get()) {
                    disconnectAsync(session);
                    return;
                }
                starting = false;
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
                    running = true;
                    status = (debug ? "Debug 127.0.0.1:" + debugPort + " · " : "Running ") + identifier;
                }
            });
        });
    }

    private void stopCurrent() {
        RunCapability.RunHandle active = handle;
        if (active == null) {
            status = running ? "Run is starting; stop is not available yet" : "No run is active";
            return;
        }
        if (!active.supportsCancellation()) {
            status = "Stop is unavailable for this provider";
            return;
        }
        DebugSession activeDebugSession = debugSession;
        debugSession = null;
        status = "Stop requested";
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
        starting = false;
        running = false;
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
        starting = false;
        running = false;
        debugging = false;
        disconnectAsync(debugSession);
        debugSession = null;
        handle = null;
        status = completion.summary();
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
