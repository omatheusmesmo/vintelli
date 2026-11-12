# Keymap

The named actions behind every key, the profiles, the IntelliJ reference column and the chords a terminal cannot deliver.

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
| `Activate<Name>ToolWindow` | `Alt+1` Project, `Alt+4` Run, `Alt+6` Problems, `Alt+7` Structure, `Alt+F12` Terminal (`Alt+2` too), `Alt+8` ACP Agents, `Alt+9` Maven. Each tool window also takes its bare digit when no input has focus | `Alt+1` Project, `Alt+4` Run, `Alt+6` Problems, `Alt+7` Structure, `Alt+F12` Terminal; none for ACP Agents and Maven |
| `SearchEverywhere` | `Ctrl+P` | `Double Shift` |
| `KeymapHelp` | `F1` | `F1` |
| `run.choose-configuration` | Palette: Choose Run Configuration; toolbar configuration name | - |
| `moveUp` / `moveDown` | arrow keys (`j/k` in the `vim` profile) | - |
| `quit` | `Ctrl+C` | - |
| `editor_copy`, `editor_cut`, `editor_paste` | `Ctrl+C` (copy, only with a selection), `Ctrl+X`, `Ctrl+V` | `Ctrl+C`, `Ctrl+X`, `Ctrl+V` |

The command palette lists every action with its active chord and, when it
differs, the real IntelliJ shortcut, filters by label or action id, and runs
the picked action with `Enter`; the help overlay lists the active keymap
(source plus shortcuts). Both stay reachable while an input has focus. Bare
`q` and `Q` no longer quit in any profile (text input stays safe); `j/k`
are reserved for the `vim` profile and the registry never assigns them as
tool window shortcuts.

### Copy, cut and paste

- `Ctrl+C` copies while the editor text has focus and text is selected. With
  no selection, in the project pane, and in every other window it keeps its
  `quit` meaning, and the ACP window keeps it to cancel a turn. Unsaved work
  is therefore not lost to an accidental quit while you are editing a
  selection. Terminal emulators and IDE terminals use the same convention.
- `Ctrl+X` cuts and `Ctrl+V` pastes the selection in the editor. Both are one
  undo step each and mark the document dirty. Copy changes neither the text
  nor the dirty state.
- Typing a character, `Enter`, `Backspace` or `Delete` over a selection
  replaces or removes it as one undo step.
- The menu (Edit) and the command palette run the same actions on the active
  editor document, whichever pane has the focus.
- Copy sends the text to the terminal with OSC 52, and to the system clipboard
  when a tool exists: `wl-copy` and `wl-paste` (Wayland), `xclip` or `xsel`
  (X11), `pbcopy` and `pbpaste` (macOS), and `clip.exe` (Windows and WSL, which
  can only write). Paste reads the system clipboard first and falls back to the
  editor's own clipboard, which always works. The status line states which one
  is in use: when no tool is found it says the text went to the editor
  clipboard and the terminal, and that no system clipboard was found.
- Ctrl+Shift+V (bracketed paste) is not covered. TamboUI delivers a pasted
  block as one paste event only to the focused element, and enabling it would
  drop pastes into the terminal and chat windows. Pasting with the terminal
  shortcut still arrives as typed keys and lands in the editor.
- Keyboard selection (Shift+arrows) is tracked in #272. Until then a selection
  comes from the mouse only.

Migration: `Ctrl+C` used to quit in every window, the editor included. Now it
copies when editor text is selected. There has been no release, so no alias is
kept. With no selection, `Ctrl+C` quits as before.

Project pane header actions (active while the project panel has the focus; the
palette runs them from anywhere):

