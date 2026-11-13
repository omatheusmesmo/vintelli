# ACP agents

Vintelli hosts agents through the official ACP Java SDK over stdio. Agent
processes are off by default. Install and authenticate the agents you want
before enabling them; Vintelli does not install packages or start an agent
while checking availability.

| Agent id | Process | Installation |
|---|---|---|
| `opencode` | `opencode acp` | Follow [OpenCode installation](https://opencode.ai/docs/) and its [ACP guide](https://docs.dev.opencode.ai/docs/acp/). |
| `claude` | `claude-agent-acp` | `npm install -g @agentclientprotocol/claude-agent-acp`; follow the [adapter setup](https://github.com/agentclientprotocol/claude-agent-acp) for authentication and prerequisites. |
| `pi` | `pi-acp` | `npm install -g pi-acp`; install and configure `pi` as described by the [adapter](https://github.com/svkozak/pi-acp). |

The adapters must be on the `PATH` inherited by Vintelli. Availability means
the executable was found; authentication, credentials and provider connectivity
are checked by the agent when it starts. Restart Vintelli after installing an
executable or changing `PATH`.

## Configure

Set these application properties (use `%dev.` when configuring dev mode):

```properties
vintelli.acp.enabled=true
vintelli.acp.default-agent=opencode
```

The built-in presets provide the names, commands and arguments in the table.
Override them or add another named agent with the same property structure:

```properties
vintelli.acp.agents.local.name=Local ACP agent
vintelli.acp.agents.local.command=/absolute/path/to/agent
vintelli.acp.agents.local.args=acp,--model,example
```

For an older Claude adapter installation, set
`vintelli.acp.agents.claude.command=claude-code-acp` instead. The upstream
adapter was renamed; the agent id in Vintelli remains `claude`.

Arguments use SmallRye Config list syntax. Each list item becomes one process
argument; commands are executable names or paths, never shell command lines.
Do not put credentials in these properties. Agents use their own authentication
and credential stores.

The older `vintelli.acp.probe.enabled`, `vintelli.acp.probe.agent-command` and
`vintelli.acp.probe.agent-args` settings still expose a `configured` agent.
It stays the default when the probe is enabled and `vintelli.acp.enabled` is
false. Enabling production agents does not enable the HTTP probe endpoints.

## Choose an agent

Open **ACP Agents** with `Alt+8`. Press Enter or `Ctrl+N` to choose an agent.
Use Up/Down and Enter, or click a row. The picker shows unavailable agents
and the selected agent's reason. Escape returns to the current conversation.
Each new conversation uses its own agent process and its tab shows the agent
name. Existing conversations keep their agent when you open another one.

Type a prompt and press Enter to send it. `Shift+Enter` inserts a newline,
`Ctrl+C` cancels an active turn, and `Ctrl+W` closes the conversation.
Permission requests wait for an explicit choice in the TUI.

The same catalog is available headlessly:

```bash
vintelli agents list
vintelli agents prompt --agent=opencode 'Explain this project'
```

Without `--agent`, the prompt command uses the configured default. The one-shot
command declines ACP permission requests because it has no interactive
permission chooser. For interactive approval, use the TUI.

## Earlier sessions

The chat service can list the sessions an agent stored for the project and
reopen one. Listing needs the agent to advertise `sessionCapabilities.list`,
and loading needs `loadSession`. An agent that advertises neither is refused
with a clear error and no session is registered. A loaded session replays its
history through the same updates as a live turn, and then accepts prompts like
any other session. The TUI does not offer this yet.

## Validation

The packaged-runner tests select an agent backed by the ACP twin, check streamed
messages and permission handling, and reject missing executables and unknown
agent ids before opening a session. Pilot tests cover keyboard and pointer
selection, unavailable states, scrolling and resized viewports. Tests do not
require a real model subscription or external agent installation.

Configuration follows the [Quarkus Config Mapping guide](https://quarkus.io/guides/config-mappings).
