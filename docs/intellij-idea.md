# IntelliJ IDEA as an optional provider

Using a running IntelliJ IDEA instance as an optional provider through its MCP server.

Vintelli can use a running IntelliJ IDEA instance as a provider for the Run,
Terminal, Problems and call-hierarchy capabilities through IDEA's MCP server.
IDEA is optional and never required: with no URL configured the IDEA
contributor reports `off` and every capability keeps its standalone provider.

Configure the connection in `application.properties` (or a profile):

```properties
# Required: the streamable HTTP endpoint IDEA prints on startup. There is no
# default because IDEA picks a free port.
vintelli.idea.mcp.url=http://127.0.0.1:<port>/stream

# Optional: project path header some IDEA servers expect.
vintelli.idea.mcp.project-path=/path/to/project

# Optional: bearer token when IDEA requires authentication.
vintelli.idea.mcp.auth-token=<token>
```

For development, a `%dev.` profile keeps the settings local:

```properties
%dev.vintelli.idea.mcp.url=http://127.0.0.1:<port>/stream
%dev.vintelli.idea.mcp.project-path=${PWD}
```

Obtain the URL from IDEA: **Settings | Tools | MCP Server**, then **Copy HTTP
Stream Config** (or read it from the headless starter output). The first call
to a tool triggers IDEA's consent dialog unless *brave mode* is enabled in
IDEA's MCP settings; in headless operation brave mode may allow
unauthenticated command execution, so enable it only with that risk in mind.

In the TUI, choose **Tools | Connect / Reconnect IntelliJ IDEA** or run
**Connect to IntelliJ IDEA** from the command palette. The connection attempt
runs in the background; the shell stays responsive and reports the classified
result in its status line and IDEA status widget. The result refreshes the Run
and Problems views, but never starts a Run or Terminal command. If the URL is
missing or the endpoint rejects the connection, use the
message to update the copied HTTP Stream Config or fix consent/authentication,
then run the same action again.

The AEsh CLI exposes the same connection through `idea status`, `idea connect`
and `idea reconnect`. `idea status` only reads the latest state; the other two
start a fresh connection handshake. Each prints one `IDEA MCP status:` line
with the state and next step. They return exit code `0` when connected and `1`
for every other state, so scripts can check connection readiness directly.

When the URL is configured, the status bar also shows a short `IDEA: <state>`
segment, for example `IDEA: connected` or `IDEA: consent pending`. Connected and
not yet contacted states are dimmed; every other state is emphasized. The
segment reads the last known state off the render thread, at most every five
seconds while the bar renders, so an unreachable IDEA never delays a frame. The
state changes when a tool call or the connect action contacts IDEA.

Status bar states reported by the IDEA contributor:
- `off` - no URL configured or client closed
- `on` - connected and initialized
- `down` - endpoint unreachable or not found
- `auth` - authentication required
- `wait` - consent pending on the IDEA side
- `slow` - request timed out
- `error` - classified failure

Startup is unaffected when IDEA is absent or unreachable.
