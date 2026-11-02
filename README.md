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
- TamboUI 0.6.0 (`tamboui-tui`, `tamboui-toolkit` with the CSS style engine, Markdown support, JLine backend)
- Quarkiverse MCP Server 2.0.4 (Vintelli as an MCP server for external agents)
- MCP Java SDK 2.1.0 (client: IntelliJ IDEA MCP server)
- LSP4J 1.0.0 (client: generic language server client with profiles)
- JGit 7.7.1 through the Quarkiverse `quarkus-jgit` extension (local Git provider for the VCS surfaces)
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

## CLI

Vintelli ships an AEsh 3.18.6 command layer, plain library, no
`quarkus-aesh` extension (that extension is legacy): the commands are CDI
beans annotated with `@CommandDefinition`, and the `aesh-processor` module
generates the command metadata at build time, so there is no reflective
annotation-scan fallback and the native image needs no extra configuration
beyond the registry classes derived by the build steps in
`vintelli-core/deployment`. `VintelliMain` decides the surface: `--tui`
starts the shell, `--repl` the interactive console, any other argument one
shot command execution, and no argument the headless server.

Scripted, one command per process (`java -jar vintelli-app/target/quarkus-app/quarkus-run.jar <command>...`):

- `run` lists the detected run configurations; `run maven:compile` starts
  one and streams the output lines
- `terminal java --version` executes a command line through the terminal
  provider; a command the provider gates prints the confirmation hint and
  can be reissued with `terminal --yes <command>`
- `problems <file>` lists the problems reported for one file
- `inspect` prints the shell selection and the capability status rows, the
  same read model as the HTTP `/capabilities` endpoint and the MCP state tool
- `agents prompt <text>` sends one prompt to the configured ACP agent over
  stdio and reports the streamed chunks and the stop reason; the agent
  process comes from `vintelli.acp.probe.agent-command` and
  `vintelli.acp.probe.agent-args` (shared with the HTTP probe surface)
- unknown names fail with exit code 127, failed commands with 1, misuse
  with 2, so scripts can branch on the outcome

The interactive console, with history and completion:

```bash
java -jar vintelli-app/target/quarkus-app/quarkus-run.jar --repl
vintelli> inspect
vintelli> exit
```

Keystrokes and commands are the same as the one shot surface; `exit` and
`quit` leave the console.

