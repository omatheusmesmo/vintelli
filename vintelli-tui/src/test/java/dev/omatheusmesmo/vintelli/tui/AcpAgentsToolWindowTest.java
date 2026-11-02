package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;

import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort;
import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort.PermissionChoice;
import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort.PermissionRequest;
import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort.Update;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Size;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.style.Style;
import dev.tamboui.tui.pilot.Pilot;
import dev.tamboui.toolkit.app.ToolkitTestRunner;
import org.junit.jupiter.api.Test;

class AcpAgentsToolWindowTest {

    @Test
    void webSurfaceCannotStartAnAgentSession() throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        FakeChat chat = new FakeChat();
        AcpAgentsToolWindow window = new AcpAgentsToolWindow(() -> themes, chat, new UiWorkQueue());
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState(), themes);

        shell.handle(KeyEvent.ofKey(KeyCode.ENTER), ActionSurface.WEB);

        assertFalse(chat.opened.await(100, TimeUnit.MILLISECONDS), "web input must not spawn a configured agent");
    }

    @Test
    void pilotStreamsMarkdownAndDiffAndWaitsForThePermissionChoice() throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeChat chat = new FakeChat();
        AcpAgentsToolWindow window = new AcpAgentsToolWindow(() -> themes, chat, uiWork);
        AtomicReference<Buffer> rendered = new AtomicReference<>();
        window.observeRenderedBuffer(buffer -> rendered.set(buffer.copy()));
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState(), themes);
        shell.useUiWorkQueue(uiWork);
        Size size = new Size(120, 42);
        try (ToolkitTestRunner test = runShell(shell, size)) {
            Pilot pilot = test.pilot();
            pilot.press('4', KeyModifiers.ALT);
            assertEquals("tool-acp.agents", test.runner().focusManager().focusedId());
            pilot.press(KeyCode.ENTER);
            assertTrue(chat.opened.await(5, TimeUnit.SECONDS));
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("ACP session ready"));

            pilot.press("change the source");
            var area = pilot.findElement("tool-acp.agents");
            pilot.click(area.x() + area.width() - 5, area.y() + area.height() - 1);

            assertTrue(chat.permission.await(5, TimeUnit.SECONDS));
            assertFalse(chat.promptCompleted.await(100, TimeUnit.MILLISECONDS),
                    "the synchronous ACP permission handler must keep the prompt turn blocked");
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("Permission required"));
            var permissionArea = pilot.findElement("tool-acp.agents");
            int headerRow = findRow(rendered.get(), "Permission required:");
            pilot.click(permissionArea.x() + 4, headerRow);
            assertFalse(chat.promptCompleted.await(100, TimeUnit.MILLISECONDS),
                    "clicking the permission heading must not choose an option");
            String visible = screen(rendered);
            assertTrue(visible.contains("Hello agent"), visible);
            assertTrue(visible.contains("streamed"), visible);
            assertTrue(visible.contains("- old line"), visible);
            assertTrue(visible.contains("+ new line"), visible);
            assertDiffStyle(rendered.get(), "- old line", themes.resolver("editor-diff-removed").toStyle());
            assertDiffStyle(rendered.get(), "+ new line", themes.resolver("editor-diff-added").toStyle());

            pilot.press(KeyCode.ENTER);
            assertTrue(chat.promptCompleted.await(5, TimeUnit.SECONDS));
            assertEquals("allow-once", chat.choice.get().optionId());
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("Permission choice:")
                    && screen(rendered).contains("allow-once"));
        }
    }

    @Test
    void croppedPermissionChoicesKeepTheirExactMouseMapping() throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeChat chat = new FakeChat();
        chat.permissionOptions = IntStream.rangeClosed(1, 24)
                .mapToObj(index -> new AcpChatPort.PermissionOption("choice-" + index, "Choice " + index)).toList();
        AcpAgentsToolWindow window = new AcpAgentsToolWindow(() -> themes, chat, uiWork);
        AtomicReference<Buffer> rendered = new AtomicReference<>();
        window.observeRenderedBuffer(buffer -> rendered.set(buffer.copy()));
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState(), themes);
        shell.useUiWorkQueue(uiWork);
        Size size = new Size(120, 21);
        try (ToolkitTestRunner test = runShell(shell, size)) {
            Pilot pilot = test.pilot();
            pilot.press('4', KeyModifiers.ALT);
            pilot.press(KeyCode.ENTER);
            assertTrue(chat.opened.await(5, TimeUnit.SECONDS));
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("ACP session ready"));
            var area = pilot.findElement("tool-acp.agents");
            pilot.press('x');
            pilot.press(KeyCode.ENTER);
            assertTrue(chat.permissionRequests.poll(5, TimeUnit.SECONDS), "first permission was not requested");

            pauseUntil(pilot, rendered, size, () -> visibleChoices(rendered.get()) != null);
            List<VisibleChoice> visibleChoices = visibleChoices(rendered.get());
            assertTrue(screen(rendered).contains("Permission required:"),
                    "the crowded permission list should retain a non-actionable heading");
            for (VisibleChoice target : visibleChoices) {
                pilot.click(area.x() + 4, target.row());
                PermissionChoice choice = chat.choices.poll(5, TimeUnit.SECONDS);
                assertTrue(choice != null, "mouse click did not resolve the pending permission");
                if (target.index() == 24) {
                    assertTrue(choice.cancelled(), "Decline row must cancel the permission");
                } else {
                    assertEquals("choice-" + target.index(), choice.optionId(),
                            "visible Choice " + target.index() + " row selected the wrong option");
                }
                if (target != visibleChoices.getLast()) {
                    pauseUntil(pilot, rendered, size, () -> !screen(rendered).contains("Permission required:")
                            && screen(rendered).contains("[Enter] Send"));
                    pilot.press('x');
                    pilot.press(KeyCode.ENTER);
                    assertTrue(chat.permissionRequests.poll(5, TimeUnit.SECONDS), "next permission was not requested");
                    pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("Permission required:"));
                }
            }
        }
    }

    @Test
    void keyboardSelectionStaysVisibleAcrossCroppedPermissionChoices() throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeChat chat = new FakeChat();
        chat.permissionOptions = IntStream.rangeClosed(1, 24)
                .mapToObj(index -> new AcpChatPort.PermissionOption("choice-" + index, "Choice " + index)).toList();
        AcpAgentsToolWindow window = new AcpAgentsToolWindow(() -> themes, chat, uiWork);
        AtomicReference<Buffer> rendered = new AtomicReference<>();
        window.observeRenderedBuffer(buffer -> rendered.set(buffer.copy()));
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState(), themes);
        shell.useUiWorkQueue(uiWork);
        Size size = new Size(120, 21);
        try (ToolkitTestRunner test = runShell(shell, size)) {
            Pilot pilot = test.pilot();
            pilot.press('4', KeyModifiers.ALT);
            pilot.press(KeyCode.ENTER);
            assertTrue(chat.opened.await(5, TimeUnit.SECONDS));
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("ACP session ready"));
            pilot.press('x');
            pilot.press(KeyCode.ENTER);
            assertTrue(chat.permissionRequests.poll(5, TimeUnit.SECONDS));
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("› [1] Choice 1"));

            for (int index = 0; index < 19; index++) pilot.press(KeyCode.DOWN);
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("› [20] Choice 20"));
            pilot.press(KeyCode.UP);
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("› [19] Choice 19"));
            pilot.press(KeyCode.DOWN);
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("› [20] Choice 20"));
            pilot.press(KeyCode.ENTER);

            PermissionChoice choice = chat.choices.poll(5, TimeUnit.SECONDS);
            assertTrue(choice != null, "Enter did not resolve the selected permission");
            assertEquals("choice-20", choice.optionId());

            pauseUntil(pilot, rendered, size, () -> !screen(rendered).contains("Permission required:")
                            && screen(rendered).contains("[Enter] Send"));
            pilot.press('x');
            pilot.press(KeyCode.ENTER);
            assertTrue(chat.permissionRequests.poll(5, TimeUnit.SECONDS));
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("Permission required:"));
            for (int index = 0; index < 24; index++) pilot.press(KeyCode.DOWN);
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("› [0] Decline"));
            var area = pilot.findElement("tool-acp.agents");
            pilot.click(area.x() + 4, findRow(rendered.get(), "› [0] Decline"));
            PermissionChoice decline = chat.choices.poll(5, TimeUnit.SECONDS);
            assertTrue(decline != null && decline.cancelled(), "clicking the scrolled Decline row must cancel");
        }
    }

    @Test
    void closingWhileOpenIsPendingDoesNotCloseTheLateSessionOnTheRenderThread() throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        UiWorkQueue uiWork = new UiWorkQueue();
        BlockingOpenChat chat = new BlockingOpenChat();
        AcpAgentsToolWindow window = new AcpAgentsToolWindow(() -> themes, chat, uiWork);
        AtomicReference<Buffer> rendered = new AtomicReference<>();
        AtomicReference<Thread> renderThread = new AtomicReference<>();
        window.observeRenderedBuffer(buffer -> {
            rendered.set(buffer.copy());
            renderThread.compareAndSet(null, Thread.currentThread());
        });
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState(), themes);
        shell.useUiWorkQueue(uiWork);
        Size size = new Size(120, 24);
        try (ToolkitTestRunner test = runShell(shell, size)) {
            Pilot pilot = test.pilot();
            pilot.press('4', KeyModifiers.ALT);
            pilot.press(KeyCode.ENTER);
            assertTrue(chat.opening.await(5, TimeUnit.SECONDS));
            window.shutdown();
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("Chat 1"));
            chat.releaseOpen.countDown();
            assertTrue(chat.closed.await(5, TimeUnit.SECONDS));
            assertFalse(chat.closeThread.get() == renderThread.get(), "SDK session close must run off the render thread");
            assertEquals(1, chat.closeCount);
        }
    }

    @Test
    void sessionReadyPostedAfterTheLastFrameIsDrawnWithoutFurtherInput() throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        UiWorkQueue uiWork = new UiWorkQueue();
        BlockingOpenChat chat = new BlockingOpenChat();
        AcpAgentsToolWindow window = new AcpAgentsToolWindow(() -> themes, chat, uiWork);
        AtomicReference<Buffer> rendered = new AtomicReference<>();
        window.observeRenderedBuffer(buffer -> rendered.set(buffer.copy()));
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState(), themes);
        shell.useUiWorkQueue(uiWork);
        Size size = new Size(120, 24);
        try (ToolkitTestRunner test = runShell(shell, size)) {
            Pilot pilot = test.pilot();
            pilot.press('4', KeyModifiers.ALT);
            pilot.press(KeyCode.ENTER);
            assertTrue(chat.opening.await(5, TimeUnit.SECONDS));
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("Chat 1"));
            assertFalse(screen(rendered).contains("ACP session ready"));
            chat.releaseOpen.countDown();
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("ACP session ready"));
        }
    }

    @Test
    void noRoundedFrameIsDrawnAndTheTabsSitUnderThePaneHeader() throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        UiWorkQueue uiWork = new UiWorkQueue();
        AcpAgentsToolWindow window = new AcpAgentsToolWindow(() -> themes, new FakeChat(), uiWork);
        AtomicReference<Buffer> rendered = new AtomicReference<>();
        window.observeRenderedBuffer(buffer -> rendered.set(buffer.copy()));
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState(), themes);
        shell.useUiWorkQueue(uiWork);
        Size size = new Size(120, 30);
        try (ToolkitTestRunner test = runShell(shell, size)) {
            Pilot pilot = test.pilot();
            pilot.press('4', KeyModifiers.ALT);
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("+ New"));

            String visible = screen(rendered);
            assertFalse(visible.contains("╭"), visible);
            assertFalse(visible.contains("╰"), visible);
            assertEquals(findRow(rendered.get(), "ACP Agents") + 1, findRow(rendered.get(), "+ New"),
                    "the session tabs must sit directly under the shell pane header\n" + visible);
        }
    }

    @Test
    void agentTextCannotInjectTerminalControlSequences() throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeChat chat = new FakeChat();
        chat.agentMessage = "before\u001b]0;pwned\u0007after \u009b31mred";
        chat.toolTitle = "Edit\u001b[2Jsource";
        chat.permissionTitle = "R\u001b[3mx";
        AcpAgentsToolWindow window = new AcpAgentsToolWindow(() -> themes, chat, uiWork);
        AtomicReference<Buffer> rendered = new AtomicReference<>();
        window.observeRenderedBuffer(buffer -> rendered.set(buffer.copy()));
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState(), themes);
        shell.useUiWorkQueue(uiWork);
        Size size = new Size(120, 42);
        try (ToolkitTestRunner test = runShell(shell, size)) {
            Pilot pilot = test.pilot();
            pilot.press('4', KeyModifiers.ALT);
            pilot.press(KeyCode.ENTER);
            assertTrue(chat.opened.await(5, TimeUnit.SECONDS));
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("ACP session ready"));
            pilot.press("go");
            pilot.press(KeyCode.ENTER);
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("Permission required:"));

            String visible = screen(rendered);
            assertTrue(visible.contains("pwned"), visible);
            assertTrue(visible.contains("R [3mx"), visible);
            assertTrue(visible.contains("Edit [2Jsource"), visible);
            visible.codePoints().filter(codePoint -> codePoint != '\n').forEach(codePoint ->
                    assertFalse(Character.isISOControl(codePoint), "control character U+" + Integer.toHexString(codePoint)
                            + " reached the cell buffer\n" + visible));
        }
    }

    @Test
    void cancellingTheTurnAnswersAPendingPermissionInsteadOfLeavingTheAgentBlocked() throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeChat chat = new FakeChat();
        AcpAgentsToolWindow window = new AcpAgentsToolWindow(() -> themes, chat, uiWork);
        AtomicReference<Buffer> rendered = new AtomicReference<>();
        window.observeRenderedBuffer(buffer -> rendered.set(buffer.copy()));
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState(), themes);
        shell.useUiWorkQueue(uiWork);
        Size size = new Size(120, 42);
        try (ToolkitTestRunner test = runShell(shell, size)) {
            Pilot pilot = test.pilot();
            pilot.press('4', KeyModifiers.ALT);
            pilot.press(KeyCode.ENTER);
            assertTrue(chat.opened.await(5, TimeUnit.SECONDS));
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("ACP session ready"));
            pilot.press("go");
            pilot.press(KeyCode.ENTER);
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("Permission required:"));

            var area = pilot.findElement("tool-acp.agents");
            pilot.click(area.x() + area.width() - 5, area.y() + area.height() - 1);

            PermissionChoice choice = chat.choices.poll(5, TimeUnit.SECONDS);
            assertTrue(choice != null && choice.cancelled(), "cancelling the turn must decline the pending permission");
            assertEquals("session-1", chat.cancelled.poll(5, TimeUnit.SECONDS));
            pauseUntil(pilot, rendered, size, () -> !screen(rendered).contains("Permission required:"));
        }
    }

    @Test
    void simultaneousPermissionRequestsAreAnsweredOneAfterTheOther() throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        UiWorkQueue uiWork = new UiWorkQueue();
        ParallelPermissionChat chat = new ParallelPermissionChat();
        AcpAgentsToolWindow window = new AcpAgentsToolWindow(() -> themes, chat, uiWork);
        AtomicReference<Buffer> rendered = new AtomicReference<>();
        window.observeRenderedBuffer(buffer -> rendered.set(buffer.copy()));
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState(), themes);
        shell.useUiWorkQueue(uiWork);
        Size size = new Size(120, 42);
        try (ToolkitTestRunner test = runShell(shell, size)) {
            Pilot pilot = test.pilot();
            pilot.press('4', KeyModifiers.ALT);
            pilot.press(KeyCode.ENTER);
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("ACP session ready"));
            pilot.press("go");
            pilot.press(KeyCode.ENTER);
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("Permission required:"));
            pilot.press(KeyCode.ENTER);
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("Permission selected:"));
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("Permission required:"));
            pilot.press(KeyCode.ENTER);

            assertTrue(chat.bothAnswered.await(5, TimeUnit.SECONDS),
                    "the second simultaneous permission request was never answered");
            assertEquals(2, chat.answers.size());
            assertTrue(chat.answers.stream().noneMatch(PermissionChoice::cancelled));
        }
    }

    @Test
    void anOutOfRangePermissionDigitDoesNotDeclineTheRequest() throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeChat chat = new FakeChat();
        AcpAgentsToolWindow window = new AcpAgentsToolWindow(() -> themes, chat, uiWork);
        AtomicReference<Buffer> rendered = new AtomicReference<>();
        window.observeRenderedBuffer(buffer -> rendered.set(buffer.copy()));
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState(), themes);
        shell.useUiWorkQueue(uiWork);
        Size size = new Size(120, 42);
        try (ToolkitTestRunner test = runShell(shell, size)) {
            Pilot pilot = test.pilot();
            pilot.press('4', KeyModifiers.ALT);
            pilot.press(KeyCode.ENTER);
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("ACP session ready"));
            pilot.press("go");
            pilot.press(KeyCode.ENTER);
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("Permission required:"));

            pilot.press('7');
            assertTrue(chat.choices.poll(300, TimeUnit.MILLISECONDS) == null,
                    "a digit without a matching option must leave the permission pending");
            pilot.press('1');
            PermissionChoice choice = chat.choices.poll(5, TimeUnit.SECONDS);
            assertTrue(choice != null && !choice.cancelled());
            assertEquals("allow-once", choice.optionId());
        }
    }

    @Test
    void anUnansweredPermissionIsDeclinedAndReportedAfterTheTimeout() throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeChat chat = new FakeChat();
        AcpAgentsToolWindow window = new AcpAgentsToolWindow(() -> themes, chat, uiWork);
        window.permissionTimeout(Duration.ofMillis(300));
        AtomicReference<Buffer> rendered = new AtomicReference<>();
        window.observeRenderedBuffer(buffer -> rendered.set(buffer.copy()));
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState(), themes);
        shell.useUiWorkQueue(uiWork);
        Size size = new Size(120, 42);
        try (ToolkitTestRunner test = runShell(shell, size)) {
            Pilot pilot = test.pilot();
            pilot.press('4', KeyModifiers.ALT);
            pilot.press(KeyCode.ENTER);
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("ACP session ready"));
            pilot.press("go");
            pilot.press(KeyCode.ENTER);

            PermissionChoice choice = chat.choices.poll(5, TimeUnit.SECONDS);
            assertTrue(choice != null && choice.cancelled(), "an unanswered permission must be declined");
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("timed out")
                    && !screen(rendered).contains("Permission required:"));
        }
    }

    @Test
    void clickingAnUncroppedPermissionRowSelectsThatOption() throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        UiWorkQueue uiWork = new UiWorkQueue();
        FakeChat chat = new FakeChat();
        chat.permissionOptions = List.of(new AcpChatPort.PermissionOption("allow-once", "Allow once"),
                new AcpChatPort.PermissionOption("allow-always", "Allow always"),
                new AcpChatPort.PermissionOption("reject-once", "Reject once"));
        AcpAgentsToolWindow window = new AcpAgentsToolWindow(() -> themes, chat, uiWork);
        AtomicReference<Buffer> rendered = new AtomicReference<>();
        window.observeRenderedBuffer(buffer -> rendered.set(buffer.copy()));
        VintelliShell shell = new VintelliShell(List.of(window), new ShellState(), themes);
        shell.useUiWorkQueue(uiWork);
        Size size = new Size(120, 42);
        try (ToolkitTestRunner test = runShell(shell, size)) {
            Pilot pilot = test.pilot();
            pilot.press('4', KeyModifiers.ALT);
            pilot.press(KeyCode.ENTER);
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("ACP session ready"));
            pilot.press("go");
            pilot.press(KeyCode.ENTER);
            pauseUntil(pilot, rendered, size, () -> screen(rendered).contains("[3] Reject once"));

            var area = pilot.findElement("tool-acp.agents");
            pilot.click(area.x() + 4, findRow(rendered.get(), "[2] Allow always"));

            PermissionChoice choice = chat.choices.poll(5, TimeUnit.SECONDS);
            assertTrue(choice != null, "the click did not resolve the permission");
            assertEquals("allow-always", choice.optionId());
        }
    }

    private static ToolkitTestRunner runShell(VintelliShell shell, Size size) throws Exception {
        ToolkitTestRunner test = ToolkitTestRunner.runTest(shell::root, size);
        test.runner().styleEngine(shell.themes().engine());
        test.runner().eventRouter().addGlobalHandler(shell);
        shell.bindQuit(test.runner()::quit);
        return test;
    }

    private static void pauseUntil(Pilot pilot, AtomicReference<Buffer> rendered, Size size,
            BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            pilot.resize(size);
            pilot.pause(Duration.ofMillis(30));
        }
        assertTrue(condition.getAsBoolean(), "the TamboUI pilot render did not reach the expected state:\n"
                + screen(rendered));
    }

    private static String screen(AtomicReference<Buffer> captured) {
        Buffer buffer = captured.get();
        if (buffer == null) return "";
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < buffer.height(); row++) {
            for (int column = 0; column < buffer.width(); column++) text.append(buffer.get(column, row).symbol());
            text.append('\n');
        }
        return text.toString();
    }

    private static int findRow(Buffer buffer, String text) {
        for (int row = 0; row < buffer.height(); row++) {
            StringBuilder line = new StringBuilder();
            for (int column = 0; column < buffer.width(); column++) line.append(buffer.get(column, row).symbol());
            if (line.toString().contains(text)) return row;
        }
        throw new AssertionError("rendered buffer did not contain: " + text);
    }

    private static List<VisibleChoice> visibleChoices(Buffer buffer) {
        if (buffer == null) return null;
        List<VisibleChoice> choices = new ArrayList<>();
        int headerRow = -1;
        for (int row = 0; row < buffer.height(); row++) {
            StringBuilder line = new StringBuilder();
            for (int column = 0; column < buffer.width(); column++) line.append(buffer.get(column, row).symbol());
            if (line.toString().contains("Permission required:")) {
                headerRow = row;
                break;
            }
        }
        if (headerRow < 0) return null;
        for (int row = headerRow + 1; row < buffer.height(); row++) {
            StringBuilder line = new StringBuilder();
            for (int column = 0; column < buffer.width(); column++) line.append(buffer.get(column, row).symbol());
            int bracket = line.indexOf("[");
            int close = bracket < 0 ? -1 : line.indexOf("]", bracket);
            if (bracket >= 0 && close > bracket + 1) {
                try {
                    choices.add(new VisibleChoice("0".equals(line.substring(bracket + 1, close))
                            ? 24 : Integer.parseInt(line.substring(bracket + 1, close)), row));
                } catch (NumberFormatException ignored) { }
            }
        }
        return choices.isEmpty() ? null : choices;
    }

    private static void assertDiffStyle(Buffer buffer, String text, Style style) {
        for (int row = 0; row < buffer.height(); row++) {
            StringBuilder line = new StringBuilder();
            for (int column = 0; column < buffer.width(); column++) line.append(buffer.get(column, row).symbol());
            int column = line.indexOf(text);
            if (column >= 0) {
                assertEquals(style.fg(), buffer.get(column, row).style().fg(), line.toString());
                return;
            }
        }
        throw new AssertionError("rendered buffer did not contain diff line: " + text);
    }

    private static final class FakeChat implements AcpChatPort {
        private final CountDownLatch opened = new CountDownLatch(1);
        private final CountDownLatch permission = new CountDownLatch(1);
        private final CountDownLatch promptCompleted = new CountDownLatch(1);
        private final AtomicReference<PermissionChoice> choice = new AtomicReference<>();
        private final LinkedBlockingQueue<PermissionChoice> choices = new LinkedBlockingQueue<>();
        private final LinkedBlockingQueue<Boolean> permissionRequests = new LinkedBlockingQueue<>();
        private List<AcpChatPort.PermissionOption> permissionOptions =
                List.of(new AcpChatPort.PermissionOption("allow-once", "Allow once"));
        private final LinkedBlockingQueue<String> cancelled = new LinkedBlockingQueue<>();
        private volatile String agentMessage = "Hello **agent**.\n\n- streamed";
        private volatile String toolTitle = "Edit source";
        private volatile String permissionTitle = "Run tool";
        private Consumer<Update> updates;
        private Function<PermissionRequest, PermissionChoice> permissionHandler;

        @Override public boolean isConfigured() { return true; }

        @Override
        public String openSession(Consumer<Update> updates,
                Function<PermissionRequest, PermissionChoice> permissionHandler) {
            this.updates = updates;
            this.permissionHandler = permissionHandler;
            opened.countDown();
            return "session-1";
        }

        @Override
        public String prompt(String sessionId, String text) {
            updates.accept(new AcpChatPort.Message(agentMessage));
            updates.accept(new AcpChatPort.ToolCall("tool-1", toolTitle, "edit_file", "completed",
                    "--- a/Main.java\n+++ b/Main.java\n- old line\n+ new line\n"));
            permission.countDown();
            permissionRequests.offer(true);
            PermissionChoice selected = permissionHandler.apply(new PermissionRequest("tool-2", permissionTitle, permissionOptions));
            choice.set(selected);
            choices.offer(selected);
            updates.accept(new AcpChatPort.Message("Permission choice: " + selected.optionId()));
            promptCompleted.countDown();
            return "end_turn";
        }

        @Override public void cancel(String sessionId) { cancelled.offer(sessionId); }
        @Override public void close(String sessionId) { }
    }

    private record VisibleChoice(int index, int row) { }

    private static final class BlockingOpenChat implements AcpChatPort {
        private final CountDownLatch opening = new CountDownLatch(1);
        private final CountDownLatch releaseOpen = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicReference<Thread> closeThread = new AtomicReference<>();
        private volatile int closeCount;

        @Override public boolean isConfigured() { return true; }
        @Override public String openSession(Consumer<Update> updates,
                Function<PermissionRequest, PermissionChoice> permissionHandler) {
            opening.countDown();
            awaitRelease();
            return "late-session";
        }

        private void awaitRelease() {
            boolean interrupted = false;
            try {
                while (releaseOpen.getCount() > 0) {
                    try {
                        if (!releaseOpen.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("open not released");
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        @Override public String prompt(String sessionId, String text) { return "end_turn"; }
        @Override public void cancel(String sessionId) { }
        @Override public void close(String sessionId) {
            closeThread.set(Thread.currentThread());
            closeCount++;
            closed.countDown();
        }
    }

    private static final class ParallelPermissionChat implements AcpChatPort {
        private final CountDownLatch bothAnswered = new CountDownLatch(2);
        private final List<PermissionChoice> answers = Collections.synchronizedList(new ArrayList<>());
        private Function<PermissionRequest, PermissionChoice> permissionHandler;

        @Override public boolean isConfigured() { return true; }

        @Override
        public String openSession(Consumer<Update> updates, Function<PermissionRequest, PermissionChoice> handler) {
            this.permissionHandler = handler;
            return "session-1";
        }

        @Override
        public String prompt(String sessionId, String text) {
            List<AcpChatPort.PermissionOption> options = List.of(new AcpChatPort.PermissionOption("allow-once", "Allow once"));
            try (ExecutorService callbacks = Executors.newFixedThreadPool(2)) {
                List<Future<?>> requests = List.of("First", "Second").stream().<Future<?>>map(title -> callbacks.submit(() -> {
                    answers.add(permissionHandler.apply(new PermissionRequest(title, title + " tool", options)));
                    bothAnswered.countDown();
                })).toList();
                for (Future<?> request : requests) {
                    try {
                        request.get(10, TimeUnit.SECONDS);
                    } catch (Exception unanswered) {
                        callbacks.shutdownNow();
                        throw new IllegalStateException(unanswered);
                    }
                }
            }
            return "end_turn";
        }

        @Override public void cancel(String sessionId) { }
        @Override public void close(String sessionId) { }
    }
}
