# Usage

Start Vintelli, run its commands and use the TUI shell (run, terminal and capability states).

## Run

Backend (headless, dev mode with live reload):

Dev mode only starts from the `vintelli-app` module directory:

```bash
sdk env            # honors .sdkmanrc (Java 25)
./mvnw -pl vintelli-app -am install -DskipTests
cd vintelli-app && ../mvnw quarkus:dev -Dquarkus.analytics.disabled=true -Dquarkus.args=serve
```

TUI (default, real terminal required):

```bash
./mvnw package
java -jar vintelli-app/target/quarkus-app/quarkus-run.jar
```

While the TUI runs, the terminal's own default background takes the frame
tone of the active theme (the `panel` variable), so the padding and margin the
terminal draws around the grid match the shell. It is set with the OSC 11
escape sequence and reset with OSC 111 when the TUI stops, including after a
crash or SIGTERM; a theme switch moves it at once. Vintelli writes the sequences
only when the terminal is attached, its `TERM` (or `TERM_PROGRAM`) names one of
alacritty, foot, ghostty, kitty, WezTerm or xterm, and the session is local (no
`SSH_CONNECTION`, `SSH_CLIENT` or `SSH_TTY`). Headless runs, tape recordings and
the `serve` surface never write them. Some terminals ignore or mishandle OSC 11:
set `vintelli.tui.terminal-background=false` (or the environment variable
`VINTELLI_TUI_TERMINAL_BACKGROUND=false`) to leave the terminal background alone.

A name wider than its pane cannot be read in full. The selected row of the
project tree scrolls its name, and the phases, modules and status line of the
Maven window scroll their selected line, like a marquee: the name waits one
second, moves one cell every 150 ms, waits at its end, moves back and waits
again. Only the selected row that is cut moves, and a name that fits never does.
The status line shows the full text of that selected row while it is cut. The
frames for the motion are requested only while a name moves, so an idle shell
stays idle. Set `vintelli.tui.marquee=false` (or `VINTELLI_TUI_MARQUEE=false`) to
turn the motion off; the project tree then cuts the selected name with an
ellipsis at the end.

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
  one and streams the output lines. Application starts such as
  `run maven:quarkus:dev` appear before build phases when the corresponding
  plugin is declared; see [run configurations](run-configurations.md).
- `terminal java --version` executes a command line through the terminal
  provider; a command the provider gates prints the confirmation hint and
  can be reissued with `terminal --yes <command>`
- `problems <file>` lists the problems reported for one file
- `inspect` prints the shell selection and the capability status rows, the
  same read model as the HTTP `/capabilities` endpoint and the MCP state tool
- `Alt+8` opens the `ACP Agents` side panel, where Enter opens the agent picker.
  Select an available agent with Up/Down and Enter, or click its row, to start a persistent
  ACP chat session. Type prompts and press Enter to send, use
  Shift+Enter for a line break, Ctrl+N to open a session, and Ctrl+W to close
  the active session. Markdown streams live; tool calls and file diffs appear
  inline. The agent waits while a permission request is visible until you
  choose an option or decline it. The standalone `agents prompt <text>` command
  remains available for one-shot prompts; `agents list` shows agent availability
  and `agents prompt --agent=opencode <text>` selects an agent explicitly.
  Enable `vintelli.acp.enabled` and see [agent setup](acp-agents.md) for presets,
  installation and compatibility with the older probe configuration.
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
  Ctrl+L clears the visible output and keeps the typed input. A line that is
  only `clear` does the same without being sent to the shell; a compound line
  such as `clear && ls` is sent as usual and does not clear the output.
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

