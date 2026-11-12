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
| `KeymapHelp` | `F1` | - |
| `run.choose-configuration` | Palette: Choose Run Configuration; toolbar configuration name | - |
| `editor_toggleBreakpoint` | `Ctrl+F8` (`Ctrl+B` in the `vim` profile) | `Ctrl+F8` |
| `moveUp` / `moveDown` | arrow keys (`j/k` in the `vim` profile) | - |
| `quit` | `Ctrl+C` | - |
| `editor_copy`, `editor_cut`, `editor_paste` | `Ctrl+C` (copy, only with a selection), `Ctrl+X`, `Ctrl+V` | `Ctrl+C`, `Ctrl+X`, `Ctrl+V` |
| `editor_selectAll` (Select All), Shift+arrows | `Ctrl+A` (`Ctrl+E` in the `vim` profile); `Shift` with the arrows, Home, End, PageUp and PageDown extend the selection | `Ctrl+A`, `Shift+arrows` |
| `editor_quickFix` | `Alt+Enter` | `Alt+Enter` |

The command palette lists every action with its active chord and, when the
IntelliJ chord differs and a terminal can send it, that chord too; it filters by
label or action id, and runs the picked action with `Enter`. The help overlay
lists the active keymap (source plus shortcuts) with the IntelliJ chord in its
own column. Both stay reachable while an input has focus. Bare
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

Migration: `Ctrl+C` used to quit in every window, the editor included. Now it
copies when editor text is selected. There has been no release, so no alias is
kept. With no selection, `Ctrl+C` quits as before.

### Selecting text

The mouse and the keyboard make the same selection: the same highlight, the
same copy, cut and paste, and typing over it replaces it as one undo step. The
anchor is where the selection started: the caret when the first Shift key was
pressed, or the press of a mouse drag.

- `Shift+Left`, `Shift+Right`, `Shift+Up` and `Shift+Down` move the caret one
  character or one row and extend the selection. `Shift+Home` and `Shift+End`
  go to the start and end of the line, and `Shift+PageUp` and `Shift+PageDown`
  move by one page of visible rows. Shift in the opposite direction shrinks
  the selection.
- A caret key without `Shift` (arrows, `Home`, `End`) moves the caret and ends
  the selection. Plain `PageUp` and `PageDown` do not move the caret in the
  editor yet, so they do not change the selection either.
- `Ctrl+A` selects the whole document in the `intellij` profile. Edit > Select
  All and the command palette run the same action.
- A selection crosses folded import blocks: the hidden rows are part of the
  selection and go to the clipboard with the rest.
- Terminals: `Shift` with the arrow keys is sent by essentially every terminal
  emulator. `Shift+Home`, `Shift+End`, `Shift+PageUp`, `Shift+PageDown` and
  `Ctrl+A` have not been checked in a live terminal yet. Ctrl+Shift chords (for
  example `Ctrl+Shift+Left`) cannot be delivered: the terminal sends the same
  bytes as the chord without Shift, so no Ctrl+Shift chord is bound for
  selection. A terminal window that captures the keyboard (terminal, chat)
  keeps `Ctrl+A` for itself, and a multiplexer that uses `Ctrl+A` as its prefix
  takes it before the editor sees it.

Project pane header actions (active while the project panel has the focus; the
palette runs them from anywhere):

| Action | Keys | Button |
|---|---|---|
| `project_select_opened_file` | `Alt+F1` | Select Opened File (crosshair) |
| `project_expand_all` | `F4` | Expand All |
| `project_collapse_all` | `Alt+c` | Collapse All |
| `project_new_menu` | `Alt+n` | New, a menu with New File (`F7`) and New Directory (`F9`) |
| `project_view_menu` | `Alt+v` | The `Project ▾` title, the view menu |
| `project_options_menu` | `Alt+o` | Options, a menu with Show Hidden Files |
| `project_hide` | `Alt+p` | Hide, like the Project icon on the left strip |

