package dev.omatheusmesmo.vintelli.idea;

import io.smallrye.config.ConfigMapping;

import java.util.Optional;

/**
 * IDEA MCP connection settings. The URL has no default on purpose: IDEA picks
 * a free port for its MCP server and prints the endpoint on startup, so the
 * value always comes from "Copy HTTP Stream Config" in Settings | Tools | MCP
 * Server (or from the headless starter output).
 */
@ConfigMapping(prefix = "vintelli.idea.mcp")
public interface IdeaMcpConfig {

    Optional<String> url();

    Optional<String> projectPath();

    Optional<String> authToken();
}
