package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;

import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;

class AcpAgentPickerInteractionTest {

    @Test
    void keyboardAndPointerStartTheSameSelectedAgent() throws Exception {
        for (boolean pointer : List.of(false, true)) {
            PickerChat chat = new PickerChat(List.of(new AcpChatPort.Agent("opencode", "OpenCode", true, ""),
                    new AcpChatPort.Agent("pi", "pi", true, "")));
            try (ToolWindowPilotFixture fixture = fixture(chat)) {
                fixture.press('8', KeyModifiers.ALT);
                fixture.press(KeyCode.ENTER);
                assertTrue(fixture.renderedText().contains("Choose an agent"), fixture.renderedText());
                if (pointer) {
                    clickAgent(fixture, "pi (ready)");
                } else {
                    fixture.press(KeyCode.DOWN);
                    fixture.press(KeyCode.ENTER);
                }
                assertTrue(fixture.await(() -> !chat.opened.isEmpty(), Duration.ofSeconds(5)), fixture.renderedText());
                assertEquals("pi", chat.opened.poll());
                assertTrue(fixture.await(() -> fixture.renderedText().contains("pi 1"), Duration.ofSeconds(5)));
            }
        }
    }

    @Test
    void unavailableAgentShowsTheReasonAndEscapeReturnsToTheConversation() throws Exception {
        PickerChat chat = new PickerChat(List.of(new AcpChatPort.Agent("missing", "Missing", false,
                "Executable not found: missing-acp"), new AcpChatPort.Agent("pi", "pi", true, "")));
        try (ToolWindowPilotFixture fixture = fixture(chat)) {
            fixture.press('8', KeyModifiers.ALT);
            fixture.press(KeyCode.ENTER);
            fixture.press(KeyCode.ENTER);
            String unavailable = fixture.renderedText();
            assertTrue(unavailable.contains("Executable not found:") && unavailable.contains("missing-acp"), unavailable);
            assertTrue(chat.opened.isEmpty());
            fixture.press(KeyCode.ESCAPE);
            assertTrue(!fixture.renderedText().contains("Choose an agent"), fixture.renderedText());
        }
    }

    @Test
    void scrolledAgentRowsKeepTheirPointerMappingAfterResize() throws Exception {
        PickerChat chat = new PickerChat(IntStream.range(0, 12)
                .mapToObj(index -> new AcpChatPort.Agent("agent-" + index, "Agent " + index, true, "")).toList());
        try (ToolWindowPilotFixture fixture = fixture(chat)) {
            fixture.press('8', KeyModifiers.ALT);
            fixture.press(KeyCode.ENTER);
            for (int index = 0; index < 11; index++) fixture.press(KeyCode.DOWN);
            fixture.resize(90, 16);
            Rect area = fixture.pilot().findElement("tool-acp.agents");
            fixture.pilot().click(2, area.y() + 2);
            assertTrue(chat.opened.isEmpty());
            clickAgent(fixture, "Agent 11 (ready)");
            assertTrue(fixture.await(() -> !chat.opened.isEmpty(), Duration.ofSeconds(5)), fixture.renderedText());
            assertEquals("agent-11", chat.opened.poll());
        }
    }

    private static void clickAgent(ToolWindowPilotFixture fixture, String label) {
        String[] rows = fixture.renderedText().split("\n");
        for (int row = 0; row < rows.length; row++) {
            int column = rows[row].indexOf(label);
            if (column >= 0) {
                fixture.pilot().click(column, row);
                return;
            }
        }
        throw new AssertionError("Agent row missing: " + label + "\n" + fixture.renderedText());
    }

    private static ToolWindowPilotFixture fixture(PickerChat chat) throws Exception {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        UiWorkQueue queue = new UiWorkQueue();
        AcpAgentsToolWindow window = new AcpAgentsToolWindow(() -> themes, chat, queue);
        VintelliShell shell = new VintelliShell(ShellConfig.of(List.of(window), new ShellState(), themes));
        shell.useUiWorkQueue(queue);
        return ToolWindowPilotFixture.open(shell, new Size(120, 30));
    }

    private static final class PickerChat implements AcpChatPort {
        private final List<Agent> agents;
        private final LinkedBlockingQueue<String> opened = new LinkedBlockingQueue<>();

        private PickerChat(List<Agent> agents) { this.agents = agents; }
        public boolean isConfigured() { return true; }
        public List<Agent> agents() { return agents; }
        public String openSession(Consumer<Update> updates, Function<PermissionRequest, PermissionChoice> permissions) {
            throw new AssertionError("The picker must pass the selected agent id");
        }
        public String openSession(String agentId, Consumer<Update> updates, Function<PermissionRequest, PermissionChoice> permissions) {
            opened.add(agentId);
            return agentId;
        }
        public String prompt(String sessionId, String text) { return "end_turn"; }
        public void cancel(String sessionId) { }
        public void close(String sessionId) { }
    }
}
