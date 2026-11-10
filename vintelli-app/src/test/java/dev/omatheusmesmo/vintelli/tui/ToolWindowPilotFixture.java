package dev.omatheusmesmo.vintelli.tui;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.app.ToolkitTestRunner;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.pilot.Pilot;

/**
 * Drives the assembled shell through TamboUI's headless runner and Pilot.
 * Pointer events enter the same TuiRunner queue as Pilot key and resize input.
 */
final class ToolWindowPilotFixture implements AutoCloseable {

    private static final Duration EVENT_WINDOW = Duration.ofMillis(500);

    private final VintelliShell shell;
    private final ToolkitTestRunner testRunner;
    private Size size;

    private ToolWindowPilotFixture(VintelliShell shell, ToolkitTestRunner testRunner, Size size) {
        this.shell = shell;
        this.testRunner = testRunner;
        this.size = size;
    }

    static ToolWindowPilotFixture open(VintelliShell shell, Size size) throws Exception {
        Objects.requireNonNull(shell, "shell");
        Objects.requireNonNull(size, "size");
        ToolkitTestRunner testRunner = ToolkitTestRunner.runTest(shell::root, size);
        testRunner.runner().styleEngine(shell.themes().engine());
        testRunner.runner().eventRouter().addGlobalHandler(shell);
        shell.bindQuit(testRunner.runner()::quit);
        return new ToolWindowPilotFixture(shell, testRunner, size);
    }

    Pilot pilot() {
        return testRunner.pilot();
    }

    void press(KeyCode key) {
        pilot().press(key);
        pilot().pause(EVENT_WINDOW);
    }

    void press(char key) {
        pilot().press(key);
        pilot().pause(EVENT_WINDOW);
    }

    void press(char key, KeyModifiers modifiers) {
        pilot().press(key, modifiers);
        pilot().pause(EVENT_WINDOW);
    }

    void press(KeyCode key, KeyModifiers modifiers) {
        pilot().press(key, modifiers);
        pilot().pause(EVENT_WINDOW);
    }

    ToolkitTestRunner testRunner() {
        return testRunner;
    }

    void scrollUp(int x, int y) {
        dispatch(MouseEvent.scrollUp(x, y));
    }

    void scrollDown(int x, int y) {
        dispatch(MouseEvent.scrollDown(x, y));
    }

    void resize(int width, int height) {
        pilot().resize(width, height);
        size = new Size(width, height);
        pilot().pause(Duration.ofMillis(60));
    }

    boolean await(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            renderedText();
            pilot().pause(Duration.ofMillis(10));
        }
        return condition.getAsBoolean();
    }

    String renderedText() {
        FutureTask<String> render = new FutureTask<>(() -> {
            Rect area = new Rect(0, 0, size.width(), size.height());
            Buffer buffer = Buffer.empty(area);
            testRunner.runner().eventRouter().clear();
            testRunner.runner().focusManager().clearFocusables();
            DefaultRenderContext context = new DefaultRenderContext(testRunner.runner().focusManager(),
                    testRunner.runner().eventRouter());
            context.setStyleEngine(shell.themes().engine());
            shell.root().render(Frame.forTesting(buffer), area, context);
            return text(buffer);
        });
        testRunner.runner().runOnRenderThread(render);
        try {
            return render.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while reading the rendered TUI buffer", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new AssertionError("Unable to read the rendered TUI buffer", failure);
        }
    }

    private void dispatch(MouseEvent event) {
        testRunner.runner().eventRouter().route(event);
        pilot().pause(Duration.ofMillis(60));
    }

    private static String text(Buffer buffer) {
        StringBuilder result = new StringBuilder();
        for (int row = 0; row < buffer.height(); row++) {
            for (int column = 0; column < buffer.width(); column++) {
                result.append(buffer.get(column, row).symbol());
            }
            result.append('\n');
        }
        return result.toString();
    }

    @Override
    public void close() throws Exception {
        testRunner.close();
    }
}
