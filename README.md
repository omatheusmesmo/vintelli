# Vintelli

[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/omatheusmesmo/vintelli/badge)](https://scorecard.dev/viewer/?uri=github.com/omatheusmesmo/vintelli)

A terminal IDE for Java with an IntelliJ IDEA-like layout.
It runs with open tooling only. IntelliJ IDEA is an optional extra.

## Why you will like it

- 🖥️ **Work where you already are.** A full IDE layout in your terminal, with no window to open.
- ⚡ **Native when you want it.** With a GraalVM or Mandrel toolchain, the installer builds one binary that needs no JVM to run.
- 🔓 **Open tools by default.** Maven, Gradle, terminal commands and Git (JGit) need no IDE.
- ▶️ **Start the application.** Detect Quarkus, Spring Boot and configured Java application commands, choose them from the toolbar, and keep the choice per project. See [run configurations](docs/run-configurations.md).
- 🤖 **AI agents in a side panel.** Choose OpenCode, Claude Code or pi over ACP (`Alt+8`), with executable availability shown before starting a session. See [agent setup](docs/acp-agents.md).
- 🔌 **Other agents can drive it.** The MCP server reads the shell state and pushes highlights and diffs into the TUI.
- 🧠 **Language support over LSP.** Configurable profiles, with jdtls as the first one.
- 🪲 **Optional Java debugging.** An experimental DAP provider uses Java Debug through jdtls. Toggle persistent source breakpoints from the editor gutter or with `Ctrl+F8`; see [Java Debug Adapter setup](docs/language-servers.md#java-debug-adapter).
- ⌨️ **Familiar keys and colors.** The `intellij` keymap is the default, and the themes come from IntelliJ IDEA's color files.

## Install

```bash
curl -fsSL https://raw.githubusercontent.com/omatheusmesmo/vintelli/main/scripts/install.sh | bash
```

Windows (PowerShell):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "irm https://raw.githubusercontent.com/omatheusmesmo/vintelli/main/scripts/install.ps1 | iex"
```

**You need git and JDK 25.** There is no published binary yet, so the installer builds Vintelli from source. It clones the repository into a temporary directory, builds it, installs the `vintelli` command and cleans up.

<details>
<summary>Installer options</summary>

Unix, passing options after `bash -s --`:

```bash
curl -fsSL https://raw.githubusercontent.com/omatheusmesmo/vintelli/main/scripts/install.sh | bash -s -- --dry-run
```

| Option | Effect |
|---|---|
| `--dry-run` | Print the planned actions without changing the system |
| `--prefix <dir>` | Install the `vintelli` command into `<dir>` |
| `--system` | Install system-wide instead of for the current user |
| `--version <tag>` | Install a specific release tag or git ref (default `latest`) |
| `--force` | Overwrite an existing installation without prompting |
| `--uninstall` | Remove the installed command and its runtime |
| `--source <dir>` | Build from a local clone you already have |

The same options exist in `install.ps1` on Windows. Full details: [docs/installation.md](docs/installation.md).

</details>

<details>
<summary>What the installer does</summary>

```mermaid
flowchart TD
    A["Run install script"] --> B{"Release binary for this OS and CPU?"}
    B -->|yes| C["Verify SHA-256 and install vintelli"]
    B -->|no| D["Clone repository to a temporary directory"]
    D --> E{"native-image found?"}
    E -->|yes| F["Build native runner with mvnw package -Dnative"]
    E -->|no| G["Build JVM package and install a launcher wrapper"]
    F --> H["Install binary and run smoke test"]
    G --> H
    C --> H
```

</details>

## Quick start

Open any project and go:

```bash
cd your-java-project
vintelli
```

`vintelli --help` lists every command, and `vintelli serve` runs it headless with the HTTP and MCP endpoints.

## How it works

```mermaid
flowchart LR
    TUI["TUI in your terminal"] --> Core["Vintelli core"]
    Web["Browser terminal (off by default)"] --> Core
    Core --> Local["Local providers (default)"]
    Core -.-> IDEA["IntelliJ IDEA (optional)"]
    Core -.-> LSP["Language servers (optional)"]
    Core -.-> ACP["ACP agents (optional)"]
    MCP["MCP clients (optional)"] -.-> Core
```

Local providers are the default path. The dashed connections are optional, and Vintelli starts without any of them.

## IntelliJ IDEA (optional)

Vintelli works without IntelliJ IDEA. Every capability has a standalone provider, and IDEA only adds its providers for Run, Terminal, Problems and call hierarchy when you connect it.

To connect IDEA:

1. In IDEA, open **Settings | Tools | MCP Server** and enable the server.
2. Click **Copy HTTP Stream Config** and use that URL as `vintelli.idea.mcp.url`.
3. Approve IDEA's consent dialog on the first tool call, or enable *brave mode* in the same settings. Brave mode lets tools run without that dialog, so enable it only with that risk in mind.

Vintelli reads these keys from `application.properties` or a profile:

| Key | Required | Purpose |
|---|---|---|
| `vintelli.idea.mcp.url` | yes | The streamable HTTP endpoint from IDEA. There is no default, because IDEA picks a free port. |
| `vintelli.idea.mcp.project-path` | no | The project IDEA should work on, sent as `IJ_MCP_SERVER_PROJECT_PATH`. |
| `vintelli.idea.mcp.auth-token` | no | The token IDEA requires when it is in restricted mode, sent as `IJ_MCP_AUTH_TOKEN`. |

For development, keep the settings in a `%dev.` profile:

```properties
%dev.vintelli.idea.mcp.url=http://127.0.0.1:<port>/stream
%dev.vintelli.idea.mcp.project-path=${PWD}
```

When `vintelli.idea.mcp.url` is set, the status bar shows the IDEA state, such as `IDEA: connected` or `IDEA: consent pending`. Healthy states are dimmed and problems are emphasized. The full reason is in the providers popup. Without the URL, the status bar is unchanged.

The complete setup, the states and the CLI commands are in [IntelliJ IDEA as an optional provider](docs/intellij-idea.md).

## Documentation

Start with [usage](docs/usage.md), the [keymap](docs/keymap.md) and [themes](docs/themes.md).

<details>
<summary>All documentation</summary>

| Topic | File |
|---|---|
| Architecture and stack | [docs/architecture.md](docs/architecture.md) |
| Installation details | [docs/installation.md](docs/installation.md) |
| Usage: run modes, CLI and TUI shell | [docs/usage.md](docs/usage.md) |
| Logging | [docs/logging.md](docs/logging.md) |
| Keymap | [docs/keymap.md](docs/keymap.md) |
| Themes and colors | [docs/themes.md](docs/themes.md) |
| File icons | [docs/file-icons.md](docs/file-icons.md) |
| MCP server, HTTP endpoints and browser terminal | [docs/mcp-server.md](docs/mcp-server.md) |
| IntelliJ IDEA provider | [docs/intellij-idea.md](docs/intellij-idea.md) |
| Native image | [docs/native-image.md](docs/native-image.md) |
| Language servers | [docs/language-servers.md](docs/language-servers.md) |
| ACP agents | [docs/acp-agents.md](docs/acp-agents.md) |
| Run configurations | [docs/run-configurations.md](docs/run-configurations.md) |

</details>

## Status

Vintelli is pre-release, with no published binary yet. See the GitHub issues for the plan (IDEA MCP integration, ACP agents, offline inspections, WebSocket surface). The native runner is built and tested in CI, see [docs/native-image.md](docs/native-image.md).

<details>
<summary>For AI agents and contributors</summary>

Setup and build (`sdk env` honors `.sdkmanrc` for Java 25):

```bash
sdk env
./mvnw package
java -jar vintelli-app/target/quarkus-app/quarkus-run.jar
./mvnw -B -ntp -T 1C -pl vintelli-app -am package -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false
./mvnw verify -Dnative -Dquarkus.native.container-build
```

Modules: `vintelli-app` (the application), `vintelli-tui` (TamboUI shell), `vintelli-core` (Quarkus extension), `vintelli-api` and `vintelli-jsonrpc` (plain Java), `vintelli-local`, `vintelli-lsp`, `vintelli-debug` and `vintelli-idea`.

Read [AGENTS.md](AGENTS.md) and [CONTRIBUTING.md](CONTRIBUTING.md) before changing code. The topic files in `docs/` hold the details.

</details>

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to set up, propose changes, and meet the review bar.

## License

Vintelli is licensed under the [Apache License, Version 2.0](LICENSE).

Debug supported Maven applications and tests from the toolbar, with a suspended
loopback JDWP port and process-tree Stop: [debug launch](docs/debug-launch.md).
Resume, pause and step through the selected stopped frame; the editor reveals
the frame and marks its current line separately from breakpoints.

The Java debugger architecture is recorded in [spike 385](docs/spikes/spike-385-native-java-debugger.md):
typed DAP with Java Debug via jdtls. The provider is opt-in and experimental. Breakpoints are
stored per project as line anchors; edits keep them on the same line, and truncating the file
removes anchors beyond the new end. The adapter reports pending, verified and rejected state.