The status bar is one row. On the left is the breadcrumb of the active file, from
the project root down to the file, followed by the containing type and method
when the LSP provider returns document symbols. Each segment is clickable, and
`Alt+Home` focuses the breadcrumb: arrows move between segments, Enter selects
one, and Escape returns focus to the editor. On the right, from left to right:
the shell message (a running task such as loading or connecting shows here), a
`⚠ N providers degraded` indicator only while a capability has no healthy
provider, the caret as `line:column`, the line separator, the encoding, the
indent detected from the file (`4 spaces` or `Tab`, hidden for a file with no
indented line), and a read only marker, a lock glyph in the Nerd Font face and
`Read-only` otherwise, shown only for a read only file. Two blank columns separate the
items. The `!` replaces `⚠` when the terminal has no Unicode. Clicking the
indicator opens a Providers popup with every provider state, the reason of an
unavailable one included; Escape or a click elsewhere closes it. The branch is
shown in the toolbar. On narrow terminals the leftmost breadcrumb segments go
first, then the indent, then the line separator and encoding. The message
shortens to `Ready` before it is clipped, and the caret and the file name stay
last. Provider selections are read from the live capability registry.

Capability states: the Run, Terminal and Problems tool windows use the same
wording as the provider popup, and the project pane footer shows the Read File
state. The text is read from the registry snapshot off the render thread. The
active provider reads `Problems: jdtls (jdtls over stdio)`. An unavailable Problems
window shows one line instead of the registry wording, with the reason and the
next step, for example `No diagnostics provider configured. Set
vintelli.lsp.profiles.jdtls.command=jdtls, then restart Vintelli
(docs/language-servers.md).`. Each higher ranked provider that was passed over reads `skipped idea: not
connected`: in Problems and Run it is its own line, in Terminal it is its own
line while no session runs, and in the project pane footer the skipped entries
share one line. The first state appears asynchronously after start. Problems
and Run re-read the registry on `r`, Terminal re-reads when a session starts,
and the project tree re-reads on its refresh action, so a provider that became
healthy is picked up by the next refresh. These windows and the project tree
never call the registry while they render. Long lines are clipped to the pane
width, so the project pane footer, which is 30 columns wide, cuts long reasons.

Keys: `Alt+1` shows the Project pane, `Alt+4` Run, `Alt+6` Problems, `Alt+7`
Structure, `Alt+F12` Terminal (`Alt+2` too), `Alt+8` ACP Agents and `Alt+9`
Maven switch tool windows (the bare digit works while no input has focus, see
`docs/keymap.md`), arrows and `PageUp`/`PageDown`/`Home`/`End` navigate
the live project tree (hjkl in the `vim` profile), and every selection is
mirrored into shell state and the status line. The tree shows file and folder
icons without connector lines, indented two columns per level in the IntelliJ New UI
style. Directory disclosure arrows use thin chevrons in Nerd Font mode, Unicode
arrows (`▸` and `▾`) when the terminal's locale and output charset support them,
and `+` and `-` otherwise. `vintelli.icons=auto`
(the default) selects Nerd glyphs when a Nerd Font is installed on the machine
and the session is a local terminal; set `vintelli.icons=nerd` to force them, or
`ascii` if the terminal draws boxes for them (see docs/file-icons.md).
A folder whose only child is a folder shares its row with that child, as IntelliJ's
Compact Middle Packages does: `src/main/java/dev/...` is one row. The chevron and
the name act on the last folder of the chain, and a chain stops at a folder with
several children or with a file. Selecting a compacted row selects its last folder,
so create, rename and delete act there. When the selected row is wider than the pane,
every row scrolls left by the same amount just enough to show the end of the selected
name, and returns to the start when a row that fits is selected. Rows that are not
selected may be cut at the pane edge. Opening a file from the editor, search, Problems
or a definition expands its folders and selects its row.
`Enter` toggles a directory or opens the selected file in the built-in editor.
With the project panel focused, `F5` refreshes the tree, `F7` creates a file,
`F9` creates a directory, `Shift+F6` renames the selected entry, and `Delete` asks
for confirmation before removal. Project mutations use the configured local
file provider; names must stay within the project and rename never replaces an
existing entry. In the editor, `Ctrl+S` saves, `Ctrl+Z`/`Ctrl+Y` undo and redo,
`Alt+Right`/`Alt+Left` switch editor tabs, and `Ctrl+F4` closes a tab (dirty tabs ask
for confirmation). `F10` opens the menus, the menu icon at the left of the
toolbar does the same with the mouse, and long unavailable reasons can be
scrolled with Page Up/Down.
The breadcrumb of the active file is on the status bar (see above), and
`Alt+Home` focuses it.