The pane-scoped keys `F4` (Expand All), `F5` (Refresh), `F7` (New File),
`F9` (New Directory) and `Delete` stay bound to their actions. Pane scope is
enough to keep them: they are active only while the project panel has the
focus, and IntelliJ also reuses these keys inside its own project tree, so
no other action needs them. `F2` is not one of them any more: in the project
pane it is Next Problem in the `intellij` profile (see the alignment below).

## IntelliJ alignment

The `intellij` profile binds the following actions to the IntelliJ default
chord, wherever a terminal can deliver that chord. There has been no release,
so the chord each one replaces is removed, with no alias. The `vim` profile
keeps the chords these actions had before, and gains the chords marked new.

| Action | `intellij` (active) | IntelliJ | Replaced chord, kept by `vim` |
|---|---|---|---|
| `editor_definition` (Go to Definition) | `Ctrl+B` | `Ctrl+B` | `F12` |
| `editor_toggleBreakpoint` (Toggle Line Breakpoint) | `Ctrl+F8` | `Ctrl+F8` | `Ctrl+B` |
| `editor_usages` (Find Usages) | `Alt+F7` | `Alt+F7` | `Alt+U` |
| `editor_nextProblem` (Next Problem) | `F2` | `F2` (`Shift+F2` previous) | `F8` |
| `editor_previousProblem` (Previous Problem) | `Shift+F2` | `Shift+F2` | none, `vim` binds `Shift+F8` (new) |
| `editor_close` (Close Editor Tab) | `Ctrl+F4` | `Ctrl+F4` | `Ctrl+W` |
| `editor_nextTab` (Next Editor Tab) | `Alt+Right` | `Alt+Right` | `Alt+]` |
| `editor_previousTab` (Previous Editor Tab) | `Alt+Left` | `Alt+Left` | `Alt+[` |
| `project_rename` (Rename Project Entry) | `Shift+F6` | `Shift+F6` | `F2` |
| `project_select_opened_file` (Select Opened File) | `Alt+F1` | `Alt+F1` | `F3` |
| `editor_duplicateLine` (Duplicate Line) | `Ctrl+D` | `Ctrl+D` | none, the `vim` profile binds nothing here |
| `navigation_goToLine` (Go to Line) | `Ctrl+G` | `Ctrl+G` | none, `vim` binds `Alt+G` (new) |
| `editor_selectAll` (Select All) | `Ctrl+A` | `Ctrl+A` | `Ctrl+E` |

Decisions behind the table:

- **`F2` and `Shift+F2`**: F2 is Next Highlighted Error in IntelliJ, so it
  moves to the editor action and Rename moves to `Shift+F6`. Shift+F2 is
  Previous Highlighted Error. A plain TamboUI key trigger answers Shift as
  well, so the Next Problem trigger refuses Shift (`UnshiftedKeyTrigger`) and
  `Shift+F2` reaches Previous Problem only. Both wrap around the file's
  problems like each other, and the palette lists both.
- **`vim` previous problem**: `vim` keeps `F8` for Next Problem, and binds
  `Shift+F8` to Previous Problem, which was free in the `vim` preset. The
  same Shift refusal keeps `F8` and `Shift+F8` apart there.