`vintelli-core` is a Quarkus extension (parent `vintelli-core-parent` with
`vintelli-core/runtime` and `vintelli-core/deployment`) and the home of
Vintelli's build-time glue. Its content is
the MCP client support: CDI producers for the MCP SDK JSON mapper and schema
validator (the SDK's `ServiceLoader` lookup does not work in a native image)
and a build step that registers the `McpSchema` types for reflection; the
Host and Origin guard (`McpEndpointGuard`) that protects the local MCP and
inspect endpoints is registered there too. Only
`vintelli-idea` and `vintelli-app` depend on it; `vintelli-api` and
`vintelli-jsonrpc` stay Quarkus-free.

`vintelli-local` is the standalone provider behind the `vintelli-api`
capability ports: it runs Maven and Gradle goals and tasks through real
processes (wrapper first, installed command as fallback, always an argument
list and never a shell command line), executes explicitly confirmed terminal
commands through the host shell, and reads
the project files within a configurable project root
(`vintelli.local.project-root`, the working directory by default) refusing
every traversal or symlink escape. A timeout
(`vintelli.local.process-timeout`, minutes) terminates each process tree,
and cancellation works the same way. Its providers are CDI beans, so the
capability registry ranks them like every other provider.

The Run tool window lists configurations from the selected `RunCapability`
provider, shows the provider and its provenance, and sends output to the console.
Use Up/Down or `j`/`k` to select a configuration, Enter or the Run toolbar
action to start it, and Stop or the `Stop Current Run` palette action to request cancellation. Maven and
Gradle goals/tasks remain available without IDEA; an IDEA provider is optional.

Decisions of the local provider:

- One shot terminal commands and interactive terminal sessions start inside
  the project root. A relative initial working directory resolves against the
  root, and a directory that lands outside it, through `..` or a symlink, is
  refused before a process starts. A timed out one shot command reports the
  output read so far.
- The Terminal tool window starts a persistent host shell for the window. It
  keeps shell state, including the current directory, between commands. The
  initial directory is checked against the project root; after startup, the
  shell can change directory and access the filesystem with the current user's
  permissions. It is not a filesystem sandbox. Starting a session requires
  confirmation, and potentially dangerous or compound commands prompt again.
- `vintelli.local.terminal.shell` selects the shell for both one shot commands
  and persistent terminal sessions. `auto` is the default: Unix honors the
  current `$SHELL` executable, including an absolute path outside `PATH`, then
  falls back to installed common shells; Windows uses Command Prompt through
  `%COMSPEC%`. Explicit choices are `powershell`, `cmd`, `git-bash`, `sh`,
  `bash`, `zsh` and `fish`. An absolute executable path can select another
  Unix shell or a custom installation of a known shell. The path is passed as
  one executable argument, not parsed as a command string. An unavailable
  explicit choice fails at startup instead of silently switching shells.
  PowerShell can also use `pwsh` on Unix when installed. Git Bash is discovered
  under common Git for Windows install roots. The resolver accepts a shell id
  or absolute executable path, not an arbitrary command line.
- Shell command confirmation remains a heuristic. It catches the dangerous
  command forms Vintelli knows, but aliases, functions, shell-specific syntax
  and indirection can bypass it; confirmation is not a filesystem sandbox.
- POSIX sessions use the host `script` utility to allocate a PTY, so that
  utility must be installed. Its command string contains only the resolved,
  quoted executable path and Vintelli's fixed interactive arguments; user
  command text is sent over the PTY input stream. Windows sessions use pty4j
  with ConPTY enabled.
  Terminal resizing is currently supported on Windows; the POSIX `script`
  wrapper does not expose resize control.
- Build wrappers are chosen per host: `mvnw` or `gradlew` on Unix (executable
  only), `mvnw.cmd` or `gradlew.bat` on Windows, run through `cmd /c`. The
  Unix script is never tried on Windows.
- A run completes as soon as the main process has exited and the output pipe
  reached its end, with no wait. When a background child keeps the pipe open
  after the main process exits, the run completes when the pipe stays idle for
  the 5 second drain grace or when 5 seconds have elapsed since the main
  process exited, whichever comes first, and that child's later output is
  dropped. The hard cap on the wait for the pipe also holds for output with no line
  terminator; it does not bound time spent inside a consumer of the output, and
  a consumer that blocks delays the end of the run until it returns. On
  Linux that child is reparented when its parent exits, so it may keep running
  after the run has completed. On abandonment the output stream is closed and
  the reader stops as soon as its pending read returns.

The tool window SPI lives in `vintelli-tui` because its render and key
handling signatures use TamboUI types: a `ToolWindow` declares an id, a
title, a `char` shortcut, an `ordinal` position, rendering and input
handling. Implementations are CDI beans discovered by the tool window
registry; windows that navigate the editor receive a shell callback. The
shell state read model stays in `vintelli-api` (`ShellStateSnapshot` and
`ShellStateView`) without TamboUI, so the MCP server can read what the user is
looking at, and the write ports
`ShellHighlightPort` and `ShellDiffViewPort` (implemented by the shell) let
it push a highlight and a diff back into the TUI.

The shell runs at the Toolkit level with a CSS style engine: the element
tree of `VintelliShell` (`#shell`, `#menu-bar`, `#tool-tabs`,
`#project-panel`, `#editor-panel`, `tool-<id>` per tool window and
`#status-bar`) resolves every color from the active stylesheet, so no hex
value is hardcoded in shell code. Two themes ship with the app, both
derived mechanically from the official IntelliJ platform files of IDEA
Ultimate 2026.2.3 (build IU-262.10968.63), with the source key cited per
palette entry in the stylesheet headers and checked by
`ThemePaletteSourceTest` against verbatim excerpts of the platform files
committed under `vintelli-tui/src/test/resources`:

- `darcula`: classic Darcula chrome (`themes/darcula.theme.json`) plus the
  classic Darcula editor color scheme (the `Darcula` scheme inside
  `DefaultColorSchemesManager.xml`).
- `dark` (default): New UI Dark chrome (`themes/expUI/expUI_dark.theme.json`)
  plus the New UI Dark editor color scheme
  (`themes/expUI/expUI_darkScheme.xml`).

Themes are configurable at three levels:

- `vintelli.tui.theme` picks the default theme of the session (`dark` by
  default). An unknown name fails fast before the terminal is attached: in
  `--tui` mode the process prints
  `Vintelli cannot start the TUI: <message>` and exits with code 2, with the
  available names in the message. Headless runs (the MCP server, the REST
  surface) never load a theme at all because the shell and its engine are
  resolved lazily on first use.
- A user theme file in the config directory, default
  `vintelli/themes.tcss`. The directory is resolved by the shared
  `ConfigDirectory` resolver: the `vintelli.config.dir` system property, or
  the `VINTELLI_CONFIG_DIR` environment variable, first (the same override
  also relocates the keymap and the file icon mapping), then
  `XDG_CONFIG_HOME` when absolute, then `APPDATA` on Windows, then
  `$HOME/.config`. The file is relocatable to any path with
  `vintelli.tui.theme.file`. Note that the shared `vintelli.config.dir`
  override is honored by themes through every MicroProfile Config source
  (system property, environment variable, `application.properties` or a
  `.env` file), while the keymap and the file icon mapping read only the
  system property and the `VINTELLI_CONFIG_DIR` environment variable, so a
  value set through `application.properties` or `.env` relocates the themes
  but not those two. The file is stylesheet text plus one optional
  `@theme` directive line, so it is not pure CSS:
    - without the directive it is a patch: its rules are appended to every
      shipped theme and override matching selectors,
    - `@theme name` defines a theme of its own (a shipped name replaces that
      theme), which must declare every palette variable,
    - `@theme name extends dark` builds `name` on top of a shipped theme, so
      one override does not need the full palette.
  Example of a patch:

  ```
  .menu-entry-active { color: #2F65CA; background: #DEDEDE; }
  ```

  Example of a derived theme:

  ```
  @theme midnight extends dark
  $panel: #101010;
  ```

  A single file may carry at most one `@theme` line; an unreadable, unparsable
  or incomplete file fails fast with the file name, the position and the
  missing palette variables in the message.
- The named action `shell.theme.switch` switches to the next registered
  theme at runtime (cycling through the registration order, so user themes
  participate). The keymap binds a key to it, see the key map below. Both
  themes render the whole shell and switch without a restart.

The chrome palette (menu bar, tab bar, panel borders, status bar) and the
editor palette (`$editor-*`: text, keywords, strings and comments) live in
separate sections of each stylesheet. Shell code exposes the editor palette
through `ShellEditorPalette` in `vintelli-api` so an editor surface follows
the editor color scheme of its theme instead of the UI chrome.

The main palette entries as CSS variables, with the rendered hex, the 256
color index the shell maps them to when it runs without truecolor, and the
official source key. Reuse notes say where a value was chosen from a
neighbouring official element instead of a role-named one:

| variable | darcula | index | dark | index | source |
|---|---|---|---|---|---|
| `$panel` | `#3C3F41` | 237 | `#2B2D30` | 236 | ui `*` `background` |
| `$chrome-fg` | `#BBBBBB` | 250 | `#DFE1E5` | 254 | ui `*` `foreground` |
| `$chrome-fg-strong` | `#DEDEDE` | 253 | `#FFFFFF` | 231 | chosen from `selectionForeground` / `SearchEverywhere.Tab.selectedForeground` |
| `$selection-bg` | `#2F65CA` | 26 | `#2E436E` | 239 | ui `*` `selectionBackground` |
| `$tab-selected-bg` | `#365880` | 60 | `#3574F0` | 69 | chosen from `Button.default.startBackground`, New UI `ToolWindow.Button.selectedBackground` |
| `$border` | `#515151` | 239 | `#1E1F22` | 234 | ui `MenuBar.borderColor`, New UI `Borders.ContrastBorderColor` |
| `$editor-border` | `#515151` | 239 | `#43454A` | 238 | ui `MenuBar.borderColor`, New UI `ToolWindow.Stripe.separatorColor` |
| `$editor-bg` | `#2B2B2B` | 235 | `#1E1F22` | 234 | editor scheme `TEXT` background |
| `$editor-fg` | `#A9B7C6` | 146 | `#BCBEC4` | 250 | editor scheme `TEXT` foreground |
| `$editor-keyword` | `#CC7832` | 173 | `#CF8E6D` | 173 | editor scheme `DEFAULT_KEYWORD` |
| `$editor-string` | `#6A8759` | 65 | `#6AAB73` | 71 | editor scheme `DEFAULT_STRING` |
| `$editor-comment` | `#808080` | 244 | `#7A7E85` | 244 | editor scheme `DEFAULT_LINE_COMMENT` |

The depth the shell renders with is chosen by the `vintelli.tui.color-level`
property:

| Value | Colors |
|---|---|
| `auto` (default) | 24-bit truecolor when `COLORTERM` contains `truecolor` or `24bit` (case insensitive), 256 colors otherwise |
| `truecolor` | the theme hex values, emitted as `38;2;r;g;b` |
| `256` | the index column above, emitted as `38;5;n` |

TamboUI never downgrades on its own and reads no terminal variable, so the
shell picks the representation before the styles are resolved (spike
`docs/spikes/spike-27-keyboard-and-colors.md` documents the behavior). The
detection of `auto` reads `COLORTERM` through MicroProfile Config, so it sees
the environment variable of the process; an unknown property value fails fast,
naming the property and the allowed values. In the `256` depth every hex value
of the active stylesheet is replaced by the nearest fixed palette index before
the stylesheet is parsed, shipped and user sheets alike. Indices 0 to 15 are
never candidates: they are the terminal's own palette and render differently
per terminal, so the three table rows documented below 16 move to 231 (white),
69 (`#3574F0`) and 244 (`#7A7E85`). The hex values stay the source of truth,
and a section of the inspection payload reports the palette the active depth
serves (see `GET /tui/render` below).

The status bar combines the shell message, live IDEA MCP connection state,
active provider for each capability, and the active editor's caret position,
encoding, line separator, and file type. Tool windows and selected capability
providers can contribute ordered widgets; the VCS provider refreshes its
branch label asynchronously. On narrow terminals the bar uses compact labels
and truncates to the available width; unavailable providers are marked with
`!`, with their reason shown when space allows. Provider selections are read
from the live capability registry.

Keys: `1-4` (or `Alt+1`..`Alt+4`) switch tool windows (Run, Terminal,
Problems, ACP Agents), arrows and `PageUp`/`PageDown`/`Home`/`End` navigate
the live project tree (hjkl in the `vim` profile), and every selection is
mirrored into shell state and the status line. The tree shows file and folder
icons and directory disclosure arrows, using ASCII markers when Nerd Font icons
are disabled. Tree connectors use Unicode when the terminal's locale and output
charset support it, independently of the Nerd Font icon mode. Set
`vintelli.icons=nerd` for the richer terminal presentation.
`Enter` toggles a directory or opens the selected file in the built-in editor.
With the project panel focused, `F5` refreshes the tree, `F7` creates a file,
`F9` creates a directory, `F2` renames the selected entry, and `Delete` asks
for confirmation before removal. Project mutations use the configured local
file provider; names must stay within the project and rename never replaces an
existing entry. In the editor, `Ctrl+S` saves, `Ctrl+Z`/`Ctrl+Y` undo and redo,
`Alt+]`/`Alt+[` switch editor tabs, and `Ctrl+W` closes a tab (dirty tabs ask
for confirmation). `F10` opens the menu bar; menu headings also respond to
mouse clicks, and long unavailable reasons can be scrolled with Page Up/Down.
`Ctrl+O` opens Go to File, `Alt+F` searches file contents, `F12` requests a
definition, `Alt+U` requests references, and `F8` moves to the next diagnostic, and `Alt+I` folds or unfolds the import block.
The Problems window refreshes diagnostics for files open in the editor with
`R`; `A`, `E`, and `W` show all, errors, or warnings. Arrow keys move through
the list, Enter opens the selected file at its diagnostic line, clicking a row
navigates there, and the mouse wheel scrolls the list. The window shows the
selected diagnostics provider and distinguishes loading, unavailable, error,
and empty results.
`Alt+=`/`Alt+-` resize the project pane and `Alt+.`/`Alt+,` resize the bottom
tool window pane; the ratios are saved per project under the user config
directory. `Tab`/`Shift+Tab` move focus through the focusable panels
(`#project-panel`, `#editor-panel`), clicking a panel focuses it, escape leaves
captured input (a tool window that claims the keyboard) and returns to shell
navigation, and `Ctrl+C` quits. Open editor tabs and their unsaved recovery
text are also restored from the user config directory. Theme switching is
exposed through the named action `shell.theme.switch`
(`SwitchShellThemeAction`), for the keymap work to bind a key to; the switch
applies on the next frame without a restart.

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
tests), then the shared `vintelli.config.dir` override (system property or
`VINTELLI_CONFIG_DIR` environment variable), then `XDG_CONFIG_HOME` (Linux, if
absolute), then `APPDATA` (Windows), then `$HOME/.config`. The user file is validated: unknown action
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