The editor shows clickable project path breadcrumbs and, when an LSP provider
returns document symbols, the containing type and method. `Alt+Home` focuses
the breadcrumbs; arrows move between segments, Enter selects one, and Escape
returns focus to the editor.
With no file open, the editor shows a start screen with the project name and the
main shortcuts (Go to File, Find in Files, Search Everywhere, the menu and the keymap
help). Its keys come from the active keymap, so a custom keymap file shows its own
chords, and an action without a chord has no row.
`Ctrl+O` opens Go to File, `Ctrl+G` goes to a line (`line` or `line:column`), `Alt+F` searches file contents, `Ctrl+B` requests a
definition, `Alt+F7` requests references, and `F2` moves to the next diagnostic, and `Alt+I` folds or unfolds the import block.
In the editor, a left click places the caret on the character under the pointer
and focuses the editor. Drag with the left button to select text; releasing ends
the drag. Shift with the arrows, Home, End, PageUp or PageDown selects from the
caret, and Ctrl+A selects the whole file; a caret key without Shift, or an edit,
clears the selection (see docs/keymap.md). Dragging past the top or bottom edge of the text scrolls the document one row per move, so a selection can extend beyond one screen. The mouse wheel
scrolls the document under the pointer by three rows without moving the caret,
and the next key press brings the caret back into view. Clicks on the line
numbers, the tab bar and the breadcrumbs do not move the caret. Ctrl+C, Ctrl+X
and Ctrl+V copy, cut and paste the selection. Vintelli captures
the mouse: to select with the terminal itself, hold its bypass modifier, usually
Shift (Option in iTerm2).
In Go to File, Find in Files and Search Everywhere, click a result to select it,
press Enter or double-click to activate it, and use the mouse wheel to scroll.
Clicking outside an overlay dismisses it without triggering controls underneath.
The Problems window refreshes diagnostics for files open in the editor with
`R`; `A`, `E`, and `W` show all, errors, or warnings, and clicking those labels
in the header does the same. Arrow keys move through the list, a click selects
the row under the pointer, and Enter or a double click opens the selected
problem at its diagnostic line. Each file is a group header with its problem
count: Left collapses it, Right expands it, and Enter or a click on the header
toggles it. Collapsing a group with a problem selected moves the selection to
its header, and the mouse wheel scrolls the list. The window shows the
selected diagnostics provider and distinguishes loading, unavailable, error,
and empty results. Each row shows a severity glyph, the line and column, the
source and the message; long messages wrap under the message column, and the
header counts errors, warnings and others.
In Structure, click a symbol row to select it, press Enter to navigate to its
file and line, and use the mouse wheel to scroll long outlines.
In the project panel, a left click selects the row under the pointer and focuses
the panel, and a double click on a row does what Enter does: it opens a file or
toggles a directory. A click on the disclosure glyph of a directory (a chevron,
an arrow, or `+` and `-`) toggles it. The list keeps the selected row in view
when the keyboard or the wheel moves the selection. Clicks below the last row
change nothing, and a click while a new name or a rename is being typed is
ignored, so the typed input and its target stay as they are.
The main toolbar follows the IntelliJ New UI order. On the left are the menu
button, the project widget and the branch widget. On the right, aligned to the
edge, are the run configuration, the Run slot, Debug, the more button, Search
and Settings, with one blank column between buttons and two before the group.
The Run slot shows Run while idle and Stop while a configuration runs; a click
on the configuration opens its chooser. Up/Down and Enter or a pointer click
select a configuration; Escape dismisses the chooser. The same chooser is
available as `Choose Run Configuration` in the palette. The Run window and
toolbar share the selection, which is remembered per project. Debug is drawn dimmed and does
nothing until debugging exists. The more button opens the action menu, the same
as `F10`. Search opens Find in Files, the same as `Alt+F`. Settings opens the
palette narrowed to the theme choice, which is also listed as `Open Settings`
in Search Everywhere (`Ctrl+P`); the icon face is set with `vintelli.icons`.
When the row is too narrow, the right group drops its leftmost buttons first,
so Settings is the last one to go, and the branch widget stays unless the left
group alone does not fit.

