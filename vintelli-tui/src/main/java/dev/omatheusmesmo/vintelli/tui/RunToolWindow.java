package dev.omatheusmesmo.vintelli.tui;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.paragraph.Paragraph;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/** Run configurations and their console, backed by the selected RunCapability. */
@Singleton
public final class RunToolWindow implements ToolWindow {

    private static final int MAX_OUTPUT_LINES = 500;
    private static final String LOADING = "Loading run configurations…";

    private final Provider<ShellThemes> themes;
    private final CapabilityRegistry capabilities;
    private final UiWorkQueue uiWork;
    private final Path workingDirectory;
    private final ArrayDeque<String> output = new ArrayDeque<>();
    private final AtomicLong listingSequence = new AtomicLong();
    private final AtomicLong runSequence = new AtomicLong();

    private volatile List<RunCapability.RunConfiguration> configurations = List.of();
    private volatile RunCapability provider;
    private volatile String providerLabel = "Run provider unavailable";
    private volatile String status = LOADING;
    private volatile RunCapability.RunHandle handle;
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
        this.status = "Run provider unavailable";
    }

    @Inject
    public RunToolWindow(Provider<ShellThemes> themes, CapabilityRegistry capabilities, UiWorkQueue uiWork,
            @ConfigProperty(name = "vintelli.local.project-root", defaultValue = ".") String workingDirectory) {
        this.themes = themes;
        this.capabilities = capabilities;
        this.uiWork = uiWork;
        this.workingDirectory = Path.of(workingDirectory).toAbsolutePath().normalize();
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
        return '1';
    }

    @Override
    public int ordinal() {
        return 0;
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
        int row = event.y() - lastArea.y() - 1;
        int column = event.x() - lastArea.x() - 1;
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
        int row = event.y() - lastArea.y() - 1;
        int index = visibleConfigurationStart + row - configurationRowsStart;
        if (configurationRowsStart >= 0 && row >= configurationRowsStart
                && row < configurationRowsStart + visibleConfigurationCount
                && index >= 0 && index < configurations.size()) {
            selectedIndex = index;
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
        if (KeyActions.RUN_EXECUTE.equals(actionId)) {
            startSelected();
            return true;
        }
        if (KeyActions.RUN_REFRESH.equals(actionId)) {
            refreshConfigurations();
            return true;
        }
        if (KeyActions.RUN_SELECT_NEXT.equals(actionId)) {
            selectedIndex = Math.min(Math.max(0, configurations.size() - 1), selectedIndex + 1);
            return true;
        }
        if (KeyActions.RUN_SELECT_PREVIOUS.equals(actionId)) {
            selectedIndex = Math.max(0, selectedIndex - 1);
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
        lastArea = area;
        configurationRowsStart = -1;
        visibleConfigurationCount = 0;
        List<String> lines = new ArrayList<>();
        lines.add("Provider: " + providerLabel);
        lines.add("▶ Run [Enter]     ↻ Refresh [r]     "
                + (handle == null || !handle.supportsCancellation()
                        ? "Stop unavailable" : "■ Stop"));
        lines.add(status);

        int innerRows = Math.max(0, area.height() - 2);
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
        Block.Builder block = Block.builder()
                .title(Title.from(Span.styled("Run", activeThemes.resolver("tool-window-title").toStyle())))
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .style(contentStyle);
        activeThemes.resolver("tool-window").borderColor().ifPresent(block::borderColor);
        frame.renderWidget(Paragraph.builder()
                .text(Text.from(String.join("\n", lines)))
                .style(contentStyle)
                .block(block.build())
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
        Thread.ofVirtual().name("vintelli-run-list").start(() -> {
            CapabilitySelection<RunCapability> selection;
            try {
                selection = capabilities.run();
            } catch (RuntimeException failure) {
                uiWork.execute(() -> {
                    if (sequence == listingSequence.get()) {
                        provider = null;
                        configurations = List.of();
                        providerLabel = "Run provider unavailable";
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
                        providerLabel = "Run provider unavailable";
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
                providerLabel = selected.identifier() + " · " + selected.provenance();
                if (result instanceof CapabilityResult.Failed<List<RunCapability.RunConfiguration>> failed) {
                    configurations = List.of();
                    status = "Run configuration listing failed: " + failed.failure().reason();
                    append(status);
                } else {
                    configurations = ((CapabilityResult.Ok<List<RunCapability.RunConfiguration>>) result).value();
                    selectedIndex = Math.min(selectedIndex, Math.max(0, configurations.size() - 1));
                    status = configurations.isEmpty() ? "No configurations found" : "Ready";
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
        providerLabel = selected.identifier() + " · " + selected.provenance();
        status = failure;
        append(failure);
    }

    private void startSelected() {
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
        RunCapability.RunConfiguration configuration = configurations.get(selectedIndex);
        RunCapability selected = provider;
        if (selected == null) {
            status = "Run provider unavailable; refresh configurations";
            return;
        }
        long sequence = runSequence.incrementAndGet();
        starting = true;
        running = true;
        handle = null;
        status = "Starting " + configuration.identifier();
        output.clear();
        append("Starting " + configuration.identifier() + " via " + selected.identifier());
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
                        providerLabel = "Run provider unavailable";
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
                        providerLabel = current.identifier() + " · " + current.provenance();
                        failRun("Run provider changed; refresh configurations");
                    }
                });
                return;
            }
            CapabilityResult<RunCapability.RunHandle> result;
            try {
                result = selected.start(
                        new RunCapability.RunRequest(configuration.identifier(), workingDirectory, Map.of()),
                        line -> uiWork.execute(() -> {
                            if (sequence == runSequence.get()) {
                                append(line);
                            }
                        }),
                        ended -> {
                            completion.set(ended);
                            uiWork.execute(() -> completeRun(sequence, ended));
                        });
            } catch (RuntimeException failure) {
                uiWork.execute(() -> {
                    if (sequence == runSequence.get()) {
                        failRun("Run failed: " + message(failure));
                    }
                });
                return;
            }
            uiWork.execute(() -> {
                if (sequence != runSequence.get()) {
                    return;
                }
                starting = false;
                if (result instanceof CapabilityResult.Failed<RunCapability.RunHandle> failed) {
                    failRun("Run failed: " + failed.failure().reason()
                            + (failed.failure().detail().isBlank() ? "" : " — " + failed.failure().detail()));
                    return;
                }
                if (completion.get() != null) {
                    completeRun(sequence, completion.get());
                } else {
                    handle = ((CapabilityResult.Ok<RunCapability.RunHandle>) result).value();
                    running = true;
                    status = "Running " + configuration.identifier();
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
        status = "Stop requested";
        Thread.ofVirtual().name("vintelli-run-stop").start(() -> {
            try {
                active.cancel();
            } catch (RuntimeException failure) {
                uiWork.execute(() -> status = "Could not stop run: " + message(failure));
            }
        });
    }

    private void failRun(String failure) {
        starting = false;
        running = false;
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
        handle = null;
        status = completion.summary();
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

    private static String message(RuntimeException failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    String statusMessage() {
        return status;
    }

    String providerLabel() {
        return providerLabel;
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
