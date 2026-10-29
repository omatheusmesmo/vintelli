# Spike 25: headless IDEA MCP server

Part of #15. Everything below was verified by reading the JetBrains sources: intellij-community release tags `idea/2025.2.6`, `idea/2025.3.4`, `idea/2026.1.5`, `idea/2026.2.3`, `idea/2026.3-beta`, and branch `master` (fetched 2026-10-28), plus the official help page. The 252 and 253 branches were also checked: the starter is absent in both. Nothing was verified at runtime: no IDEA distribution was launched for this spike, and items that can only be settled by a live run are listed under "Risks and open questions".

## Decision

**PROVISIONAL go** for a `ManagedIdeaProvider` on IDEA 2026.2+ (the first released line where `idea.sh mcpServer` is a headless command), conditional on the runtime probe below.

Conditions attached to the provisional go:

- The spawn command works on a released 2026.2.x distribution (not verified).
- The recipe keeps brave mode off and uses the narrow tool whitelist by default (see Security).
- Vintelli sends `IJ_MCP_SERVER_PROJECT_PATH` on the `initialize` request.

Not verified by execution (all follow-ups, see "Risks and open questions"):

- The `tools/list` capture and the real tool schemas.
- Whether IDEA Community bundles the MCP server plugin (static evidence only).
- Startup time and memory.
- The behavior of `idea.sh inspect` while a headless instance owns the same project.
- Whether the startup recipe works with a fresh config, and which tools work without UI.
- Compatibility of the MCP Java SDK 2.1.0 client, and whether brave mode is honored headless.

## Findings

### Version matrix

Each row lists what was verified on that release tag. Items marked 2026.3 exist only on `master` and `idea/2026.3-beta`, not in the released 2026.2 line.

| Release (tag) | Starter and command | Notes |
| --- | --- | --- |
| 2025.2.x, 2025.3.x (`idea/2025.2.6`, `idea/2025.3.4`) | No `McpServerHeadlessStarter`, no `mcpServer` starter | `idea.sh mcpServer` is not available. |
| 2026.1.x (`idea/2026.1.5`) | Starter exists (commit `4b368fde`), registered in `plugin.xml` without `internal` | `mcpServer` is absent from `WellKnownCommands.kt`, so it is not a headless command. What a 2026.1 build does with the command (GUI start, error, or failure without a display) is not verified. |
| 2026.2.x (`idea/2026.2.3`) | First-class headless command (`WellKnownCommand` maps `mcpServer` to `HEADLESS`), `appStarter` with `internal="true"` | Arguments: `--project`, `--port`, `--invocation-mode`, `--allowed-tools` (no `--search-format`). Timeouts: project init and smart mode only. Project path header read only when the session is created. JSON on stdout (TOML is 2026.3 only). |
| 2026.3 (`master`, `idea/2026.3-beta`) | Same starter, plus the items below | Adds `--search-format=json` or `compact`; project configuration wait (`Observation.awaitConfiguration`, property `idea.mcp.server.project.configuration.timeout.seconds`, commit `d707b4f0`, 2026-09-22); consent gate and `GRANTED` persistence in `start()`; per-call `IJ_MCP_SERVER_PROJECT_PATH` with project resolution; TOML for Codex on stdout. |

Everything in the recipe targets 2026.2.x unless a row above says 2026.3.

### `idea.sh mcpServer` is a real headless command

Registration (`idea/2026.2.3`; line ~59 there, the line number on `master` was not confirmed): `plugin = plugins/mcp-server/resources/META-INF/plugin.xml`:

```xml
<appStarter id="mcpServer" implementation="com.intellij.mcpserver.McpServerHeadlessStarter" internal="true"/>
```

`McpServerHeadlessStarter` (plugins/mcp-server/src/com/intellij/mcpserver/McpServerHeadlessStarter.kt) is a `ModernApplicationStarter` with `override val isHeadless: Boolean = true`, and `args[0]` must literally be `mcpServer`.