## File icons

The `icons` package of `vintelli-tui` maps a file or folder name to an icon.
The mapping is data: the built-in resource
`dev/omatheusmesmo/vintelli/tui/icons/file-icons.properties` lists, per exact
file or folder name (`name.pom.xml`, `name..git`) and per extension
(`ext.java`), a Nerd Font glyph, a pure ASCII marker and an optional ANSI
color. Lookup order for a file: exact name, then the extension (case
insensitive, after the last dot), then the default file icon. Folders use the
exact name, then the default folder icon. Only the last path segment is
matched, so whole paths can be passed.

Two faces render the same mapping, chosen by the `vintelli.icons` system
property:

| Value | Face |
|---|---|
| `auto` (default) | AEsh detects terminal identity; kitty, Ghostty and WezTerm select Nerd Font glyphs, while other terminals use ASCII markers |
| `nerd` | Nerd Font glyphs, for a terminal with a patched font |
| `ascii` | ASCII markers, colored, for exact terminal compatibility |

AEsh's environment-only detection cannot see the font of the terminal, so the
application only trusts the terminals listed above. On other terminals (IDE terminals, VS Code, iTerm2,
Windows Terminal, plain `xterm` over SSH) a glyph from the private use area
shows as a tofu box, a blank or an overlapping character, depending on the font.
Set `vintelli.icons=nerd` there only when the font is patched. Each entry
defines both symbols with the same cell width, so switching the face does not
shift the columns of a tree. The glyph width was not verified on any terminal:
a consumer must reserve `FileIcon.symbolWidth(mode)` cells plus one separator
cell after the icon.

