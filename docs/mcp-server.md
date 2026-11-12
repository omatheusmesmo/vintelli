# MCP server

The MCP endpoint, the Host and Origin guard, the browser terminal and how to configure MCP clients.

Vintelli exposes an MCP server over streamable HTTP at `http://127.0.0.1:8080/mcp`
(`io.quarkiverse.mcp:quarkus-mcp-server-http`). The headless `serve` command
keeps that port. The TUI, CLI and REPL processes bind a free loopback port
instead, so several instances run side by side; pin one with
`quarkus.http.port` (for example `-Dquarkus.http.port=8080`), and a pinned port
that is already taken makes the process exit with status 1 and a message on
stderr. The HTTP server binds the loopback address by default
(`quarkus.http.host=127.0.0.1`); set `quarkus.http.host` if other machines or
containers must reach it.

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
  the opaque IDs of currently connected ACP agent sessions as JSON
- `vintelli_highlight`: pushes a `file` and a 1-based `line` to the TUI; the
  path must be relative to the project root and is rejected when absolute or
  using `..` segments
- `vintelli_diff_view`: renders a `title` and unified `diff` text in the
  editor panel; sizes are capped and control characters are stripped before
  rendering

## Finding the instance for a project

Once its HTTP server listens, every instance writes one record,
`instances/<pid>.json`, under the per-user state directory: `$XDG_STATE_HOME/vintelli`
on Linux, `~/Library/Application Support/vintelli` on macOS and
`%LOCALAPPDATA%\vintelli` on Windows. The record holds the process id, the bound
port, the host and the canonical `vintelli.local.project-root`. It contains no
secrets, is created owner-only where the platform supports it, is replaced
atomically, and is removed when the instance exits.

A client picks the instance for its project by reading the records and taking
the live one whose project root matches; its MCP endpoint is
`http://<host>:<port>/mcp`. A record whose process is gone is stale: it is
ignored and deleted when read, and a new start for the same process id replaces
it. When several live instances own the same root, the most recently started one
wins. Because a process id can be reused by an unrelated process, a client should
confirm the endpoint with an MCP `initialize` before it trusts a record.

Pinning `quarkus.http.port` stays the explicit override: the pinned port is
written to the record as well, so a client that looks the project up finds the
pinned instance the same way.

## Host and Origin guard

A loopback bind does not stop DNS rebinding: a web page served from a hostile
name that resolves to `127.0.0.1` can still reach the local endpoints from
the user's browser, with `Host` and `Origin` set to the hostile name. A
Vert.x guard (`McpEndpointGuard` in `vintelli-core`) closes this vector: it
rejects requests whose `Host` header is not a loopback name or the configured
host, and whose `Origin` header is present and not on the allowed list, with
403 and before anything routes to a tool. It checks every request, whatever
the path spelling, so the MCP endpoint (`/mcp`), the inspect endpoint
(`/tui/render`) and every other route get the same policy. Requests without
an `Origin` header are never rejected for that reason, so MCP clients such as
IntelliJ IDEA and CLI agents keep working.

- `vintelli.mcp.allowed-hosts`: the trusted `Host` values, default
  `localhost`, `127.0.0.1` and `[::1]` plus the configured `quarkus.http.host`;
  the match ignores the port and IPv6 brackets
- `vintelli.mcp.allowed-origins`: the trusted browser `Origin` values, default
  empty, so no browser origin is accepted out of the box

The origin check that `quarkus-mcp-server-http` offers
(`quarkus.mcp.server.http.dns-rebinding-check.enabled`) is turned off in
`application.properties` because it has no configurable origin list. The
global Quarkus host validation (`quarkus.http.host-validation.require-localhost`
and its `allowed-hosts` list) is turned off too: it answers 400 and would
duplicate the host list above, so this guard is the single host policy and
answers 403. To accept a browser origin end to end, add it to both
`vintelli.mcp.allowed-origins` (reach the endpoint) and
`quarkus.http.cors.origins` (readable responses).

`GET /tui/render` shows the same shell (tool window tab row, editor area,
status row and active theme) as JSON, plus the color depth the session renders
with in `colorLevel` and, in `colors`, the values of six palette variables
(chrome background, foreground and selected tab background plus editor
background, keyword and comment) as that depth serves them: the authored hex on
truecolor, the rewritten `indexed(n)` on 256 colors. `colorLevel` carries the
name of the resolved depth (`truecolor` or `indexed_256`), so `auto` reports
what it resolved to instead of the property token and the `256` value reports
`indexed_256`. It draws the live shell offscreen under the shell lock
and never routes keys from HTTP, but it is only meant for tests and local
debugging: it answers 404 unless `vintelli.inspect.enabled=true`.

`GET /tui/icons?files=Main.java&files=pom.xml` reports the resolved file type
icons as JSON: the icon face the session picked (`vintelli.icons`, `auto`
resolving through the terminal environment) and, per requested file name, the
Nerd Font glyph and the ASCII fallback marker of the built-in mapping or any
user mapping. It is gated by the same `vintelli.inspect.enabled` switch and
exists so the mapping resource (and its load inside the native image) is
validated by the integration tests on every run.

`GET /tui/keymap` reports the keymap the inspection shell builds (source and
the shortcut string of every named action) as JSON. It is gated by the same
`vintelli.inspect.enabled` switch and exists so the TamboUI binding presets
are exercised on every run, including the native runner.

`GET /tui/run-selection` lists detected run configurations and the saved choice.
`POST /tui/run-selection?id=<identifier>` saves a listed configuration for the
next shell session; it does not start a process or forward input to a live TUI.
Unknown ids return 400. Both routes answer 404 unless
`vintelli.inspect.enabled=true` and share the Host and Origin guard above.

