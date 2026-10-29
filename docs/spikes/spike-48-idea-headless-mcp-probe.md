# Spike 48: runtime probe of the headless IDEA MCP server (`idea.sh mcpServer`)

Part of #15. Follow-up of spike #25 (`docs/spikes/spike-25-idea-headless-mcp.md`), whose decision was a PROVISIONAL go conditioned on a runtime probe on a released 2026.2.x distribution. This document is that runtime half: everything below was executed against a live server, except items marked as static (jar inspection) or UNVERIFIED; nothing here is from source reading. Probe ran on 2026-10-28 and 2026-10-29.

## Install and build facts

The machine already had a real released IDE, so nothing was downloaded:

- Install: Toolbox app at `~/.local/share/JetBrains/Toolbox/apps/intellij-idea` (Linux/amd64).
- Product: IntelliJ IDEA **Ultimate**, `product-info.json`: version 2026.2.3, buildNumber 262.10968.63, productCode IU. `build.txt`: `IU-262.10968.63`.
- MCP plugin bundled: `plugins/mcpserver/lib/mcpserver.jar`, and the shaded `META-INF/plugin.xml` inside that jar (static inspection, not executed) contains `<appStarter id="mcpServer" implementation="com.intellij.mcpserver.McpServerHeadlessStarter" internal="true" />`. Also present: `plugins/mcpserver/lib/modules/intellij.mcpserver.terminal.jar` and `plugins/debuggerMcp` (the debugger tools in a separate plugin).
- JVM: bundled JBR, OpenJDK **25.0.4** (JetBrains s.r.o.), `-ea`.
- Not present on this machine and therefore still unverified at runtime: any IDEA **Community** distribution of any line, and any released 2026.1 build. The Community-bundling question stays UNVERIFIED; the static evidence in spike 25 is not confirmed or refuted by this probe.

## What was run

The isolated-state recipe from spike 25, on a freshly created tiny Maven project (one `pom.xml`, one `src/main/java/demo/Probe.java`, one `README.md`) at `/tmp/opencode/idea-probe-project`:

```sh
STATE_DIR=/tmp/opencode/idea48/state
umask 077
mkdir -p "$STATE_DIR/config/options" "$STATE_DIR/system" "$STATE_DIR/log"
APP=~/.local/share/JetBrains/Toolbox/apps/intellij-idea
cd "$APP"
bin/idea.sh mcpServer \
  --project=/tmp/opencode/idea-probe-project \
  --port=64349 \
  -Didea.config.path="$STATE_DIR/config" \
  -Didea.system.path="$STATE_DIR/system" \
  -Didea.log.path="$STATE_DIR/log" \
  -Didea.mcp.server.smart.mode.timeout.seconds=300 \
  --invocation-mode=direct
```

The `-D` options were placed after the command as the spike-25 recipe says, and they were honored: the first stderr line read `MCP headless startup timeouts: projectInitialization=10m, smartMode=5m`, i.e. the smart-mode override was applied (and the other two `-D` paths produced a working config/system/log layout in the state dir).

**Caveat found:** the JVM options come from the user's real config even with an isolated `-Didea.config.path`. The java process was started with `-Djb.vmOptionsFile=$HOME/.config/JetBrains/IntelliJIdea2026.2/idea64.vmoptions` and `-Xmx2048m` in the launcher line, i.e. the flags came from the pre-existing user-level config of a normal GUI install despite the isolated paths. A managed provider must not assume the isolated config controls JVM options; either accept the local defaults or pass explicit `-D` JVM option overrides at the launcher level. Effect observed in this run: Java **heap capped at 2048m** (`-Xmx2048m`), which was enough for the probe project; a large project with a 2 GB cap is risky for indexing. This interaction was observed once, not fully mapped (no run with the user vmoptions absent). UNVERIFIED: exact precedence between the install's `bin/idea64.vmoptions` and the user-level `idea64.vmoptions` when `-Didea.config.path` is set.

## Startup: timings and memory

Logged and observed values, first cold run on the fresh state dir:

| Phase | Value | Source |
| --- | --- | --- |
| Project opened/imported | 10670 ms | stderr, `Project opened/imported in 10670 ms.` (name/base path follow, `dumb=true`) |
| Smart mode reached | 42549 ms after launch | stderr, `Projects are smart, starting MCP server after 42549 ms` |
| Banner printed | ~54 s after `bin/idea.sh` invocation | stderr/stdout mtimes vs. launch timestamp |
| RSS at ~48 s | ~2.3 GB (2,398,592 kB) | `ps rss` on the java process |
| RSS at ~6 min | ~2.55 GB (2,676,656 kB) | `ps rss` on the java process |

Context: the project is a two-file Maven toy and the run was the first (cold) indexing into a fresh state dir, so the numbers are a floor, not a target. Real projects will exceed both numbers; the 2 GB heap is a default taken from the user vmoptions, not a known-good value for bigger projects.

Banner content matched spike 25 exactly: `MCP Server started on port 64349`, `SSE URL: http://127.0.0.1:64349/sse`, project name and path; stdout carried the copy-pastable JSON for `~/.claude.json` (key `ide-headless-mcp`, `type: sse`, url `http://127.0.0.1:64349/sse`). No TOML/Codex output on this build.

Shutdown note: `SIGTERM` to the java process terminated it cleanly for the probe; the graceful-shutdown path in the starter was not inspected. Vintelli should still plan a soft timeout then `SIGKILL`.

## Endpoint contract (runtime)

All facts below were executed with `curl` against the live server.

- `POST /stream` with `initialize` returning **JSON body** (`Content-Type: application/json`, confirming `enableJsonResponse=true`) plus a `mcp-session-id` response header. Request headers used: `Content-Type: application/json`, `Accept: application/json, text/event-stream`, `IJ_MCP_SERVER_PROJECT_PATH: /tmp/opencode/idea-probe-project`. Body: standard JSON-RPC `initialize` with `protocolVersion: "2025-06-18"`, empty client capabilities.
- `initialize` result, verbatim: `protocolVersion: "2025-06-18"`, `capabilities: {"tools":{"listChanged":true}}`, `serverInfo: {"name":"IntelliJ IDEA MCP Server","version":"2026.2.3"}`. Only the `tools` capability is advertised, matching the source reading.
- `GET /stream`: an SSE stream whose only content is `: heartbeat` comments (observed period consistent with the 5000 ms default, not precisely timed). Required for session liveness, see Sessions.
- `GET /sse`: SSE, returns `event: endpoint` with `data: /message?sessionId=<uuid>` as the POST target, followed by `: heartbeat` lines. Legacy transport is present on this build.
- `DELETE /stream` with `mcp-session-id`: HTTP 200, and subsequent use of that session fails, so sessions can be deleted by the client.
- Host validation: `POST /stream` with `Host: evil.example` returned **HTTP 403**. The Origin/Referer checks were not exercised (loopback client without browser headers).

## Sessions expire fast without the GET stream

This is the most important operational fact that spike 25 did not predict:

- A session created by `initialize` and used by `tools/call` immediately works.
- If that session's `GET /stream` (the notification transport) is never opened, the session is dropped on the server side between 10 and 20 s after creation in both observed runs (n=2; the exact timeout was not measured). Observed twice: a session whose POST at 10 s idle succeeded, whose next POST at 20 s idle returned the plain text body `Streamable HTTP session not found`; and a second session with the same shape. The `idea.log` shows the parallel server-side warning `Pending StreamableHttp transport timed out without SSE stream: <id>` each time.
- While the GET stream was open, the session survived 60 s and more; with the stream closed after 75 s, a call 3 s later still succeeded once (the log shows subscription cleanup only on idle timeouts, so a few seconds after a stream teardown is a gray zone; do not rely on it).
- Plain POSTs do **not** keep the session alive across that gap (both observations above had intervening successful POSTs and still died). The durable pattern is an open GET on `/stream` plus its heartbeats. UNVERIFIED: whether POSTs keep the timer going for a session whose GET stream was opened and then closed (#71).

HTTP status of the `Streamable HTTP session not found` response was not captured (code-level status unknown; the body text is verbatim above; #71). The timing above is n=2 and is used here as a design constraint inferred from those runs, not as a measured contract. The impact for Vintelli: the MCP client must open the GET notification stream promptly after `initialize` (which a standard Streamable HTTP client does) and keep it open for the whole session, including during tool calls, or the server silently drops the session. Vintelli's health check and the tool-call path must share one session, not create a fresh one per call.

## Real `tools/list` on the default whitelist

`--allowed-tools` was not passed, so the default whitelist from spike 25 applied (the four search tools). `tools/list` returned **5 tools** with full JSON Schemas; the exact capture is committed as the companion file `spike-48-tools-list.json` next to this document. Summary of the shapes:

| Tool | Required input | Input props | Output schema |
| --- | --- | --- | --- |
| `search_file` | `q` (glob pattern, string) | `paths` (array, project-relative globs, supports `!` excludes, trailing `/` expands to `**`), `includeExcluded` (bool), `limit` (integer) | `items[].{filePath, startLine?, startColumn?, endLine?, endColumn?}` plus `more` (bool), `partialResultReason` (string) |
| `search_regex` | `q` | `paths`, `limit` | same shape |
| `search_symbol` | `q` | `paths`, `limit`, `include_external` (bool; "include SDK and library symbols. Disabled by default; if nothing suitable is found, try again with include_external=true") | same shape |
| `search_text` | `q` | `paths`, `limit` | same shape |
| `execute_tool` | `command` (string, "Command-line string with tool name and arguments") | (none) | none |

The capture includes each tool's `description` strings, and the four search tools carry `annotations: {readOnlyHint: true, openWorldHint: false}` plus an `outputSchema`; `execute_tool` (the router) has neither. Names and shapes are now real, not documented-only; results include 1-based line/column match coordinates, which the capability adapters can use directly.

Two consequences worth flagging for the provider work (#21, #15):

- `execute_tool` is exposed **even with `--invocation-mode=direct` and no `--allowed-tools` override**, so the router is always part of the surface, not something a client opts into.
- Tool names in this table coincide with the 2026.2 help page's search group, so the drift concern from spike 25 applies to the heavier tools, not necessarily to the default whitelist. #2 still takes names from a live `tools/list`, never from this table.

## Project resolution at tool-call time: what the released 2026.2.3 build actually honors

Spike 25 said from source that on 2026.2.x only the `IJ_MCP_SERVER_PROJECT_PATH` of the request that creates the session is used. That was stricter than the shipped build. Observed on this build (one tool call each):

- Send the header on the `initialize` POST: session-bound project resolves; tools then work with no further effort. Confirmed.
- Omit the header at `initialize`, and send `IJ_MCP_SERVER_PROJECT_PATH` on a later `tools/call` POST: **works** (resolved a file). So per-call header reading exists on 2026.2.3.
- Omit the header everywhere, pass a `projectPath` **tool-call argument**: **works**.
- Omit everything: tool call returns `isError: true` with text starting `Unable to determine the target project for the current MCP tool call`, listing the currently open projects as JSON (`{"projects":[{"path":...}]}`), and suggesting `projectPath`.

The spike-25 source reading does not match the shipped jar here; the 2026.2.3 source tag should be re-read to locate where the per-call header read actually lives before anyone relies on it as a version boundary. `projectPath` is accepted by the server but is not declared in any `inputSchema` of `spike-48-tools-list.json`, so a client that builds arguments from `tools/list` will not send it on its own.

Corrected decision: still send `IJ_MCP_SERVER_PROJECT_PATH` on `initialize` (cheapest, and confirmed working), with the per-call header and the `projectPath` argument as fallbacks.

Also worth recording: a tool call with a wrong argument name (`globPattern`, from an older documented schema, instead of the real required `q`) returns the text `MCP tool call has been failed: No argument is passed for required parameter 'q'` with `isError: true`, in-band, not a JSON-RPC error; the IDEA `idea.log` records it as SEVERE. Vintelli's capability layer must treat `isError`-in-content as the normal failure channel, and must take argument names from the real schemas (`q`, not `path` or `globPattern`), which is exactly what #2 reads from `tools/list`.

## Auth, brave mode, consent, and the parser surface

- **No auth token needed.** The global server (the one the headless starter uses) accepted `initialize` and `tools/call` with no `IJ_MCP_AUTH_TOKEN` header. The spike-25 claim that headless mode uses `authCheck = false` is consistent with observation. The token path was not exercised because it needs an in-IDE session; still UNVERIFIED at runtime.
- **No consent gate hit headless.** A fresh-isolated-config start did not block on any dialog, and no `mcpServer.xml` was ever written into the state dir's `options/` during the run (checked: the dir stayed empty). Spike 25's reading (headless consent skipped on 2026.3, no gate on 2026.2) is consistent, but the persistence that only the 2026.3 `start()` writes was not exercised on this build either, since nothing was persisted here.
- **Version matrix against the shipped 2026.2.3 jar** (static inspection of class strings; `--search-format` was not passed at runtime, only the absence of the TOML stdout block was observed): the starter's argument parser accepts exactly `--project=`, `--port=`, `--invocation-mode=`, `--allowed-tools=` (all four verbatim in the class constant pool; `--search-format` is not present anywhere in the shipped jar's strings). No TOML/Codex stdout block. So the spike-25 statement that `--search-format` and TOML are 2026.3-only is consistent with the shipped jar strings.
- **What was NOT exercised:** an `--allowed-tools=` override (mask behavior), `--invocation-mode=via-router`, the `-p` short flag (the 2026.2.3 source says unimplemented), a GUI IDEA instance holding the same project (no GUI instance ran during the probe), and the brave-mode-gated tools (`execute_terminal_command`, run configurations), which are not in the default whitelist. The spike-25 warnings about brave mode in a headless JVM stand UNVERIFIED (#68).

## `idea.sh inspect` next to the headless server

`bin/inspect.sh /tmp/opencode/idea-probe-project Project-Default /tmp/output` was run in parallel with the headless MCP server that owned the same project. Result: exit 0, produced `.descriptions.xml` (~2.4 MB) and one inspection result file in the output dir; stderr shows plain SLF4J noise and `Using default project profile` (the profile name was not resolved, so the run used the project default profile rather than a named one).

Conclusion for #5's question: `inspect` **coexists** with a headless MCP server on the same project (two IDEA processes on one project succeeded). Not answered by this run and out of its scope: whether the inspection profile must exist as a real file for a named profile, and whether a *GUI* IDEA instance (not another command) on the same project causes any contention.

## Untouched areas and open questions (contrast against spike 25)

Resolved by this probe (details above): the headless starter accepts `idea.sh mcpServer` on released 2026.2.3; the isolated-state recipe works as documented; banner shapes; `/stream` and `/sse` shapes; real tool schemas and `tools` capability; project-path resolution paths; no auth required; no consent dialog; timeout property honored; the 2026.2 sides of the version matrix (no `--search-format`, no TOML); `inspect` coexistence; `DELETE` on `/stream`; and the session keep-alive contract.

Still open after this spike (UNVERIFIED, carrying over except where reformulated):

- **Community edition runtime behavior.** No Community distribution is installed on this machine and none was downloaded (download size not measured); the static bundle claim from spike 25 stays unconfirmed at runtime (#61).
- **JVM options precedence** when a user config exists alongside the isolated state dir (see Caveat in What was run): heap cap and provider defaults must be pinned in #21 and checked in #70.
- **Brave-mode-gated tools headless** (`execute_terminal_command`, run configurations) and their `DialogWrapper` behavior; only relevant if Vintelli ever enables brave mode (current recipe: no) (#68).
- **GUI IDEA coexistence** (a running IDE holding the same project path) was not exercised; only headless+headless (#69).
- **HTTP details** beyond what was observed: the HTTP status code of the `Streamable HTTP session not found` response, and `Accept` header semantics other than `application/json, text/event-stream` (#71).
- **2026.1 released behavior** of `idea.sh mcpServer` (whether the 2026.1.x starter starts headless, GUI, or fails without a display): no 2026.1 build present (#69).
- **Propagating tool-limit overrides**: `--allowed-tools=` override and `IJ_MCP_ALLOWED_TOOLS` were not used; both are documented in source but not exercised for the narrowed whitelist decision (#68).
- **Fresh-config first-run state on other machines.** This machine already had a Toolbox install with `~/.local/share/JetBrains/consentOptions` and Toolbox-managed state; a machine with no prior JetBrains footprint may differ (EULA, telemetry consents, download cache). Headless mode reportedly guards these dialogs (`ApplicationLoader` gates them with `AppMode.isHeadless()`), but that was not exercised on a clean machine (#70).

## Decision

The two conditions the PROVISIONAL go in spike 25 carried:

1. "The spawn command works on a released 2026.2.x distribution" - **confirmed** on released 2026.2.3 (this machine's Toolbox install, build IU-262.10968.63).
2. "Vintelli sends `IJ_MCP_SERVER_PROJECT_PATH` on the `initialize` request" - unchanged plan; on released 2026.2.3 it is one of three working resolution paths (session header, per-call header, `projectPath` argument), but the session header remains the only one confirmed across versions.

**GO** for a `ManagedIdeaProvider` on IDEA 2026.2+; the spike-25 decision changes from PROVISIONAL go to final go (updated in the spike-25 document). Design notes flowing into #21: parse readiness from the stderr banner (`MCP Server started on port N`), open the GET notification stream promptly and keep it open for the whole session (sessions die without it), set the smart-mode timeout explicitly, do not rely on POST-only traffic keeping a session alive, and pin the JVM heap explicitly because the isolated `-Didea.config.path` does not control `idea64.vmoptions`.

## Follow-ups

- #60: re-read the released 2026.2.3 mcp-server sources for per-call project path handling (the spike-25 reading does not match the shipped jar).
- #61: run this probe on an IDEA Community 2026.2+ distribution (Community bundling is still only statically evidenced).
- #68: brave-mode-gated tools headless, tools that need SDK or run-configuration setup, `--allowed-tools=` override, `IJ_MCP_ALLOWED_TOOLS`, `--invocation-mode=via-router` and the `-p` flag.
- #69: released 2026.1 `idea.sh mcpServer` behavior and a GUI IDEA instance holding the same project.
- #70: JVM options precedence, explicit heap pinning for the managed spawn (provider work stays in #21), and first run on a clean machine.
- #71: HTTP status of `Streamable HTTP session not found`, POST keep-alive after the GET stream closes, `Accept` header semantics, and MCP Java SDK 2.1.0 client compatibility.
- Twin fixtures and required failure-mode coverage (session dropped after the GET stream stays closed, `Host` rejection 403, missing project resolution, `isError`-in-content failures): #2 and #55.
- `inspect` coexistence is confirmed at command level here; the `analyze_calls`-vs-`inspect` decision remains in #5, and the GUI-instance question is tracked in #69.

## Feeding the twin (IDEA client tests, AGENTS.md "Native validation")

The companion capture `spike-48-tools-list.json` (committed next to this document) is the ground-truth `tools/list` payload of this released build, including descriptions and full input/output schemas, ready to become the twin's fixture set for #2. The twin must also cover the failure modes observed here: session dropped after the GET stream stays closed, `Host` header rejection (403), missing project resolution (`isError` text), and `isError`-in-content tool failures.

## Validation runs

- 2026-10-28/29, this machine: all of the above against build IU-262.10968.63. Raw logs stay under `/tmp/opencode/idea48/` (not committed): `stdout.log`, `stderr.log`, `state/log/idea.log`, `init-headers.txt`, `tools-list.txt`, probe bodies.