A user file `vintelli/file-icons.properties` inside the config directory
(the shared `vintelli.config.dir` override first, then `XDG_CONFIG_HOME`,
then `APPDATA`, then `$HOME/.config`) is layered on top
with the same keys and is optional when missing. The `vintelli.icons.file`
property names an explicit user file instead and must exist. Each key
overrides exactly the field it names, so `ext.java.ascii=Jv` changes only the
ASCII marker of Java files; keys are case-insensitive. An invalid key, a
duplicate key, an unknown color name or an unknown `vintelli.icons` value
fails fast and names the source.

Project tree connectors are detected separately from icon fonts. Vintelli uses
the `├──`, `└──`, and `│` line characters when the active locale is UTF-8 (or
unset) and the output charset can encode them. `TERM=dumb`, an explicitly
non-UTF-8 locale, or an output charset that cannot encode the connectors selects
the ASCII fallback.

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

### Host and Origin guard

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

`GET /inspect/editor` and its `POST /open`, `/insert`, `/undo` and `/save`
actions expose a small headless editor inspection surface for native
integration tests and local debugging. It is off unless
`vintelli.editor.inspect.enabled=true`; open paths remain confined to the
configured project root, and saves retain the editor's external-change checks.

### Browser terminal

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

### IntelliJ IDEA as an optional provider

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

