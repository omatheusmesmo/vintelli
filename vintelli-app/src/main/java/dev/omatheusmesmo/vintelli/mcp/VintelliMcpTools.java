package dev.omatheusmesmo.vintelli.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.omatheusmesmo.vintelli.api.ShellDiffViewPort;
import dev.omatheusmesmo.vintelli.api.ShellHighlightPort;
import dev.omatheusmesmo.vintelli.api.ShellHighlightTarget;
import dev.omatheusmesmo.vintelli.api.ShellStateSnapshot;
import dev.omatheusmesmo.vintelli.api.ShellStateView;
import dev.omatheusmesmo.vintelli.api.ShellUnifiedDiff;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Map;

/**
 * MCP tools that expose and drive the TUI shell for external agents. Reads go
 * through the read-only {@link ShellStateView} port, pushes go through the
 * {@link ShellHighlightPort} and {@link ShellDiffViewPort} ports the shell
 * implements, so no TamboUI type reaches this surface.
 *
 * <p>{@code agentSessionIds} is always empty until the ACP client reports
 * real sessions (see issue #6); its wiring behind the state port is tracked
 * separately.
 */
@Singleton
public final class VintelliMcpTools {

    private final ShellStateView shellStateView;
    private final ShellHighlightPort highlightPort;
    private final ShellDiffViewPort diffViewPort;
    private final ObjectMapper jsonMapper;

    @Inject
    public VintelliMcpTools(ShellStateView shellStateView,
                            ShellHighlightPort highlightPort,
                            ShellDiffViewPort diffViewPort,
                            ObjectMapper jsonMapper) {
        this.shellStateView = shellStateView;
        this.highlightPort = highlightPort;
        this.diffViewPort = diffViewPort;
        this.jsonMapper = jsonMapper;
    }

    @Tool(description = "Returns the current state of the Vintelli TUI shell as JSON: the selected tool window id, the selected file path and the agent session ids (always empty until the ACP client exists).")
    public String vintelli_state() {
        ShellStateSnapshot snapshot = shellStateView.snapshot();
        return json(Map.of(
                "selectedToolWindowId", snapshot.selectedToolWindowId(),
                "selectedFile", snapshot.selectedFile(),
                "agentSessionIds", List.of()));
    }

    @Tool(description = "Pushes a file and a line to the Vintelli TUI shell so the human sees which source location the calling agent is working on. The file must be a relative path inside the project root and the line a 1-based line number.")
    public String vintelli_highlight(
            @ToolArg(name = "file", description = "Path of the file to highlight, relative to the project root", required = true) String file,
            @ToolArg(name = "line", description = "1-based line number inside the file", required = true) int line) {
        ShellHighlightTarget target = toTargetOrToolError(file, line);
        highlightPort.highlight(target);
        return json(Map.of(
                "status", "highlighted",
                "file", target.file(),
                "line", target.line()));
    }

    @Tool(description = "Renders a unified diff in the Vintelli TUI shell editor panel so the human can review a change an agent proposes. The diff text is capped at 256 KiB and control characters are stripped before rendering.")
    public String vintelli_diff_view(
            @ToolArg(name = "title", description = "Short label shown above the diff, for example the file or patch name", required = true) String title,
            @ToolArg(name = "diff", description = "Full unified diff text to render in the shell editor panel", required = true) String diff) {
        ShellUnifiedDiff unifiedDiff = toDiffOrToolError(title, diff);
        diffViewPort.show(unifiedDiff);
        return json(Map.of(
                "status", "diff-shown",
                "title", unifiedDiff.title()));
    }

    private static ShellHighlightTarget toTargetOrToolError(String file, int line) {
        try {
            return new ShellHighlightTarget(file, line);
        } catch (RuntimeException e) {
            throw new ToolCallException(message(e));
        }
    }

    private static ShellUnifiedDiff toDiffOrToolError(String title, String diff) {
        try {
            return new ShellUnifiedDiff(title, diff);
        } catch (RuntimeException e) {
            throw new ToolCallException(message(e));
        }
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? "invalid arguments" : e.getMessage();
    }

    private String json(Map<String, Object> payload) {
        try {
            return jsonMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new ToolCallException("failed to serialize the tool result", e);
        }
    }
}