| Action | Keys | Button |
|---|---|---|
| `project_select_opened_file` | `F3` | Select Opened File (crosshair) |
| `project_expand_all` | `F4` | Expand All |
| `project_collapse_all` | `Alt+c` | Collapse All |
| `project_new_menu` | `Alt+n` | New, a menu with New File (`F7`) and New Directory (`F9`) |
| `project_view_menu` | `Alt+v` | The `Project ▾` title, the view menu |
| `project_options_menu` | `Alt+o` | Options, a menu with Show Hidden Files |
| `project_hide` | `Alt+p` | Hide, like the Project icon on the left strip |

Chords a terminal cannot deliver, and their alternatives:

- **Double Shift** (Search Everywhere in IntelliJ): needs key release events
  and the Kitty keyboard protocol, which TamboUI 0.6.0 does not support. Use
  `Ctrl+P` to open the command palette.
- **`Ctrl+Shift+A`** (Find Action): `Ctrl+Shift+letter` sends the same bytes
  as `Ctrl+letter` in legacy mode. Use the command palette.
- **Alt+digit in tmux and ghostty**: `Alt+digit` is intercepted by default in
  tmux (`M-1`..`M-9`) and ghostty (tab switching). The profile binds
  `Alt+F12` to Terminal, the IntelliJ chord, and keeps `Alt+2` as a second
  binding for it. Users of the intercepting terminals reach a window through
  its bare digit when no input has focus, or through the palette from anywhere.
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

## Find row in the editor

`Find` (`Ctrl+F`) opens a find row above the editor status line. While it is
open, typed characters and Backspace edit the query, and the other keys are
local to the row rather than named actions, so they do not appear in the
table below:

| Key | Effect |
|---|---|
| `Enter`, `F3`, `Down` | Next match, wrapping to the first |
| `Shift+Enter`, `Shift+F3`, `Up` | Previous match, wrapping to the last |
| `Escape` | Closes the row and returns the focus to the text |

The search is literal and case-sensitive. The caret keys do not move the caret
in the text while the row is open, so close it with `Escape` first. `Find in
Files` (`Alt+F`) keeps opening the project search.

## IntelliJ comparison table

The file
`vintelli-tui/src/main/resources/dev/omatheusmesmo/vintelli/tui/keymap/intellij-keymap.csv`
lists every action the `intellij` profile binds, with its active chords, the
IntelliJ Windows/Linux default chords of the same action, and a status. It is
packaged with the native image through `quarkus.native.resources.includes` in
`vintelli-app/src/main/resources/application.properties`, and it is read by
`KeymapTable` (JDK only). Chords are separated by ` | `, and the chord text is
compared case-insensitively.

Statuses:

- `same`: the active chords equal IntelliJ's chords for the action. The bare
  digit of a tool window is the shortcut for while no input has focus, not an
  IntelliJ chord, so it is left out of the comparison.
- `different`: IntelliJ binds the action to other chords. The reason says why
  the chord stays (or is planned to move, #365 and #364).
- `not-deliverable`: IntelliJ's chord cannot be sent by a terminal (Double
  Shift, `Ctrl+Shift+letter`). The active chord stays and the reason names the
  alternative.
- `vintelli-only`: the action has no IntelliJ chord that this table can
  confirm, or Vintelli has no IntelliJ counterpart. Unconfirmed IntelliJ chords
  are never invented: they stay empty here.

A binding change must update the table. `IntellijKeymapTableTest` builds the
default profile with the real tool windows and fails when a bound action has
no row, when a row names an action the profile does not bind, when a row's
active chords differ from the bound ones, when a `same` row differs from
IntelliJ or a `different` row does not, when a chord is bound to two actions
outside the shared chords it documents (`Enter` for `select`, `confirm` and
`editor_open`, `Delete` for `deleteForward` and `project_delete`, and the mouse
wheel for `scroll` and its directions, all set by the TamboUI presets and the
focus), and when a non-`same` row has no reason. Adding a shared chord means
adding it to that test on purpose.


`run.debug` (**Debug Selected Configuration**) starts a supported selected run
configuration waiting for an external debugger; it is available in the palette.