Status bar states reported by the IDEA contributor:
- `off` - no URL configured or client closed
- `on` - connected and initialized
- `down` - endpoint unreachable or not found
- `auth` - authentication required
- `wait` - consent pending on the IDEA side
- `slow` - request timed out
- `error` - classified failure

Startup is unaffected when IDEA is absent or unreachable.

## Native image

The project is kept reflection-free (final beans, no client proxies) so it is
native-ready. Verify with the `native` profile and Mandrel 25 from SDKMAN:

```bash
./mvnw package -Dnative
```

The native runner is written to `vintelli-app/target/`.

Integration tests (`*IT`) run against the native runner in CI with
`./mvnw verify -Dnative -Dquarkus.native.container-build`. `GET /tui/render`
renders one offscreen frame of the live shell through the CSS style engine
and returns the tool window tab row, the editor area, the status row and the
active theme as JSON; it exists so the render pipeline can be validated on
the native image and is gated behind `vintelli.inspect.enabled` (read-only
and offscreen, never driving the TUI event loop). The theme stylesheets are
resources of the image, included by the `ShellThemeProcessor` build step of
`vintelli-core/deployment`.

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

## Language servers

`vintelli-lsp` hosts the generic LSP client: it launches a language server from
a profile through the LSP4J launcher (executor configured with daemon threads
so the process never lingers after a session), runs `initialize`/`shutdown`,
serves `didOpen`/`didChange`/`didClose`, the pushed diagnostics of
`textDocument/publishDiagnostics`, hover, definition, references, completion
and the `callHierarchy/*` requests. The diagnostics and call hierarchy ports of
`vintelli-api` are implemented by providers whose identifier is the active
profile, and the `vintelli-core` extension registers the LSP4J types and the
four dynamic proxies for the native image from the Jandex index at build time,
so an LSP4J upgrade needs no hand edited reflection config.

