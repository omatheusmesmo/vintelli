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
arrow keys navigate the project tree, `Ctrl+C` quits.

## Keymap

Every key is routed through named actions (IntelliJ action ids such as
`ActivateRunToolWindow` and `SearchEverywhere`) on top of TamboUI
`Bindings`, so a binding change is data, never code. Profiles: `intellij`
(default), `vim`; picked with the `vintelli.keymap` system property. A user
file `vintelli/keymap.properties` inside the config directory is layered on
top in the TamboUI properties format (`moveUp = Ctrl+k`): an action named in
the file loses the profile triggers and keeps exactly the listed ones, other
actions keep the profile bindings. The config directory is resolved from the
`vintelli.keymap.dir` property when set (absolute or relative, useful for
tests), then `XDG_CONFIG_HOME` (Linux, if absolute), then `APPDATA`
(Windows), then `$HOME/.config`. The user file is validated: unknown action
names fail fast naming the file and the line, a trigger bound to two
different actions fails fast, and actions that must stay reachable while an
input has focus (the palette, the help overlay, the tool window
activations) must keep at least one Ctrl/Alt/F-key chord; bare printable
characters belong to the capturing input.

The keymap is built only for the `--tui` session: headless and MCP-only
processes never read the file, and a malformed file fails the TUI startup
with a one-line message on stderr and exit code 2.

Key actions in the `intellij` profile:

| Action | Keys (active) | IntelliJ reference |
|---|---|---|
| `Activate<Name>ToolWindow` | `Alt+1`..`Alt+4`, or the bare tab shortcut (`1-4`) when no input has focus | `Alt+4` for Run, `Alt+F12` for Terminal |
| `SearchEverywhere` | `Ctrl+P` | `Double Shift` |
| `KeymapHelp` | `F1` | `F1` |
| `moveUp` / `moveDown` | arrow keys (`j/k` in the `vim` profile) | - |
| `quit` | `Ctrl+C` | - |

The command palette lists every action with its active chord and, when it
differs, the real IntelliJ shortcut, filters by label or action id, and runs
the picked action with `Enter`; the help overlay lists the active keymap
(source plus shortcuts). Both stay reachable while an input has focus. Bare
`q` and `Q` no longer quit in any profile (text input stays safe); `j/k`
are reserved for the `vim` profile and the registry never assigns them as
tool window shortcuts.

Chords a terminal cannot deliver, and their alternatives:

- **Double Shift** (Search Everywhere in IntelliJ): needs key release events
  and the Kitty keyboard protocol, which TamboUI 0.6.0 does not support. Use
  `Ctrl+P` to open the command palette.
- **`Ctrl+Shift+A`** (Find Action): `Ctrl+Shift+letter` sends the same bytes
  as `Ctrl+letter` in legacy mode. Use the command palette.
- **`Alt+F12`** (Terminal tool window in IntelliJ): bound in the profile as
  `Alt+2`, but `Alt+digit` is intercepted by default in tmux (`M-1`..`M-9`)
  and ghostty (tab switching). Not the only path: the bare digits still work
  when no input has focus and the palette reaches every action from anywhere.
- **Cmd/Super**: never reaches the application.
- **`Ctrl+I`, `Ctrl+M`, `Ctrl+J`, `Ctrl+H`, `Ctrl+[`**: the same bytes as Tab,
  Enter, Backspace and Escape; never given their own action.

While a tool window with text input claims the keyboard
(`ToolWindow.capturesInput()`), the shell keeps escape and the reserved
keymap chords for itself: `Ctrl+C` goes to the captured input, but
`Alt+<digit>`, `Ctrl+P` and `F1` still drive the shell, so switching never
relies on bare digit keys once an input has focus. Opening an overlay
releases the capture; after closing it the shell keeps the focus until the
tab is reselected.

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

`/acp/probe/session` and `/acp/probe/mcp` expose the ACP SDK client probe in
the same way (issue #132): they drive a full client session over stdio against
a tiny echo agent configured with
`vintelli.acp.probe.agent-command` and `vintelli.acp.probe.agent-args`, and
answer 404 unless `vintelli.acp.probe.enabled=true`.

## Status

See the GitHub issues for the implementation plan (IDEA MCP integration, ACP
agents, offline inspections, WebSocket surface, native smoke test).

## License

Vintelli is licensed under the [Apache License, Version 2.0](LICENSE).