Platform-level recognition: `platform/core-api/src/com/intellij/idea/WellKnownCommand.java` (`idea/2026.2.3` and `master`) contains `put("mcpServer", HEADLESS)`. `AppMode.setFlags` then forces `java.awt.headless=true` and marks the process as a command-line run, and `ApplicationLoader.createAppStarter` exits with `NO_GRAPHICS` when a starter that is not headless-capable is used in headless mode. So in 2026.2+ `mcpServer` is a first-class headless command, and nothing needs to be passed to force headless.

Arguments (2026.2.x and `master`):

- `--project=<path>`, repeatable and de-duplicated (default: current working directory).
- `--port=<n>` (default: auto-select first free port starting at the persisted port). The KDoc also mentions `-p <n>`, but the parser only implements `--port=`, so `-p 5000` does nothing.
- `--invocation-mode=direct|via-router` (default `via-router`).
- `--allowed-tools=<names>|*`. When omitted, the built-in whitelist is only `search_file`, `search_text`, `search_regex`, `search_symbol`, converted to the mask `-*,+*.<name>...` in `McpToolFilterSettings.toolsFilter`.
- `--search-format=json|compact` (2026.3 only).

Startup sequence (from `McpServerHeadlessStarter.start`):

1. Marks the target path as a trusted project (`TrustedProjects.setProjectTrusted`), so the trust dialog cannot block headless runs.
2. Opens or imports the project (`ProjectUtil.openOrImportAsync`).
3. Waits for project init (10 min timeout) and smart mode (`waitForSmartMode`, 30 min timeout). These are overridable with `idea.mcp.server.project.init.timeout.seconds` and `idea.mcp.server.smart.mode.timeout.seconds`. On 2026.3 the starter also waits for project configuration (`Observation.awaitConfiguration`, 30 min), overridable with `idea.mcp.server.project.configuration.timeout.seconds`. On 2026.2.x that property is ignored.
4. Calls `McpServerService.getInstance().start()`. On 2026.3 this also persists `McpServerConsent.GRANTED` (see Consent).
5. Prints to **stderr**:

   ```
   ******************************
   * MCP Server started on port 64342
   * SSE URL: http://127.0.0.1:64342/sse
   * Project: <name> (<base path>)
   ******************************
   ```

   and to **stdout** a copy-pastable JSON snippet for `~/.claude.json` (`{"mcpServers": {"ide-headless-mcp": {"type": "sse", "url": "..."}}}`). The TOML snippet for Codex is 2026.3 only.
6. Blocks forever (`awaitCancellation()`); the graceful shutdown path closes the projects.

Headless mode skips the EULA and language dialogs (`ApplicationLoader` guards them with `AppMode.isHeadless()`), which is what allows a start with a fresh config directory.

`-D` options placed after the command are read by the launcher (`Main.kt`, `preprocessArgs`), applied as system properties, and stripped before the starter sees the arguments, so `args[0]` stays `mcpServer`.

Version compatibility:

The starter exists since commit `4b368fde` (IJPL-227249, authored 2025-12-02, committed 2026-01-22). It is absent in releases 2025.2.x and 2025.3.x and present, but not wired as a headless command, from 2026.1 (`idea/2026.1.5`). The supported target of this recipe is IDEA 2026.2+. The official help page (https://www.jetbrains.com/help/idea/mcp-server.html, currently "2026.2") documents the plugin as bundled and enabled by default (introduced in 2025.2); the server itself still has to be turned on in settings, or by `start()`/`force.enable` in headless mode.

### Endpoint contract

Verified in `plugins/mcp-server/src/com/intellij/mcpserver/impl/util/network/mcp.sdk.util.kt`. Served by a Ktor CIO embedded server bound to `"127.0.0.1"` only (`McpServerService.startServer`):

| Endpoint | Methods | Notes |
| --- | --- | --- |
| `/sse` | GET | Legacy SSE transport; the POST endpoint is `/message?sessionId=<id>` |
| `/stream` | GET/POST/DELETE | Streamable HTTP; `mcp-session-id` header; `enableJsonResponse = true` |

- `/stream` GET opens an SSE notification stream (with heartbeats every `mcp.server.sse.heartbeat.period.ms` (IDE Registry key), default 5000), POST sends requests and creates sessions, DELETE ends the session.
- URL shape built by `McpServerConnectionAddressProvider`: `http://127.0.0.1:<port>/sse` and `http://127.0.0.1:<port>/stream`. Default port: 64342 for IDEA, with +20 steps per product (ClionE etc.) and auto-increment to the next free port. The selected port is persisted back into settings unless a runtime override is active (`-Didea.mcp.server.force.port`, or `force.enable`, checked via `hasMcpServerRuntimeOverrides()`).
- Server capabilities advertise **tools only** (`listChanged = true`; prompts and resources are explicitly turned off on the SDK side).

### Headers

- **`IJ_MCP_SERVER_PROJECT_PATH`**: read in `McpServerService.startServer` and copied into the session through `HttpCallTransport`, so it survives SDK send and response header dropping. On 2026.2.x only the header of the request that creates the session is used (`projectPathFromInitialRequest`). Per-call reading and project resolution exist on 2026.3 only (`master`): tool-call argument `projectPath`, then the per-call header, then the session header, then the request file roots. Otherwise the tool call fails with a "no suitable project" error. Resolution matches the header value against open projects (`findMostRelevantProject`), so it can only select among projects that the starter opened with repeated `--project=`. Vintelli must send the header on the `initialize` request in all versions.
- **`IJ_MCP_CLIENT_TAGS`** and **`IJ_MCP_ALLOWED_TOOLS`**: read when a session is created. `IJ_MCP_ALLOWED_TOOLS` is a session-level allow list chosen by the client. It narrows what that session sees but is not an access control, because the server does not require it.
- **`IJ_MCP_AUTH_TOKEN`**: only consulted on the private, isolated server started by `McpServerService.authorizedSession(...)` (`authCheck = true`, 401 "restricted mode"). The global server, which headless mode uses, is started with `authCheck = false`, so no token is required headless. See Security for why no token is usable from Vintelli.

### Consent, brave mode, and auth headless

- **Consent gate (2026.3 only).** `McpServerConsentGate.isInteractiveHost()` returns `!application.isUnitTestMode && !application.isHeadlessEnvironment`. In headless mode `awaitConsent()` returns `true` without touching settings, and `McpServerService.start()` sets `McpServerConsent.GRANTED` in memory. On 2026.2.x there is no consent gate on the global server (the 2026.2 consent is the older `getConsentDialog` in `util/ConsentDialog.kt`, used from the settings UI). A headless start therefore needs no dialog on either line, for different reasons.
- **Forced enable and port (2026.2.x and `master`).** `-Didea.mcp.server.force.enable=true` and `-Didea.mcp.server.force.port=<n>` (`McpServerRuntimeOverrideUtil`) enable the server and pin the port independently of persisted settings. A forced port is exact: if it is busy, the server does not start, the failure is swallowed in `startGlobalServer`, and the starter fails with `MCP Server is not enabled`.
- **Brave mode is a persisted setting, not per session:** `McpServerSettings.enableBraveMode` (default `false`, `@State(name = "McpServerSettings", storages = [Storage("mcpServer.xml")])`), stored under the config directory's `options/`. Brave mode only gates the callers of `checkUserConfirmationIfNeeded`: terminal execution, run configurations and, on 2026.3, the IDE script tool. With brave mode off (the default), those callers open `askConfirmation`, a Swing `DialogWrapper` that also offers the "enable brave mode" checkbox, and there is no headless shortcut on `idea/2026.2.3` or `master`. In a headless JVM that dialog cannot be shown, so without brave mode these tools are expected to fail or hang. This is unverified: `DialogWrapper.show()` may throw `HeadlessException` or block. Pre-enabling brave mode through a seeded `mcpServer.xml` would avoid the dialog, but it opens unauthenticated command execution (see Security), so the recipe does not do it. The XML shape `<application><component name="McpServerSettings"><option name="enableBraveMode" value="true"/>` is the standard `BaseState` serialization; its byte format is not verified at runtime.
- **Host validation without a token:** `installHostValidation` (`impl/util/network/util.kt`) rejects any Host, Origin or Referer that is not `127.0.0.1`, `localhost`, `::1` or `[::1]`, with 403. It is a browser and DNS rebinding defense only (see Security). Our client binds to `127.0.0.1` and must not send `Origin` or `Referer` headers (MCP clients do not by default).

### Tool surface (docs + repository; real schema shapes still need a live tools/list)

The IDEA 2026.2 help page (https://www.jetbrains.com/help/idea/mcp-server.html) enumerates **58 named tools** in these groups: Analysis (`analyze_calls`, `build_project`, `get_file_problems`, `get_project_dependencies`, `get_project_modules`, `lint_files`), Code Insight (`get_symbol_info`), Database (registered only if the database tooling plugin is up: `execute_sql_query`, `introspect_schema`, `list_database_connections`, `preview_table_data`, ...), Debugger (`xdebug_*`, 13 tools), Execution (`execute_run_configuration`, `get_run_configurations`), File (`create_new_file`, `get_all_open_file_paths`, `list_directory_tree`, `open_file_in_editor`, `reformat_file`), Formatting, Inspection Generator and Inspection KTS, Patch (`apply_patch`), Read (`read_file`), Refactoring (`rename_refactoring`), Search (`search_file`, `search_regex`, `search_symbol`, `search_text`), Skill Search (`skill_search`), Terminal (`execute_terminal_command`), Universal (`execute_tool`, the router), VCS (`git_status`, `get_repositories`).

A client-side capture also exists in the repository at `build/mcp-servers/ij-proxy/cc-tools.json` (meta `2026-01-21`). It is a Claude Code tool schema from an earlier build on a JetBrains developer machine, not a server `tools/list` payload, and it must not be used as a `tools/list` fixture. It shows 24 `mcp__jetbrains__*` tools, and its names differ from the 2026.2 help page: it lists `get_file_text_by_path`, `replace_text_in_file`, `find_lock_requirements_usages`, `find_threading_requirements_usages` and `permission_prompt`, which the help page no longer lists, and it lacks `read_file`, `apply_patch` and `analyze_calls`, which the help page lists. Tool names therefore drift between versions, which matters for the #2 test fixture and the capability adapters.

There are also bundled MCP skills (`com.intellij.mcpServer.bundledSkillsProvider`, `mcpserver.skills` module) whose delivery checks that the tools a skill needs are exposed. This is inferred from source and not verified at runtime.

Community vs Ultimate: the plugin lives inside the `intellij-community` tree (`plugins/mcp-server`), and the help page states it is bundled and enabled by default. Static evidence points the same way: `build/src/org/jetbrains/intellij/build/IdeaCommunityProperties.kt` (`idea/2026.2.3`, `AndroidStudioProperties`) starts from `IDEA_BUNDLED_PLUGINS` and calls `.removing("intellij.mcpserver")`, which implies the Community bundle list includes the module and that Android Studio opts out. This is not a runtime proof; the claim must still be verified on a Community distribution.

## Security

The headless server trades authentication for simplicity, so the recommended settings have to be read with that in mind.

- The global server used headless has no authentication (`authCheck = false`). Host, Origin and Referer validation only stops browsers and DNS rebinding. It does not stop other processes or other local users, because loopback is shared: anyone who can reach `127.0.0.1:<port>` can call every exposed tool.
- With brave mode on and `--allowed-tools=*`, the exposed tools include `execute_terminal_command` and `execute_run_configuration` (on 2026.3 also the IDE script tool). That is unauthenticated command execution as the user who runs Vintelli, reachable from any local process.
- `IJ_MCP_ALLOWED_TOOLS` is chosen by the client and read when the session is created. A different client can simply omit it, so it narrows what that client sees and is not an access control.
- `IJ_MCP_AUTH_TOKEN` exists only for the in-IDE private server started by `McpServerService.authorizedSession(...)`, and it is handed to in-process callers. An external process such as Vintelli cannot obtain one, so there is no token option for headless use.

Mitigations to evaluate (none is implemented or tested):

- Keep brave mode off, and do not expose `execute_terminal_command` or `execute_run_configuration` unless the user opts in. Vintelli already has its own `local` terminal and run providers.
- Narrow `--allowed-tools` to what each capability needs. The default for a managed provider is the narrow whitelist, not `*` with brave mode.
- A random high port on loopback is not a defense against other local users.
- Consider running the server inside a user-private network namespace, or accept the risk explicitly and record that decision.

## Startup recipe

Concrete headless startup recipe, to validate in the runtime spike. It requires 2026.2+. It keeps the narrow default: brave mode is not enabled and `--allowed-tools` is not overridden, so the default whitelist of four search tools applies.

The state directory is per user and created with a restrictive umask, so another local user cannot pre-create or tamper with the settings. A persistent `system` directory keeps indexes between runs; the trade-off is that a fresh `system` directory means a cold index on every run.

```sh
STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/vintelli/idea-headless"
umask 077
mkdir -p "$STATE_DIR/config/options" "$STATE_DIR/system" "$STATE_DIR/log"

bin/idea.sh mcpServer \
  --project=/path/to/project \
  --port=64342 \
  -Didea.config.path="$STATE_DIR/config" \
  -Didea.system.path="$STATE_DIR/system" \
  -Didea.log.path="$STATE_DIR/log" \
  -Didea.mcp.server.smart.mode.timeout.seconds=300 \
  -Didea.mcp.server.project.configuration.timeout.seconds=300 \
  --invocation-mode=direct
```

Notes on the recipe:

- `--port=64342` auto-increments if the port is busy, and the stderr banner reports the real port, which is what readiness parsing reads. Use `-Didea.mcp.server.force.port=<n>` only when exact-or-fail behavior is wanted.
- The project configuration timeout is 2026.3 only and is ignored on 2026.2.x.
- Brave mode is not enabled here. Enabling it means a pre-seeded `mcpServer.xml` and carries the risks listed in Security.
- A fresh config has no JDK table, no Maven or Gradle home settings and no imported project SDK. Whether `build_project`, `get_file_problems` and run configurations then work is not verified (see Risks and open questions).

## Risks and open questions

- Whether a **released 2026.2 distribution** accepts `idea.sh mcpServer --project=...` in practice. The starter is `internal="true"` and its KDoc mentions `bazel run build:mcp_server --` for source runs. The `WellKnownCommand` mapping and the `appStarter` registration say yes, but this is the first thing the runtime probe must prove.
- The exact **stderr and stdout shapes** at runtime (banner with port and SSE URL, timeout messages, JSON lines), and the actual startup time and RSS for a Quarkus project. Not measured.
- What a **released 2026.1** build does with `idea.sh mcpServer` (GUI start, error or failure without a display). Not verified. Also open: whether `-Djava.awt.headless=true` makes `AppMode.isHeadless` true on 2026.1 (`Boolean.getBoolean`), so that the older starter could run. It has no whitelist, timeouts or `--invocation-mode`, so every tool would be exposed. Not verified.
- Which tools behave correctly **without UI**. Brave-mode gated: `execute_terminal_command`, `execute_run_configuration`. Not brave-mode gated, with headless behavior unknown because of possible EDT or UI dependencies: `build_project`, `get_file_problems`, `lint_files`, `rename_refactoring`, `xdebug_*`.
- Fresh-config prerequisites: the isolated config has no JDK table, Maven or Gradle home settings or project SDK. `openOrImportAsync` imports a Maven or Quarkus project, but whether `build_project`, `get_file_problems` and run configurations work without SDK setup is not verified.
- Real **`tools/list` JSON** payload with the actual schemas. The 58 tool names are documented, but no raw fixture was captured without a live server.
- Behavior when a GUI IDEA instance already owns the same project. The starter does not obviously check for running instances in source, so a runtime probe is needed.
- Whether `idea.sh inspect` can run while a headless instance owns the same project. Not checked in source or at runtime. #5 depends on this.
- Whether the **MCP Java SDK 2.1.0 client** connects cleanly to both `/stream` (Streamable HTTP, `mcp-session-id`, GET, POST and DELETE, with heartbeats) and `/sse` (legacy transport). Two client-side wrinkles are visible in source: `enableJsonResponse=true` on `/stream`, and header propagation. On 2026.2.x only the `initialize` request header matters; on 2026.3 the client should keep sending the headers on every request.
- The **pre-seeded `mcpServer.xml`** persistence byte format (component name, option elements). Not verified at runtime; needed only if brave mode is ever enabled.
- The exact behavior of a Swing `DialogWrapper` confirmation in a headless JVM. Expected to fail or hang; unverified.
- Whether IDEA **Community** (not Ultimate) bundles the MCP server plugin. Static evidence only (see Community vs Ultimate).
- SDK 2.1.0 compatibility beyond the connection checks above is not verified.

### Criteria for issue #2

- The server URL comes from a configured property or from the process manager (the stderr banner), not from a hard-coded port.
- The `initialize` request carries `IJ_MCP_SERVER_PROJECT_PATH`.
- Tool names are not hard-coded; they are taken from `tools/list`.

### Criteria for issue #5

- Decide whether `get_file_problems` and `lint_files` replace `idea.sh inspect`. This needs the `inspect` coexistence answer listed above.

## Verified from source

- `plugins/mcp-server/src/com/intellij/mcpserver/McpServerHeadlessStarter.kt` (headless starter, args, whitelist, timeouts; exists on `idea/2026.1.5`, `idea/2026.2.3` and `master`)
- `plugins/mcp-server/src/com/intellij/mcpserver/impl/McpServerService.kt` (startServer authCheck, per-call header read on `master`, consent write on `start()` on `master`)
- `plugins/mcp-server/src/com/intellij/mcpserver/impl/McpServerConsentGate.kt` (headless consent skip, `master` only)
- `plugins/mcp-server/src/com/intellij/mcpserver/impl/util/network/mcp.sdk.util.kt` (`/sse`, `/stream` routing, session header, heartbeats)
- `plugins/mcp-server/src/com/intellij/mcpserver/impl/util/network/util.kt` (host validation)
- `plugins/mcp-server/src/com/intellij/mcpserver/impl/util/network/McpServerConnectionAddressProvider.kt` (URL shape)
- `plugins/mcp-server/mcpserver.stdio/src/McpStdioRunner.kt` (header name constants)
- `plugins/mcp-server/src/com/intellij/mcpserver/settings/McpServerSettings.kt` (`enableBraveMode`, `mcpServer.xml` storage)
- `plugins/mcp-server/src/com/intellij/mcpserver/util/execution.util.kt` on `idea/2026.2.3` (brave-mode confirmation path); `plugins/mcp-server/mcpserver.toolsets/src/util/execution.util.kt` on `master`
- `platform/core-api/src/com/intellij/idea/WellKnownCommand.java` (`mcpServer` to `HEADLESS`, present in `idea/2026.2.3` and `master`, absent in `idea/2026.1.5`)
- `platform/core-api/src/com/intellij/idea/AppMode.java` and `platform/platform-impl/bootstrap/src/com/intellij/platform/ide/bootstrap/ApplicationLoader.kt` (headless command dispatch)
- Commits: `4b368fde` (IJPL-227249, authored 2025-12-02, committed 2026-01-22, headless mode and CLI), `8553a190` (IJPL-227249 smart-mode wait and headless), `f289f723` (router config), `d707b4f0` (configuration wait, 2026-09-22, 2026.3 only), `624d27bd` and `ac8f362d` (WellKnownCommand as Java)
- Plugin registration: `plugins/mcp-server/resources/META-INF/plugin.xml`
- Docs: https://www.jetbrains.com/help/idea/mcp-server.html (2026.2, tool list)
- In-repo client capture: `build/mcp-servers/ij-proxy/cc-tools.json` (not a server payload)
- The `master` snapshot is dated 2026-10-28; its commit SHA is not recorded in this document.

## Integration points and next steps

Provider integration points for `vintelli-idea` (Refs #15, #21, #16):

- **spawn:** `bin/idea.sh mcpServer --project=<path> --invocation-mode=direct --port=<n>` (2026.2+ required). Prefer `--invocation-mode=direct` so Vintelli gets the whole single-tool surface, not the router-only view; keep the router option behind a toggle. Do not add `--allowed-tools=*` by default (see Security); the default whitelist is the four search tools.
- **parse readiness:** watch **stderr** for `* MCP Server started on port N`. The command is already headless in 2026.2+, so nothing needs to be passed to force headless.
- **health:** connect to `http://127.0.0.1:<port>/stream` and send `initialize` (POST with headers, expect `mcp-session-id` in the response headers), carrying `IJ_MCP_SERVER_PROJECT_PATH`. Verify that the `Host` header our SDK sends is `127.0.0.1:<port>`; the server rejects anything else with a 403.
- **timeout:** set `-Didea.mcp.server.smart.mode.timeout.seconds` so a stuck indexer fails fast (the default of 30 min is too generous for a managed provider). The configuration timeout (`-Didea.mcp.server.project.configuration.timeout.seconds`) is 2026.3 only and is ignored on 2026.2.x.
- **shutdown:** the starter blocks on `awaitCancellation()` and there is no documented graceful-stop protocol; SIGTERM with a grace period is the pragmatic approach.
- **port:** pass an explicit port (`--port=<n>`) so the stdout and stderr parse yields a deterministic URL. Use `-Didea.mcp.server.force.port` only when an exact port is required, accepting that a busy port fails startup.
- **auth:** no auth token is usable by Vintelli (see Security). Mitigate through the narrow tool whitelist and by keeping brave mode off.

Next steps:

1. **Runtime spike on a released distribution (IDEA 2026.2.x):** run the recipe above, capture stderr and stdout, capture `tools/list` via the MCP Java SDK against `/stream` (Refs #2), and compare tool behavior with and without UI. Check the Community-bundling question on the same install, the `idea.sh inspect` coexistence, and the fresh-config SDK prerequisites. Verify the pre-seeded `mcpServer.xml` format only if brave mode is ever enabled (Refs #2).
2. **Draft `ManagedIdeaProvider`** in `vintelli-idea` once the runtime probe confirms spawn, parsing, health checks and SIGTERM shutdown, mirroring the process-supervision shape planned for jdtls in the local provider (Refs #21, `jdtls` profile Refs #22, capability ports Refs #18).
3. **MCP Java SDK 2.1.0 compatibility check:** connect to `/stream` with the SDK client, verify `mcp-session-id` handling and per-request header propagation, and (depending on the outcome) document `/sse` as fallback (Refs #2).
4. **Cross-check the tool whitelist behavior** (default four search tools vs. full whitelist) and decide what Vintelli exposes by default on a managed headless IDEA (Refs #21, #15).
5. **Decide go/no-go** for `ManagedIdeaProvider` after step 1. The current decision is the PROVISIONAL go in the Decision section, conditional on the runtime probe. Brave mode is not a condition, because the recipe does not enable it (see Security).

## Follow-up issues

Work not executed in this spike is tracked in #48.