Profiles are configuration only. The `jdtls` profile of issue #20 is the first
tenant:

```properties
vintelli.lsp.profiles.jdtls.command=jdtls
vintelli.lsp.profiles.jdtls.args=-data,/home/me/workspace-vintelli
vintelli.lsp.profiles.jdtls.workspace-root=/home/me/projects/app
vintelli.lsp.profiles.jdtls.initialization-options={"bundles":["..."]}
```

`vintelli.lsp.profile` picks the serving profile when several are configured.
A missing binary or a server still indexing never breaks a call: the selection
and the status row carry an explicit unavailable reason instead.
`/lsp/diagnostics?file=...`, `/lsp/callers`, `/lsp/callees`, `/lsp/hover`,
`/lsp/definition`, `/lsp/references`, `/lsp/completion` and `/lsp/symbols`
expose the session
over HTTP for the native ITs, which run against the fake language server of
`vintelli-lsp`; they answer 404 unless
`vintelli.lsp.debug-endpoint.enabled=true`.

### Real jdtls integration test

`JdtlsFixtureTest` in `vintelli-lsp` runs the `LspClient` against the real jdt.ls
on the Maven fixture project in `vintelli-lsp/src/test/resources/jdtls-fixture`
(one clean class and one class with a type mismatch). It checks the initialize
handshake, the error diagnostic with its range, a hover on a method and a
shutdown that leaves no server process behind. The test is skipped unless
`VINTELLI_JDTLS_HOME` points to an extracted jdt.ls archive.

Run it locally with a Java 25 runtime (jdt.ls 1.63.0 does not start on Java 21,
see issue #100):

```shell
VINTELLI_JDTLS_HOME=/opt/jdt-language-server ./mvnw -pl vintelli-lsp -am verify
```

The `LSP jdtls` workflow (`.github/workflows/lsp-jdtls.yml`) runs the test on
pull requests that touch `vintelli-lsp` or `vintelli-core`, and on demand. It is
not a required check. It fails early unless the runner has Java 25, downloads
the pinned jdt.ls 1.63.0 milestone archive from `download.eclipse.org`, checks
its SHA-256 against the value pinned in the workflow, caches the archive by
version and extracts it into the runner temp directory. To bump jdt.ls, change
the version, build, archive name, URL and SHA-256 env values together; take the
checksum from the `.sha256` file published next to the archive.

`/vcs/status` and `/vcs/diff?file=...` expose the Git provider through its
`VersionControlCapability` port so the native ITs drive JGit inside the image
over a real temporary repository the test prepares through JGit before
boot. They answer 404 unless `vintelli.vcs.debug-endpoint.enabled=true`; a
selection without a healthy provider answers 503 and a failed capability
result (not a repository, a path outside the project root) answers 409 with
the provider's reason. Confirmed `PUT /vcs/stage`, `PUT /vcs/unstage`,
`POST /vcs/commit` and `PUT /vcs/branch` operations use the same opt-in debug
setting and require `confirm=true`; branch switching accepts existing local
branches only and lets JGit refuse any checkout that would overwrite work.
The project root may sit below the repository root:
the provider confines every requested diff path and every status entry to the
project root, so no answer carries state from outside the project, and a
status longer than its bound says so with `truncated`. JGit needs no `git`
executable for status and diff; it may spawn one only to discover system wide
configuration when such a file exists.

The TUI VCS refresh marks changed files in the Project tree and editor tabs;
the active file's gutter marks lines changed against `HEAD`, and the VCS menu
can open that file's unified diff or the full working tree diff. Its write
actions stage or unstage the active file, commit staged changes, and switch
local branches after an on-screen confirmation.

## Status

See the GitHub issues for the implementation plan (IDEA MCP integration, ACP
agents, offline inspections, WebSocket surface, native smoke test).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to set up, propose changes, and
meet the review bar.

## License

Vintelli is licensed under the [Apache License, Version 2.0](LICENSE).
