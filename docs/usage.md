# Usage

Start Vintelli, run its commands and use the TUI shell (run, terminal and capability states).

## Run

Backend (headless, dev mode with live reload):

```bash
sdk env            # honors .sdkmanrc (Java 25)
./mvnw -pl vintelli-app -am quarkus:dev -Dquarkus.args=serve
```

TUI (default, real terminal required):

```bash
./mvnw package
java -jar vintelli-app/target/quarkus-app/quarkus-run.jar
```

Headless server (HTTP, MCP endpoint, web terminal):

```bash
java -jar vintelli-app/target/quarkus-app/quarkus-run.jar serve
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
`vintelli-core/deployment`. `VintelliMain` decides the surface: no argument
(or `--tui`) starts the shell, `serve` starts the headless server (HTTP, MCP
endpoint, web terminal), `--repl` the interactive console, and any other
argument one shot command execution. `--tui` stays as an alias for
compatibility.

Scripted, one command per process (`java -jar vintelli-app/target/quarkus-app/quarkus-run.jar <command>...`):

- `run` lists the detected run configurations; `run maven:compile` starts
  one and streams the output lines
- `terminal java --version` executes a command line through the terminal
  provider; a command the provider gates prints the confirmation hint and
  can be reissued with `terminal --yes <command>`
- `problems <file>` lists the problems reported for one file
- `inspect` prints the shell selection and the capability status rows, the
  same read model as the HTTP `/capabilities` endpoint and the MCP state tool
- `Alt+4` opens the `ACP Agents` side panel, where Enter starts a persistent
  ACP chat session against the configured stdio agent. Type prompts and press Enter to send, use
  Shift+Enter for a line break, Ctrl+N to open a session, and Ctrl+W to close
  the active session. Markdown streams live; tool calls and file diffs appear
  inline. The agent waits while a permission request is visible until you
  choose an option or decline it. The standalone `agents prompt <text>` command
  remains available for one-shot prompts. The agent process comes from
  `vintelli.acp.probe.agent-command` and `vintelli.acp.probe.agent-args`,
  shared with the HTTP probe surface.
- `help` (or `--help` / `-h`) lists the available commands or prints the usage of a named command; `version` (or `--version` / `-v`) prints the application version
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
Its headline reads `Run: <provider> (<provenance>)`; when no provider is
available it reads `Run unavailable: <reason>` (see "Capability states" below).
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

The status bar combines the shell message, live IDEA MCP connection state,
active provider for each capability, and the active editor's caret position,
encoding, line separator, and file type. Tool windows and selected capability
providers can contribute ordered widgets; the VCS provider refreshes its
branch label asynchronously. On narrow terminals the bar uses compact labels
and truncates to the available width; unavailable providers are marked with
`!`, with their reason shown when space allows. Provider selections are read
from the live capability registry.

Capability states: the Run, Terminal and Problems tool windows use the same
wording as the status bar, and the project pane footer shows the Read File
state. The text is read from the registry snapshot off the render thread. The
active provider reads `Problems: jdtls (jdtls over stdio)`, and an unavailable
capability reads `Problems unavailable: <reason>` with the registry reason as
is. Each higher ranked provider that was passed over reads `skipped idea: not
connected`: in Problems and Run it is its own line, in Terminal it is its own
line while no session runs, and in the project pane footer the skipped entries
share one line. The first state appears asynchronously after start. Problems
and Run re-read the registry on `r`, Terminal re-reads when a session starts,
and the project tree re-reads on its refresh action, so a provider that became
healthy is picked up by the next refresh. These windows and the project tree
never call the registry while they render. Long lines are clipped to the pane
width, so the project pane footer, which is 30 columns wide, cuts long reasons.

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
for confirmation). `F10` opens the menus, the menu icon at the left of the
toolbar does the same with the mouse, and long unavailable reasons can be
scrolled with Page Up/Down.
The editor shows clickable project path breadcrumbs and, when an LSP provider
returns document symbols, the containing type and method. `Alt+Home` focuses
the breadcrumbs; arrows move between segments, Enter selects one, and Escape
returns focus to the editor.
`Ctrl+O` opens Go to File, `Alt+F` searches file contents, `F12` requests a
definition, `Alt+U` requests references, and `F8` moves to the next diagnostic, and `Alt+I` folds or unfolds the import block.
In the editor, a left click places the caret on the character under the pointer
and focuses the editor. Drag with the left button to select text; releasing ends
the drag, and moving the caret or editing clears the selection. The mouse wheel
scrolls the document under the pointer by three rows without moving the caret,
and the next key press brings the caret back into view. Clicks on the line
numbers, the tab bar and the breadcrumbs do not move the caret. The editor has
no copy action yet, so this selection stays inside Vintelli. Vintelli captures
the mouse: to select with the terminal itself, hold its bypass modifier, usually
Shift (Option in iTerm2).
In Go to File, Find in Files and Search Everywhere, click a result to select it,
press Enter or double-click to activate it, and use the mouse wheel to scroll.
Clicking outside an overlay dismisses it without triggering controls underneath.
The Problems window refreshes diagnostics for files open in the editor with
`R`; `A`, `E`, and `W` show all, errors, or warnings. Arrow keys move through
the list, Enter opens the selected file at its diagnostic line, clicking a row
navigates there, and the mouse wheel scrolls the list. The window shows the
selected diagnostics provider and distinguishes loading, unavailable, error,
and empty results.
In Structure, click a symbol row to select it, press Enter to navigate to its
file and line, and use the mouse wheel to scroll long outlines.
In the project panel, a left click selects the row under the pointer and focuses
the panel, and a double click on a row does what Enter does: it opens a file or
toggles a directory. A click on the expand marker of a directory (`+` and `-`, or
the arrows with Nerd icons) toggles it. The list keeps the selected row in view
when the keyboard or the wheel moves the selection. Clicks below the last row
change nothing, and a click while a new name or a rename is being typed is
ignored, so the typed input and its target stay as they are.
Panes have no frames: each one starts with a header row, and the separators
are the one-column line between the project pane and the editor (and before
the right tool stripe) and the one-row line above the bottom tool windows.
A two-column icon strip sits on each side of the shell. The left strip holds
the Project icon on top and the bottom tool windows at its bottom, and the
right strip holds the right tool windows. Click an icon to show a hidden
window, activate a visible one, or hide the active one; a hidden window frees
its space and its icon stays. Tool window tabs show the shortcut digit before
the title, underline the selected tab, and answer clicks.
Drag the vertical separator to resize the project pane or the horizontal one
to resize the bottom tool window pane. `Alt+=`/`Alt+-` resize the project pane and
`Alt+.`/`Alt+,` resize the bottom pane from the keyboard; after the first resize the
ratios are saved per project under the user config directory and scale with the
terminal size, and until then the panes keep fixed default sizes.
`Tab`/`Shift+Tab` move focus through the focusable panels
(`#project-panel`, `#editor-panel`), clicking a panel focuses it, escape leaves
captured input (a tool window that claims the keyboard) and returns to shell
navigation, and `Ctrl+C` quits. Open editor tabs and their unsaved recovery
text are also restored from the user config directory. Theme switching is
exposed through the named action `shell.theme.switch`
(`SwitchShellThemeAction`), for the keymap work to bind a key to; the switch
applies on the next frame without a restart.