`GET /inspect/editor` and its `POST /open`, `/insert`, `/undo` and `/save`
actions expose a small headless editor inspection surface for native
integration tests and local debugging. It is off unless
`vintelli.editor.inspect.enabled=true`; open paths remain confined to the
configured project root, and saves retain the editor's external-change checks.

## Browser terminal

The shell can be watched and driven from a terminal in a browser (an xterm.js
style client) through the `/tui/ws` WebSocket surface
(`io.quarkus:quarkus-websockets-next`). Every WebSocket connection gets its
own session: its own screen buffer and viewport, its own render diff against
the previous frame and its own key stream. The stream is the terminal byte
protocol in both directions: binary frames carry ANSI screen updates to the
client and key bytes from the client, and the only text frame is
`resize COLSxROWS`, which changes the session viewport. Sizes are clamped
(between `20x5` and `400x200`), shell content is sanitized on the way out, so
control characters inside a cell can never drive the browser terminal, and
input is parsed into key events only: the session has no other write path
into the shell than the key events the shared keymap already accepts.

The surface is a guarded, authenticated, default-off surface:

- `vintelli.web.enabled` (default `false`): the upgrade handshake answers 404
  until the surface is switched on.
- Host and Origin validation is done by the loopback guard below
  (`vintelli.mcp.allowed-hosts` and `vintelli.mcp.allowed-origins`), the same
  policy every other route answers 403 with; a browser client therefore needs
  its origin on that list.
- Basic authentication is enforced on the upgrade by Quarkus HTTP security:
  `quarkus.http.auth.basic=true` (already set in `application.properties`).
  Credentials come from the embedded realm that is enabled at build time:
  at runtime, set `quarkus.security.users.embedded.users.<name>=<password>`
  and `quarkus.security.users.embedded.roles.<name>=user` (for example from
  environment variables); no credentials ship with the application, so while
  none are configured every handshake stays on the 401 challenge. Browsers
  show their own login prompt once the surface is on.
- Command-running actions are denied on the web surface by default, even for
  an authenticated browser session. `vintelli.web.allowed-actions` is the
  explicit comma-separated allow-list for actions such as `run.execute`,
  `run.stop` and `terminal.execute`. It defaults to `none`; add action IDs to
  allow selected commands. The local TUI allows actions by default; its optional
  allow-list is `vintelli.tui.allowed-actions` (`*` means all command actions).
  MCP-originated shell actions default to `none` via
  `vintelli.mcp.allowed-actions`.

To use the surface end to end, turn it on and configure one account and one
browser origin (all runtime config, for example through environment):

```properties
vintelli.web.enabled=true
quarkus.security.users.embedded.plain-text=true
quarkus.security.users.embedded.users.terminal=${VINTELLI_WEB_PASSWORD}
quarkus.security.users.embedded.roles.terminal=user
vintelli.mcp.allowed-origins=http://localhost:5173
quarkus.http.cors.origins=http://localhost:5173
```

Set `VINTELLI_WEB_PASSWORD` in the environment (or your secret manager) before
starting; do not commit a literal password.

`quarkus.security.users.embedded.plain-text=true` stores the password as
written; leave it out to supply a digest instead (see the Quarkus security
guide). The CORS origin is needed as well: the CORS filter answers 403 to an
origin it does not list, so the origin has to be on both lists.

Connect a terminal client to `ws://127.0.0.1:8080/tui/ws` with the basic
credentials of the account above. Sessions of other surfaces (the local TUI
and every browser terminal) share the shell state, so all of them show the
same tool windows; the sessions themselves stay independent.

The xterm.js client ships with the application: open
`http://127.0.0.1:8080/tui/terminal` in a browser on the same origin. The page
and its script answer 404 while the surface is off, and the browser asks for
the basic credentials of the account above once. The WebSocket that the page
opens to `/tui/ws` reuses those cached credentials, so no token or query
parameter is needed. The client sends key bytes as they are typed (Alt chords
arrive as ESC-prefixed sequences, and `macOptionIsMeta` maps Option on macOS),
reports its size with `resize COLSxROWS` on connect and on window resize, and
reconnects with a backoff (0.5 s doubling to 8 s) when the socket closes. The
xterm.js library and its fit addon are served from the mvnpm artifacts
`org.mvnpm.at.xterm:xterm` and `org.mvnpm.at.xterm:addon-fit` (versions in the
root `pom.xml`) under `/_static/at/xterm/`; those two library files stay public
because they carry no shell state.

Security notes for the browser terminal:

- Credentials travel as HTTP basic authentication. Over `ws://` or `http://`
  they are readable on the wire, so bind to loopback only or run behind a
  TLS-terminating proxy / TLS for any non-loopback address. The default
  `quarkus.http.host=127.0.0.1` keeps the surface on loopback.
- There is no brute-force protection on failed upgrades (no lockout, rate
  limit or delay). Keep the surface on loopback or behind TLS and a
  reverse proxy that can throttle if needed.
- The surface inherits every key handler of the shared shell, including any
  tool window that handles keys. The shell centrally authorizes named
  command-running actions for every key, mouse and command-palette path. The
  web allow-list stays empty unless an operator explicitly enables named
  actions. A follow-up issue (#161) tracks shipping the xterm.js client page.
- Concurrent sessions are capped by `vintelli.web.max-sessions` (default 4)
  and idle sessions (no client input) close after `vintelli.web.idle-timeout`
  (default `PT5M`). Each session costs one thread, one TamboUI runner and
  two screen buffers, so keep the cap small on constrained hosts.
## Configuring clients

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
config instead of replacing the file. The port above is the one `serve` binds;
a TUI process picks a free port unless `quarkus.http.port` pins it, so point the
client at the pinned port when the TUI is the server.