- **`F12` and `F8` are left unbound** in `intellij`. IntelliJ's `F12` is Jump
  to Last Tool Window (#366, not bound yet), and `F8` is Step Over, a debugger
  key that stays free for the debugger work.
- **`Ctrl+W`** is Extend Selection in IntelliJ, so it is not bound in
  `intellij`. The ACP Agents window keeps its own `Ctrl+W`, which closes the
  conversation, because that window handles its keys itself.
- **`Alt+F1`** is Select In in IntelliJ, which opens a list of targets. The
  project pane crosshair is the nearest action. The row is `same` in the
  comparison table, and its reason records the difference.
- **`Ctrl+Y`, `Ctrl+O` and `Ctrl+T` stay.** IntelliJ binds `Ctrl+Y` to Delete
  Line and `Ctrl+O` to Override Methods, and `Ctrl+T` to Update Project. Ours
  are Redo, Go to File and the shell theme switch. Redo keeps `Ctrl+Y` because
  its IntelliJ chord `Ctrl+Shift+Z` cannot be delivered, and Go to File keeps
  `Ctrl+O` for the same reason with `Ctrl+Shift+N`. The theme switch has no
  IntelliJ action.
- **`Alt+F` stays** for Find in Files, because IntelliJ's `Ctrl+Shift+F`
  cannot be delivered.
- **`Ctrl+D`** (Duplicate Line) copies the caret line below itself and moves
  the caret to the copy, in the same column, as one undo step. It works on
  the last line with or without a trailing newline (the document ends as it
  did) and with folded imports. Known gap: with a selection, IntelliJ
  duplicates the selected text, while Vintelli duplicates the caret line only
  and clears the selection. Duplicating the selected text is not done yet.
- **`Ctrl+A`** (Select All) is IntelliJ's chord and is free in both profiles.
  The `vim` profile uses `Ctrl+E` instead: Vim gives `Ctrl+A` to the increment
  command, which this editor does not have, so the chord stays free for it.

Terminal caveats, from the captures in `docs/spikes/spike-27-keyboard-and-colors.md`
and `docs/spikes/spike-49-per-terminal-capture.md`:

- `Ctrl+B` is the second prefix of the Omarchy tmux configuration, which
  swallows it, so Go to Definition needs the command palette there.
- `Alt+Left` and `Alt+Right` are bound by the Omarchy tmux configuration as
  pane switches, so the tab switches need the palette there too.
- `Ctrl+F4`, `Alt+F7`, `Alt+F1`, `Shift+F6` and `Shift+F2` have not been
  checked in a live terminal yet. The TamboUI key parser reads the F-key
  modifiers (spike 27), but each sequence is unverified on each terminal.
- `Shift+Home`, `Shift+End`, `Shift+PageUp`, `Shift+PageDown` and `Ctrl+A` are
  the keyboard selection chords that have not been checked in a live terminal.

Chords a terminal cannot deliver, and their alternatives:

- **Double Shift** (Search Everywhere in IntelliJ): needs key release events
  and the Kitty keyboard protocol, which TamboUI 0.6.0 does not support. Use
  `Ctrl+P` to open the command palette.
- **`Ctrl+Shift+A`** (Find Action): `Ctrl+Shift+letter` sends the same bytes
  as `Ctrl+letter` in legacy mode. Use the command palette.
- **`Ctrl+Shift+Z`** (Redo in IntelliJ): sends the same bytes as `Ctrl+Z`.
  Redo is `Ctrl+Y` here.
- **`Ctrl+Shift+N`** (Go to File in IntelliJ): sends the same bytes as
  `Ctrl+N`. Go to File is `Ctrl+O` here.
- **`Ctrl+Shift+F`** (Find in Files in IntelliJ): sends the same bytes as
  `Ctrl+F`. Find in Files is `Alt+F` here.
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

## Go to Line

`Go to Line` (`Ctrl+G` in the `intellij` profile, `Alt+G` in `vim`, and the
Navigate menu or the command palette in both) opens a one-line input in a
small panel over the editor. It takes a line, or a line and a column, counted
from 1 as in IntelliJ:

| Input | Caret |
|---|---|
| `42` | Line 42, column 1 |
| `42:7` | Line 42, column 7 |

A number past the end goes to the last line, and a column past the end goes
to the end of that line. `0`, a negative, empty or non numeric input keeps the
panel open with a message and leaves the caret where it was. `Enter` confirms
and returns the focus to the text, `Escape` cancels and does the same. The
status line shows the new position, and a target inside a folded import block
expands the block to reach it.

## IntelliJ comparison table

The file `vintelli-tui/src/main/resources/dev/omatheusmesmo/vintelli/tui/keymap/intellij-keymap.csv`
is the one source of the IntelliJ reference. It has a row for every action of
the keymap catalog: the chords the default `intellij` profile binds to it (`-`
when the profile leaves it unbound), the IntelliJ Windows/Linux default chords of
the same action, a status and a reason. The keymap reads it once. The command
palette shows the IntelliJ chord only while it differs from the chords bound now
and a terminal can send it, and the keymap help overlay shows it in its own
column. The file is packaged with the native image through
`quarkus.native.resources.includes` in
`vintelli-app/src/main/resources/application.properties`, and `KeymapTable`
reads it with the JDK only, with no reflection. The table below states the same
rows: `IntellijKeymapDocsTableTest` fails when the two differ.

In the CSV chords are separated by ` | `, in this table by ` \| `. Chord text is
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

| Action ID | Keys (active) | IntelliJ | Status | Reason |
|---|---|---|---|---|
| cancel | Escape | - | vintelli-only | Closes overlays and inputs here; IntelliJ uses Escape to return focus from a tool window to the editor (#366), an action we do not bind yet |
| click | Mouse.LEFT.PRESS | - | vintelli-only | Mouse click of the TamboUI preset; IntelliJ has no keyboard chord for it |
| rightClick | Mouse.RIGHT.PRESS | - | vintelli-only | Mouse click of the TamboUI preset; IntelliJ has no keyboard chord for it |
| scroll | Mouse.SCROLL_UP \| Mouse.SCROLL_DOWN \| Mouse.SCROLL_LEFT \| Mouse.SCROLL_RIGHT | - | vintelli-only | Mouse wheel of the TamboUI preset; IntelliJ has no keyboard chord for it |
| scrollUp | Mouse.SCROLL_UP | - | vintelli-only | Mouse wheel of the TamboUI preset; IntelliJ has no keyboard chord for it |
| scrollDown | Mouse.SCROLL_DOWN | - | vintelli-only | Mouse wheel of the TamboUI preset; IntelliJ has no keyboard chord for it |
| scrollLeft | Mouse.SCROLL_LEFT | - | vintelli-only | Mouse wheel of the TamboUI preset; IntelliJ has no keyboard chord for it |
| scrollRight | Mouse.SCROLL_RIGHT | - | vintelli-only | Mouse wheel of the TamboUI preset; IntelliJ has no keyboard chord for it |
| confirm | Enter | - | vintelli-only | Confirms a choice in TamboUI widgets; shares Enter with select and editor_open, the focus decides |
| select | Enter \| Space | - | vintelli-only | Selects in TamboUI widgets; shares Enter with confirm and editor_open, the focus decides; Space names no IntelliJ action here |
| deleteBackward | Backspace | - | vintelli-only | Text editing in capturing inputs; the reference names no chord for it |
| deleteForward | Delete | - | vintelli-only | Text editing in capturing inputs; shares Delete with project_delete, the focus decides |
| focusNext | Tab | - | vintelli-only | Moves focus between panes here; IntelliJ binds Tab to Indent Selection in the editor |
| focusPrevious | Shift+Tab | - | vintelli-only | Moves focus back here; IntelliJ binds Shift+Tab to Unindent in the editor |
| home | Home \| Ctrl+Home | Home \| Ctrl+Home | same | Move Caret to Line Start (Home) and to Text Start (Ctrl+Home) |
| end | End \| Ctrl+End | End \| Ctrl+End | same | Move Caret to Line End (End) and to Text End (Ctrl+End) |
| pageUp | PageUp | PageUp | same | Page Up |
| pageDown | PageDown | PageDown | same | Page Down |
| moveUp | Up | Up | same | Move Caret Up on the arrow keys; the reference page does not list arrows, the chord is the editor one |
| moveDown | Down | Down | same | Move Caret Down on the arrow keys; the reference page does not list arrows, the chord is the editor one |
| moveLeft | Left | Left | same | Move Caret Left on the arrow keys; the reference page does not list arrows, the chord is the editor one |
| moveRight | Right | Right | same | Move Caret Right on the arrow keys; the reference page does not list arrows, the chord is the editor one |
| quit | Ctrl+C | - | vintelli-only | Terminal interrupt and the only quit chord; IntelliJ has no quit chord and Ctrl+C is Copy there (#365) |
| toggleDebugOverlay | Ctrl+Shift+F12 | - | vintelli-only | TamboUI debug overlay; IntelliJ binds Ctrl+Shift+F12 to Hide All Tool Windows, which a terminal cannot deliver (#363) |
| SearchEverywhere | Ctrl+P | Double Shift | not-deliverable | Double Shift needs key release events, which a terminal does not send; Ctrl+P is the command palette stand-in |
| KeymapHelp | F1 | - | vintelli-only | F1 is not on the IntelliJ default keymap page (it lists Ctrl+F1 for Error Description and Ctrl+Q for Quick Documentation); here it opens the keymap help |
| shell_menu | F10 | - | vintelli-only | Opens the shell menu; no IntelliJ chord confirmed against the reference page |
| navigation_focusBreadcrumbs | Alt+Home | - | vintelli-only | Focus the navigation bar; no IntelliJ chord confirmed against the reference page |
| project_new_file | F7 | - | vintelli-only | New project file; IntelliJ binds F7 to Step Into in the debugger, and no New File chord is confirmed here |
| project_new_directory | F9 | - | vintelli-only | New project directory; IntelliJ binds F9 to Resume Program, and no new-directory chord is confirmed here |
| project_rename | Shift+F6 | Shift+F6 | same | Rename in the project pane; F2 is Next Highlighted Error in IntelliJ, so F2 moved to the editor action |
| project_delete | Delete | - | vintelli-only | Deletes the project entry; no IntelliJ chord confirmed; shares Delete with deleteForward, the pane scope decides |
| project_refresh | F5 | - | vintelli-only | Refreshes the project tree; IntelliJ's F5 is Synchronize in the project view, not confirmed against the reference page |
| project_select_opened_file | Alt+F1 | Alt+F1 | same | Reveals the open file in the project pane (crosshair); the nearest IntelliJ chord, since IntelliJ's Alt+F1 opens the Select In list instead |
| project_expand_all | F4 | Ctrl+Shift+NumPad + | not-deliverable | Ctrl+Shift+NumPad + cannot be told apart from a plain key in a terminal; F4 expands the project tree here |
| project_collapse_all | Alt+C | Ctrl+Shift+NumPad - | not-deliverable | Ctrl+Shift+NumPad - cannot be told apart from a plain key in a terminal; Alt+C collapses the project tree here |
| project_view_menu | Alt+V | - | vintelli-only | Pane header view menu; no IntelliJ chord confirmed |
| project_new_menu | Alt+N | - | vintelli-only | Pane header New menu; no IntelliJ chord confirmed |
| project_options_menu | Alt+O | - | vintelli-only | Pane header options menu; no IntelliJ chord confirmed |
| project_hide | Alt+P | Alt+1 | different | IntelliJ toggles the Project window with Alt+1, which is ActivateProjectToolWindow here (#364); Alt+P hides the pane |
| editor_open | Enter | - | vintelli-only | Opens the selected file in the project pane; no IntelliJ chord confirmed; shares Enter with confirm and select, the focus decides |
| editor_save | Ctrl+S | Ctrl+S | same | IntelliJ Save All is Ctrl+S; our action saves the open file |
| editor_undo | Ctrl+Z | Ctrl+Z | same | Undo |
| editor_redo | Ctrl+Y | Ctrl+Shift+Z | not-deliverable | Ctrl+Shift+Z sends the same bytes as Ctrl+Z in a terminal; Ctrl+Y is Delete Line in IntelliJ and stays here (docs/keymap.md) |
| editor_find | Ctrl+F | Ctrl+F | same | Find |
| editor_cut | Ctrl+X | Ctrl+X | same | Cut the selection to the clipboard; with no selection it reports that nothing is selected |
| editor_copy | Ctrl+C | Ctrl+C | same | Copy the selection; in the editor Ctrl+C quits only when no text is selected (see docs/keymap.md) |
| editor_paste | Ctrl+V | Ctrl+V | same | Paste from the clipboard, replacing the selection |
| editor_duplicateLine | Ctrl+D | Ctrl+D | same | Duplicate Line; with a selection it duplicates the caret line only and clears the selection, IntelliJ duplicates the selected text (docs/keymap.md) |
| editor_selectAll | Ctrl+A | Ctrl+A | same | Select All; the vim profile uses Ctrl+E because Ctrl+A is the Vim increment (docs/keymap.md) |
| editor_close | Ctrl+F4 | Ctrl+F4 | same | Closes the editor tab; Ctrl+W is Extend Selection in IntelliJ, so it is not bound here |
| editor_nextTab | Alt+Right | Alt+Right | same | Select Next Tab |
| editor_previousTab | Alt+Left | Alt+Left | same | Select Previous Tab |
| editor_definition | Ctrl+B | Ctrl+B | same | Go to Declaration; F12 is Jump to Last Tool Window in IntelliJ, so F12 is left unbound (#366) |
| editor_usages | Alt+F7 | Alt+F7 | same | Find Usages |
| editor_toggleImportFold | Alt+I | - | vintelli-only | Folds or unfolds imports; no IntelliJ chord confirmed |
| editor_toggleBreakpoint | Ctrl+F8 | Ctrl+F8 | same | Toggle Line Breakpoint; Ctrl+B is Go to Declaration |
| editor_nextProblem | F2 | F2 | same | Next Highlighted Error; the bare F2 trigger refuses Shift, and F8 is left unbound (docs/keymap.md) |
| editor_previousProblem | Shift+F2 | Shift+F2 | same | Previous Highlighted Error on its own Shift+F2 trigger (docs/keymap.md) |
| editor_quickFix | Alt+Enter | Alt+Enter | same | Quick fixes from the language server for the selection or caret, offered in a popup (docs/keymap.md) |
| navigation_goToFile | Ctrl+O | Ctrl+Shift+N | not-deliverable | Ctrl+Shift+N sends the same bytes as Ctrl+N in a terminal; Ctrl+O is Override Methods in IntelliJ and stays here (docs/keymap.md) |
| navigation_findInFiles | Alt+F | Ctrl+Shift+F | not-deliverable | Ctrl+Shift+F sends the same bytes as Ctrl+F in a terminal; Alt+F is kept here |
| navigation_goToLine | Ctrl+G | Ctrl+G | same | Go to Line with the IntelliJ chord; the vim profile binds Alt+G instead (docs/keymap.md) |
| layout_project_wider | Alt+= | - | vintelli-only | Widens the project panel; no IntelliJ chord confirmed |
| layout_project_narrower | Alt+- | - | vintelli-only | Narrows the project panel; no IntelliJ chord confirmed |
| layout_bottom_taller | Alt+. | - | vintelli-only | Grows the bottom tool window; no IntelliJ chord confirmed |
| layout_bottom_shorter | Alt+, | - | vintelli-only | Shrinks the bottom tool window; no IntelliJ chord confirmed |
| shell.theme.switch | Ctrl+T | - | vintelli-only | Switches the shell theme; Ctrl+T is Update Project in IntelliJ and stays here as the theme switch (docs/keymap.md) |
| ActivateProjectToolWindow | Alt+1 | Alt+1 | same |  |
| ActivateRunToolWindow | Alt+4 \| 4 | Alt+4 | same |  |
| ActivateTerminalToolWindow | Alt+F12 \| Alt+2 \| 2 | Alt+F12 | different | IntelliJ uses Alt+F12 for Terminal (#364); Alt+2 stays as a second binding because the owner kept it, and the bare 2 is the shortcut while no input has focus |
| ActivateProblemsToolWindow | Alt+6 \| 6 | Alt+6 | same |  |
| ActivateStructureToolWindow | Alt+7 \| 7 | Alt+7 | same |  |
| ActivateMavenToolWindow | Alt+9 \| 9 | - | vintelli-only | IntelliJ gives Maven no number; 9 is a number IntelliJ gives Version Control, which we do not have (#364) |
| ActivateAcpAgentsToolWindow | Alt+8 \| 8 | - | vintelli-only | IntelliJ gives no number to ACP Agents; 8 is a number IntelliJ gives Services, which we do not have (#364) |
| shell.settings | - | Ctrl+Alt+S | different | Open Settings is Ctrl+Alt+S in IntelliJ; it opens from the toolbar and the palette, and no chord is bound here yet |
| idea-mcp.connect | - | - | vintelli-only | Connects to a running IntelliJ IDEA; no IntelliJ chord confirmed on the reference page |
| run.execute | - | Shift+F10 | different | Run is Shift+F10 in IntelliJ; unbound here, the toolbar and the palette start the selected configuration |
| run.debug | - | Shift+F9 | different | Debug is Shift+F9 in IntelliJ; unbound here, the toolbar and the palette start the debug session |
| run.stop | - | Ctrl+F2 | different | Stop is Ctrl+F2 in IntelliJ; unbound here, the palette stops the current run |
| run.refresh | - | - | vintelli-only | Refreshes the run configurations; no IntelliJ chord confirmed on the reference page |
| run.choose-configuration | - | - | vintelli-only | Chooses the run configuration; no IntelliJ chord confirmed on the reference page |
| debug.resume | - | F9 | different | Resume Program is F9 in IntelliJ; F9 is New Directory in the project pane, so the debugger keeps its own keys |
| debug.pause | - | - | vintelli-only | Pause Program is not on the IntelliJ reference page; the debugger keeps its own keys |
| debug.step-over | - | F8 | different | Step Over is F8 in IntelliJ; F8 stays free for the debugger work (docs/keymap.md) |
| debug.step-into | - | F7 | different | Step Into is F7 in IntelliJ; F7 is New Project File in the project pane |
| debug.step-out | - | Shift+F8 | different | Step Out is Shift+F8 in IntelliJ; unbound here, the debugger work decides |
| terminal.execute | - | - | vintelli-only | Runs the typed command in the terminal window; IntelliJ has no chord for it |
| problems.navigate | - | - | vintelli-only | Opens the selected problem; no IntelliJ chord confirmed on the reference page for the Problems window |
| problems.refresh | - | - | vintelli-only | Refreshes the problems; no IntelliJ chord confirmed on the reference page for the Problems window |
| problems.select-next | - | - | vintelli-only | Selects the next row of the window; no IntelliJ chord confirmed on the reference page for the Problems window |
| problems.select-previous | - | - | vintelli-only | Selects the previous row of the window; no IntelliJ chord confirmed on the reference page for the Problems window |
| problems.filter-all | - | - | vintelli-only | Shows all problems; no IntelliJ chord confirmed on the reference page for the Problems window |
| problems.filter-errors | - | - | vintelli-only | Shows only the problem errors; no IntelliJ chord confirmed on the reference page for the Problems window |
| problems.filter-warnings | - | - | vintelli-only | Shows only the problem warnings; no IntelliJ chord confirmed on the reference page for the Problems window |
| problems.expand | - | - | vintelli-only | Expands the selected problem group; no IntelliJ chord confirmed on the reference page for the Problems window |
| problems.collapse | - | - | vintelli-only | Collapses the selected problem group; no IntelliJ chord confirmed on the reference page for the Problems window |
| problems.toggle-group | - | - | vintelli-only | Toggles the selected problem group; no IntelliJ chord confirmed on the reference page for the Problems window |
| project_show_hidden | - | - | vintelli-only | Shows hidden files from the project options menu; no IntelliJ chord confirmed on the reference page |

### Palette and help layout

Both overlays keep the action name and the active chord on every row. A row that
does not fit its panel drops the IntelliJ text first. The help panel is at most
78 columns wide, so at 80 columns every row fits unless the name, the active
chord and the reference together need more room; then the reference is cut, and
the name is shortened only when the name and the active chord alone do not fit.
The active chord is never cut. The IntelliJ reference of a chord that a terminal
cannot send (Double Shift) is shown in the help with the note `not sent by
terminals`, and it is never a palette hint. The full reason of every row is in
the table above.

`run.debug` (**Debug Selected Configuration**) starts a supported selected run
configuration waiting for an external debugger; it is available in the palette.