The project pane header reads `Project ▾` on the left. On the right, one blank
column apart and right aligned, it has New (`+`), Select Opened File (a
crosshair), Expand All, Collapse All, Options (`⋮`) and Hide (`─`), as in the
IntelliJ New UI. The arrow opens the view menu, which lists Project as the only
view. New opens a menu with New File (`F7`) and New Directory (`F9`), created in
the selected folder. Options opens a menu with Show Hidden Files. Select Opened
File reveals the file of the active editor tab and expands its folders; Expand
All and Collapse All act on the whole tree, and Collapse All keeps the selection
on the top folder that holds it. Hide works like the Project icon on the left
strip, and the command palette (`Ctrl+P`) runs every header action from
anywhere. Each button dispatches the same action as its keys, with the project
panel focused: `Alt+F1` Select Opened File, `F4` Expand All, `Alt+c` Collapse All,
`Alt+v` the view menu, `Alt+n` the New menu, `Alt+o` the options menu and `Alt+p`
Hide. A click on a blank header cell does nothing. When the pane is narrow, the
buttons drop from the left and Hide stays last. Nerd Font mode draws Nerd
glyphs; otherwise the header uses the Unicode symbols when the tree does, and
plain ASCII (`v`, `+`, `o`, `=`, `-`, `:`, `_`) when it does not.
Panes have no frames and no separator lines: each one is an island of the editor
tone on the frame tone, and starts with a header row that is underlined in the
frame tone. The left strip, the toolbar, the right stripe and the status bar sit
on the frame. The project pane and the right tool stripe run from the toolbar
down to the status bar. The bottom tool windows sit under the editor only, to
the right of the project pane, and their tab row starts at the editor's left
edge. With the project pane hidden, they start right after the left strip.
The gaps between islands are one cell each, drawn with a half block so they read
as half a cell: a column between the project pane and the editor, a column
before the right tool stripe, and a row above the bottom tool windows, which
spans the editor column only. Each gap cell is also the splitter, so the mouse
drags it. Where a row gap meets a column gap, the island corners are drawn as
quarter blocks. Without Unicode support, or in the ASCII icon face, the gaps are
plain frame cells and the corners stay square.
A two-column icon strip sits on each side of the shell. The left strip holds
the Project icon on top and the bottom tool windows at its bottom, and the
right strip holds the right tool windows. The bottom icons stay in the same
cells while the bottom tool windows are hidden, so the last visible window can
be shown again by clicking its icon. With the Nerd Font icon mode each
icon is that window's glyph; the ASCII mode shows the first letter of its
title. Click an icon to show a hidden window, activate a visible one, or hide
the active one; a hidden window frees its space and its icon stays. On a fresh layout the right tool windows start hidden with their icons on the stripe, while a saved layout restores exactly the windows it lists. Tool window
tabs show the shortcut digit before the title, underline the selected tab, and
answer clicks.
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

### Debug launch

Choose a supported Maven run configuration, then click Debug or invoke **Debug
Selected Configuration** in the palette. Attach to `127.0.0.1:5005`; Stop ends the
debuggee and build. See [debug launch](debug-launch.md) for supported goals and configuration.
