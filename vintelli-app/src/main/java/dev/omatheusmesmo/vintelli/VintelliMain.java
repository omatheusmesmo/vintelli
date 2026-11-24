package dev.omatheusmesmo.vintelli;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

import dev.omatheusmesmo.vintelli.cli.VintelliCliCommands;
import dev.omatheusmesmo.vintelli.cli.VintelliCliRepl;
import dev.omatheusmesmo.vintelli.launch.RunMode;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.omatheusmesmo.vintelli.tui.VintelliShell;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyboardMode;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapRegistry;
import dev.omatheusmesmo.vintelli.tui.terminal.TerminalBackground;
import dev.omatheusmesmo.vintelli.tui.terminal.TrackingJLineBackendProvider;
import dev.tamboui.tfx.toolkit.ToolkitEffects;
import dev.tamboui.toolkit.app.ToolkitPostRenderProcessor;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.TickEvent;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Singleton
public final class VintelliMain implements QuarkusApplication {

    /**
     * Idle redraw cadence. Every tick forces a full frame, so an idle shell at 4 frames per
     * second spends a visible share of a core redrawing an unchanged screen. Background work
     * that bypasses the UI work queue is only picked up on the next frame, so this bounds that
     * delay while the shell is idle; queued work wakes the loop at once through
     * {@link VintelliShell#bindRedraw}.
     */
    static final Duration IDLE_TICK = Duration.ofSeconds(1);
    /** The scheduler period is the smaller of the tick and this grace, so resize detection keeps its latency. */
    static final Duration RESIZE_CHECK = Duration.ofMillis(40);

    private final Instance<VintelliShell> shell;
    private final Instance<KeymapRegistry> keymapRegistry;
    private final Instance<ShellThemes> themes;
    private final VintelliCliCommands cliCommands;
    private final VintelliCliRepl cliRepl;
    private final boolean fileLogging;
    private final Optional<String> logFile;
    private final boolean terminalBackground;

    @Inject
    VintelliMain(Instance<VintelliShell> shell, Instance<KeymapRegistry> keymapRegistry,
            Instance<ShellThemes> themes, VintelliCliCommands cliCommands, VintelliCliRepl cliRepl,
            @ConfigProperty(name = "quarkus.log.file.enabled", defaultValue = "false") boolean fileLogging,
            @ConfigProperty(name = "quarkus.log.file.path") Optional<String> logFile,
            @ConfigProperty(name = TerminalBackground.CONFIG_PROPERTY, defaultValue = "true") boolean terminalBackground) {
        this.shell = shell;
        this.keymapRegistry = keymapRegistry;
        this.themes = themes;
        this.cliCommands = cliCommands;
        this.cliRepl = cliRepl;
        this.fileLogging = fileLogging;
        this.logFile = logFile;
        this.terminalBackground = terminalBackground;
    }

    private Optional<TerminalBackground> attachBackground(ToolkitRunner runner, ShellThemes shellThemes) {
        return TerminalBackground.attach(TerminalBackground.channel(runner.tuiRunner().backend()), shellThemes,
                terminalBackground, System.getenv(),
                TerminalBackground.consoleAttached() || TerminalBackground.recordedSession());
    }

    static final String BACKEND_PROPERTY = "tamboui.backend";
    static final String BACKEND_ENV = "TAMBOUI_BACKEND";
    static final String DEFAULT_BACKEND = TrackingJLineBackendProvider.NAME;

    static TuiConfig shellConfig(KeyboardMode keyboardMode) {
        return TuiConfig.defaults().toBuilder()
                .tickRate(IDLE_TICK)
                .resizeGracePeriod(RESIZE_CHECK)
                .keyboardProtocol(keyboardMode.protocol())
                .mouseCapture(true)
                .mouseMotion(true)
                .build();
    }

    static void selectDefaultBackend() {
        selectDefaultBackend(System::getProperty, System::getenv, System::setProperty);
    }

    static void selectDefaultBackend(Function<String, String> propertyReader,
            Function<String, String> envReader, BiConsumer<String, String> propertyWriter) {
        String configuredProperty = propertyReader.apply(BACKEND_PROPERTY);
        if (configuredProperty != null && !configuredProperty.isEmpty()) {
            return;
        }
        String configuredEnv = envReader.apply(BACKEND_ENV);
        if (configuredEnv != null && !configuredEnv.isEmpty()) {
            return;
        }
        propertyWriter.accept(BACKEND_PROPERTY, DEFAULT_BACKEND);
    }

    static String tuiFailure(String message, boolean fileLogging, Optional<String> logFile) {
        String line = "Vintelli cannot start the TUI: " + message;
        if (fileLogging && logFile.isPresent()) {
            return line + System.lineSeparator() + "Details are in the log file: " + logFile.get();
        }
        return line;
    }

    @Override
    public int run(String... args) throws Exception {
        List<String> arguments = Arrays.asList(args);
        RunMode mode = RunMode.of(arguments);
        if (mode == RunMode.TUI) {
            try {
                selectDefaultBackend();
                ShellThemes shellThemes = themes.get();
                VintelliShell tuiShell = shell.get();
                ToolkitEffects motionEffects = new ToolkitEffects();
                ToolkitPostRenderProcessor processMotionEffects = motionEffects.asPostRenderProcessor();
                tuiShell.bindMotionEffects(motionEffects);
                Keymap keymap = keymapRegistry.get().get();
                try (ToolkitRunner runner = ToolkitRunner.builder()
                        .postRenderProcessor((frame, elements, styledAreas, focus, elapsed) -> {
                            if (motionEffects.registry().isRunning()) {
                                processMotionEffects.process(frame, elements, styledAreas, focus, elapsed);
                            }
                        })
                        .config(shellConfig(keymap.keyboardMode()))
                        .bindings(keymap.bindings())
                        .styleEngine(shellThemes.engine())
                        .mouseCapture(true)
                        .faultTolerant(true)
                        .build()) {
                    runner.eventRouter().addGlobalHandler(tuiShell);
                    tuiShell.bindQuit(runner::quit);
                    tuiShell.bindRedraw(() -> runner.tuiRunner().dispatch(TickEvent.of(0)));
                    try (TerminalBackground background = attachBackground(runner, shellThemes).orElse(null)) {
                        runner.run(tuiShell::root);
                    }
                }
                Quarkus.asyncExit(0);
                return 0;
            } catch (RuntimeException failure) {
                System.err.println(tuiFailure(failure.getMessage(), fileLogging, logFile));
                Quarkus.asyncExit(2);
                return 2;
            }
        }
        return switch (mode) {
            case REPL -> cliRepl.run();
            case CLI -> cliCommands.execute(arguments);
            default -> {
                Quarkus.waitForExit();
                yield 0;
            }
        };
    }
}
