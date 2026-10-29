package dev.omatheusmesmo.vintelli.mcp;

import io.quarkiverse.mcp.server.Tool;
import jakarta.inject.Singleton;

@Singleton
public final class VintelliMcpTools {

    @Tool(description = "Returns the current state of the Vintelli TUI shell (selected tool window, selected file, status line).")
    public String vintelli_state() {
        return "vintelli mcp server is up";
    }
}
