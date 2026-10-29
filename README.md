# Vintelli

A terminal-native IDE experience for Java: IntelliJ intelligence, in your terminal, with zero IDE UI.

Vintelli is a Quarkus application that shows an IntelliJ IDEA-like TUI (built with TamboUI) and hosts AI coding agents (OpenCode, Claude Code, pi) through the Agent Client Protocol (ACP). It works standalone with open tooling only. IntelliJ IDEA, driven headlessly through its MCP server, is an optional provider and is never required.

## Architecture

```
+-----------------------------------------------------------+
|                    Vintelli (Quarkus TUI)                 |
|  menu bar | project tree | editor | tool windows | status |
+------------------+--------------------+-------------------+
| MCP Java SDK     | ACP client         | AEsh CLI commands |
| (client -> IDEA) | (opencode/claude/pi)| (REPL capabilities)|
+------------------+--------------------+-------------------+
        |                     |                    |
        v                     v                    v
  IntelliJ IDEA         agent processes      Vintelli REPL
  MCP Server (SSE/HTTP streamable)        + browser surface (websockets-next)
```

## Stack

- Java 25 (Mandrel 25.0.2, the Red Hat build of GraalVM for native images, pinned by `.sdkmanrc`)
- Quarkus 3.40.1.1 (build-time-first, no proxies/reflection unless required)
- TamboUI 0.6.0 (`tamboui-tui`, `tamboui-toolkit`, Markdown support, JLine backend)
- Quarkiverse MCP Server 2.0.4 (Vintelli as an MCP server for external agents)
- MCP Java SDK 2.1.0 (client: IntelliJ IDEA MCP server)
- AEsh 3.18.6 (plain library; CLI/REPL capabilities) and WebSockets Next (browser surface)

## Run

Backend (headless, dev mode with live reload):

```bash
sdk env            # honors .sdkmanrc (Java 25)
./mvnw -pl vintelli-app -am quarkus:dev
```

TUI (real terminal required):

```bash
./mvnw package
java -jar vintelli-app/target/quarkus-app/quarkus-run.jar --tui
```

The application lives in the `vintelli-app` module; the other modules are
plain libraries built along with it.

`vintelli-core` is a Quarkus extension (parent `vintelli-core-parent` with
`vintelli-core/runtime` and `vintelli-core/deployment`) and the home of
Vintelli's build-time glue. Its first content is
the MCP client support: CDI producers for the MCP SDK JSON mapper and schema
validator (the SDK's `ServiceLoader` lookup does not work in a native image)
and a build step that registers the `McpSchema` types for reflection. Only
`vintelli-idea` and `vintelli-app` depend on it; `vintelli-api` and
`vintelli-jsonrpc` stay Quarkus-free.

The tool window SPI lives in `vintelli-tui` because its render and key
handling signatures use TamboUI types: a `ToolWindow` declares an id, a
title, a `char` shortcut, an `ordinal` position, rendering and key
handling, implementations are CDI beans, and the shell needs no change when
a new window appears. The shell state read model stays in `vintelli-api`
(`ShellStateSnapshot` and `ShellStateView`) without TamboUI, so the MCP
server can read what the user is looking at, and the write ports
`ShellHighlightPort` and `ShellDiffViewPort` (implemented by the shell) let
it push a highlight and a diff back into the TUI.

Keys: `1-4` switch tool windows (Run, Terminal, Problems, ACP Agents),
`j/k` navigate the project tree, `q` quits.

## MCP server

Vintelli exposes an MCP server over streamable HTTP at `http://127.0.0.1:8080/mcp`
(`io.quarkiverse.mcp:quarkus-mcp-server-http`). The HTTP server binds the
loopback address by default (`quarkus.http.host=127.0.0.1`); set
`quarkus.http.host` if other machines or containers must reach it.

The HTTP CORS filter is enabled with `quarkus.http.cors.enabled` and no
`cors.origins` are configured, so the endpoint stays same-origin only and the
extension logs a startup warning about that policy. Browser-based clients are
therefore not welcome out of the box; set `quarkus.http.cors.origins` to their
origin if you need them. Non-browser clients (IDEA, CLI agents, `.mcp.json`
based tools) never send an origin and are unaffected.

The tools read and write the shell through the plain-Java ports in
`vintelli-api`: the read-only `ShellStateView` plus the write ports
`ShellHighlightPort` and `ShellDiffViewPort`. The TUI shell implements the
write ports and paints what was pushed into the editor panel on the next
frame, so no TamboUI type leaks into the MCP tools.

- `vintelli_state`: the selected tool window id, the selected file path and
  the agent session ids as JSON; the ids are always an empty list until the
  ACP client (issue #6) reports real sessions
- `vintelli_highlight`: pushes a `file` and a 1-based `line` to the TUI; the
  path must be relative to the project root and is rejected when absolute or
  using `..` segments
- `vintelli_diff_view`: renders a `title` and unified `diff` text in the
  editor panel; sizes are capped and control characters are stripped before
  rendering

`GET /tui/render` shows the same shell (tool window tab row, editor area,
status row) as JSON. It draws the live shell offscreen under the shell lock
and never routes keys from HTTP, but it is only meant for tests and local
debugging: it answers 404 unless `vintelli.inspect.enabled=true`.

### Configuring clients

Register the URL in your MCP client configuration with the streamable HTTP
transport. For IntelliJ IDEA, add the server in the MCP settings of the AI
plugin (AI Assistant or Junie), choose the `HTTP (Streamable)` type and the
URL `http://127.0.0.1:8080/mcp`.

Tools that follow the `.mcp.json` convention need a `vintelli` entry like:

```json
{
  "mcpServers": {
    "vintelli": {
      "type": "http",
      "url": "http://127.0.0.1:8080/mcp"
    }
  }
}
```

This repository carries its own `.mcp.json` at the root for the Quarkus agent,
which is unrelated to Vintelli, so merge the `vintelli` entry into your
config instead of replacing the file. The port above is the Quarkus default;
change it with `quarkus.http.port`.

## Native image

The project is kept reflection-free (final beans, no client proxies) so it is
native-ready. Verify with the `native` profile and Mandrel 25 from SDKMAN:

```bash
./mvnw package -Dnative
```

The native runner is written to `vintelli-app/target/`.

Integration tests (`*IT`) run against the native runner in CI with
`./mvnw verify -Dnative -Dquarkus.native.container-build`. `GET /tui/render`
renders one offscreen frame of the live shell and returns the tool window tab
row, the editor area and the status row as JSON; it exists so the render
pipeline can be validated on the native image and is gated behind
`vintelli.inspect.enabled` (read-only and offscreen, never driving the TUI
event loop).

`/idea/reconnect`, `/idea/tools` and `/idea/tool?name=get_...` expose the IDEA
MCP client over HTTP so the native ITs can drive it against an in-process twin
of the IDEA server. They answer 404 unless
`vintelli.idea.debug-endpoint.enabled=true` and only call read-only tools whose
name matches `get_[a-z_]+`.

## Status

See the GitHub issues for the implementation plan (IDEA MCP integration, ACP
agents, offline inspections, WebSocket surface, native smoke test).

## License

Vintelli is licensed under the [Apache License, Version 2.0](LICENSE).
