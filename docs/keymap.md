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
characters belong to the capturing input. The keyboard protocol is picked
with the `vintelli.keyboard.protocol` system property (see
[Keyboard protocol](#keyboard-protocol)). Under the default `auto` mode a user
file may bind Super, Hyper and Meta chords, keypad keys and F13 to F35; under
`legacy` a trigger the legacy protocol never receives fails fast.

The keymap is built only for the `--tui` session: headless and MCP-only
processes never read the file, and a malformed file fails the TUI startup
with a one-line message on stderr and exit code 2.

Key actions in the `intellij` profile:

| Action | Keys (active) | IntelliJ reference |
|---|---|---|
| `Activate<Name>ToolWindow` | `Alt+1` Project, `Alt+3` Debug, `Alt+4` Run, `Alt+6` Problems, `Alt+7` Structure, `Alt+F12` Terminal (`Alt+2` too), `Alt+8` ACP Agents, `Alt+9` Maven. Each tool window also takes its bare digit when no input has focus | `Alt+1` Project, `Alt+4` Run, `Alt+6` Problems, `Alt+7` Structure, `Alt+F12` Terminal; none for Debug, ACP Agents and Maven |
| `SearchEverywhere` | `Ctrl+P` | `Double Shift` |
| `KeymapHelp` | `F1` | - |
| `run.choose-configuration` | Palette: Choose Run Configuration; toolbar configuration name | - |
| `editor_toggleBreakpoint` | `Ctrl+F8` (`Ctrl+B` in the `vim` profile) | `Ctrl+F8` |
| `moveUp` / `moveDown` | arrow keys and the keypad arrows (`j/k` in the `vim` profile) | - |
| `quit` | `Ctrl+C` | - |
| `editor_copy`, `editor_cut`, `editor_paste` | `Ctrl+C` (copy, only with a selection), `Ctrl+X`, `Ctrl+V` | `Ctrl+C`, `Ctrl+X`, `Ctrl+V` |
| `editor_selectAll` (Select All), Shift+arrows | `Ctrl+A` (`Ctrl+E` in the `vim` profile); `Shift` with the arrows, Home, End, PageUp and PageDown extend the selection | `Ctrl+A`, `Shift+arrows` |
| `editor_quickFix` | `Alt+Enter` | `Alt+Enter` |
| `editor_completion` | `Ctrl+Space` (`Ctrl+@` too) | `Ctrl+Space` |
| `editor_quickDocumentation` | `Ctrl+Q` | `Ctrl+Q` |
| `editor_generate` | `Alt+Insert` (the command palette, `Ctrl+P`, is the fallback) | `Alt+Insert` |
| `editor_quickDefinition` | `Ctrl+Shift+I` (Kitty keyboard protocol), `Alt+Space` | `Ctrl+Shift+I` |
| `focusEditor` | `Ctrl+F11` | `Ctrl+Shift+F12` |

The command palette lists every action with its active chord and, when the
IntelliJ chord differs and a terminal can send it, that chord too; it filters by
label or action id, and runs the picked action with `Enter`. The help overlay
lists the active keymap (source plus shortcuts) with the IntelliJ chord in its
own column. Both stay reachable while an input has focus. Bare
`q` and `Q` no longer quit in any profile (text input stays safe); `j/k`
are reserved for the `vim` profile and the registry never assigns them as
tool window shortcuts.

### Code completion

`Ctrl+Space` (and `Ctrl+@`, the same key in terminals that send it as `@`)
asks the language server for the completions at the caret and opens a popup.
A dot typed behind an identifier in a `.java` file opens it on its own. The
server receives the buffer first, unsaved edits included. While the popup is
open, typing narrows the list by the identifier before the caret: names that
start with the typed text come first, then camel-hump matches such as `gNa`
for `getName`. Backspace widens the list again, `Enter` or `Tab` inserts the
selected item, `Escape` closes the popup, and the arrow and page keys move the
selection. A click on a row inserts it. The popup closes when nothing matches
or when the caret leaves the completed text. A server that cannot answer says
why in the popup, and the editor keeps working. One undo step reverts an insert.
An item whose type is not imported yet also gets its import, as one step with the
name. When the server sends that import only after it is asked, the insert waits
up to a second for it; without an answer the name is inserted alone and the status
line says why.

### Quick documentation

`Ctrl+Q` asks the language server for the documentation of the symbol at the
caret and opens a popup beside it, with the signature on the first line and the
documentation below. Markdown and the HTML of Javadoc are shown as text: code in
a code colour, headings and bold text in bold, links as their text, and no tags
or markers. The popup takes at most about half of the editor height. While it is
open, `Up`, `Down`, `PageUp` and `PageDown` scroll it instead of moving the
caret, and the mouse wheel scrolls it too. `Escape` closes it, and so does a
click or any other key; that key then goes to the editor as usual. The popup
shows `Loading...` while the server answers, `No documentation found` for an
empty answer, and the reason when the server cannot answer. An answer that
arrives after the caret or the text moved is dropped, so the popup never shows
the documentation of a position the caret has left.

### Quick definition

`Ctrl+Shift+I` (or `Alt+Space`) resolves the declaration of the symbol at the
caret through the navigation provider and opens a popup titled `Quick Definition`
below the caret line, above it when there is no room. The popup shows the source
of the declaration, coloured the way the editor colours it, about 15 rows tall,
under a header with the file and the line, such as `Util.java:42`. The range is the
innermost declaration of the file outline that contains the target (capped at 200
lines), or 20 lines from the target line when the outline has none. The text comes
from the open tab when the file is open, unsaved edits included, and from the read
file capability otherwise. The editor keeps its caret, scroll and tabs while the
popup is open.

While it is open, `Up`, `Down`, `PageUp` and `PageDown` scroll it, and so does the
mouse wheel. `Enter` or the Go to Declaration chord (`Ctrl+B`) opens the shown
declaration for real, with the file revealed in the project tree, and closes the
popup. `Escape` closes it and the focus stays in the editor at the same caret; a
click or any other key closes it too, and that key then goes to the editor as
usual. When the server answers several declarations the header adds the position
among them, such as `1/3`, and the Next Editor Tab and Previous Editor Tab chords
(`Alt+Right` and `Alt+Left` in the `intellij` profile) page between them.

A target the editor cannot read says why in the popup and on the status line: a
`jdt://` location of a library or the JDK, which the editor has no way to open
yet, a file outside the project root, a server that is not running, or no
definition at the caret. An answer that arrives after the caret or the text moved
is dropped.

`Ctrl+Shift+I` needs the Kitty keyboard protocol: without it the terminal sends
the chord as `Ctrl+I`, which is `Tab`. `Alt+Space` is the fallback that works in
every terminal that sends `Alt` as an `ESC` prefix, and it is bound in both
profiles; the Navigate menu and the command palette reach the action as well.
It is IntelliJ's macOS chord for Quick Definition, and no other action uses it.
The `vim` profile binds `Alt+Space` only.

### Generate

`Alt+Insert` asks the language server for the `source.generate` actions of the
file at the caret (or the selection) and opens a popup titled `Generate` beside
the caret. The menu lists what jdtls offers for the class, such as
`Generate Getters and Setters`, `Generate Constructors`,
`Generate hashCode() and equals()` and `Generate toString()`; other source
actions, such as organize imports, are left out. `Up`, `Down`, `Enter` and
`Escape` work as in the quick fix popup, and `Enter` applies the chosen action as
one undo step. When the class has nothing to generate the popup and the status
line say `Nothing to generate here`, and a server that cannot answer says why in
the popup.

Some jdtls generate actions first ask the client to pick the fields (their
commands are `java.action.generateAccessorsPrompt`,
`java.action.generateConstructorsPrompt`, `java.action.hashCodeEqualsPrompt` and
`java.action.generateToStringPrompt`). Those rows are listed dimmed with
`(needs field selection)` and do not run: the field checklist is not built yet.
Actions that come back with a plain edit, or with the client side command
`java.apply.workspaceEdit`, apply as usual.

The chord is only bound in the `intellij` profile. A terminal that does not
deliver `Alt+Insert` still reaches the action through the command palette
(`Ctrl+P`, then `Generate`), which is the fallback to use there.

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

### Ctrl+click

A left click with `Ctrl` held on a symbol in the editor text goes to its
definition, as in IntelliJ: the caret moves to the clicked character and
Go to Definition runs from there. It works in both profiles, does not start a
selection, and a click on the gutter keeps toggling breakpoints. A plain click
only moves the caret. The terminal must report mouse presses with their
modifiers; a terminal that keeps `Ctrl+click` for itself (to open links, for
example) needs `Ctrl+B` or the command palette instead.

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
  example `Ctrl+Shift+Left`) reach the shell only in a Kitty-capable terminal
  (see [Keyboard protocol](#keyboard-protocol)), and no Ctrl+Shift chord is
  bound for selection yet. `Ctrl+Left` moves by word in the editor, and it also
  answers while Shift is held, because a plain Ctrl+Left trigger does not
  refuse Shift: `Ctrl+Shift+Left` moves by word and does not select. Refusing
  Shift there is a follow-up. A terminal window that captures the keyboard (terminal, chat)
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
| `navigation_goToClass` (Go to Class) | `Ctrl+N` | `Ctrl+N` | none, `vim` binds `Ctrl+N` too (new) |
| `navigation_goToSymbol` (Go to Symbol) | `Ctrl+Alt+Shift+N` | `Ctrl+Alt+Shift+N` | none, the palette and the Navigate menu reach it |
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
  are Redo, Go to File and the shell theme switch. Redo and Go to File keep
  `Ctrl+Y` and `Ctrl+O` as their fallback chords: in the default `auto` mode
  they answer to their IntelliJ chords `Ctrl+Shift+Z` and `Ctrl+Shift+N` in a
  Kitty-capable terminal, and the fallbacks keep both reachable everywhere
  else. The theme switch has no IntelliJ action.
- **`Alt+F` stays** for Find in Files as the fallback: IntelliJ's
  `Ctrl+Shift+F` is bound too, and reaches the shell in a Kitty-capable
  terminal only.
- **`Ctrl+D`** (Duplicate Line) copies the caret line below itself and moves
  the caret to the copy, in the same column, as one undo step. It works on
  the last line with or without a trailing newline (the document ends as it
  did) and with folded imports. Known gap: with a selection, IntelliJ
  duplicates the selected text, while Vintelli duplicates the caret line only
  and clears the selection. Duplicating the selected text is not done yet.
- **`Ctrl+A`** (Select All) is IntelliJ's chord and is free in both profiles.
  The `vim` profile uses `Ctrl+E` instead: Vim gives `Ctrl+A` to the increment
  command, which this editor does not have, so the chord stays free for it.
- **`Ctrl+F11`** (Focus Editor) takes the place of IntelliJ's Hide All Tool
  Windows, `Ctrl+Shift+F12`. That chord is `toggleDebugOverlay` here (the
  TamboUI debug overlay, #363), and TamboUI's trigger ignores Shift unless it
  asks for it, so a `Ctrl+F12` binding would also fire on `Ctrl+Shift+F12`.
  `Alt` with a digit is intercepted by tmux and ghostty (see the spike 27
  chord matrix), and `Ctrl+Alt+letter` does not reach the shell. An F-key with
  a Ctrl modifier is sent by the legacy protocol (`ESC[23;5~`), and no profile
  binds it. The comparison table keeps the IntelliJ chord as the reference.

Terminal caveats, from the captures in `docs/spikes/spike-27-keyboard-and-colors.md`
and `docs/spikes/spike-49-per-terminal-capture.md`:

- `Ctrl+B` is the second prefix of the Omarchy tmux configuration, which
  swallows it, so Go to Definition needs the command palette or a Ctrl+click
  there.
- `Alt+Left` and `Alt+Right` are bound by the Omarchy tmux configuration as
  pane switches, so the tab switches need the palette there too.
- `Ctrl+F4`, `Alt+F7`, `Alt+F1`, `Shift+F6` and `Shift+F2` have not been
  checked in a live terminal yet. The TamboUI key parser reads the F-key
  modifiers (spike 27), but each sequence is unverified on each terminal.
- `Shift+Home`, `Shift+End`, `Shift+PageUp`, `Shift+PageDown` and `Ctrl+A` are
  the keyboard selection chords that have not been checked in a live terminal.

Chords a terminal cannot deliver, and their alternatives:

- **Double Shift** (Search Everywhere in IntelliJ): needs key release events,
  which only the full Kitty keyboard mode sends. Vintelli runs the `auto`
  mode, which does not send them. Use `Ctrl+P` to open the command palette.
- **Lone modifiers** (for example a Shift tap on its own): also need the
  release events of the full Kitty mode. No action is bound to them.
- **`Ctrl+Shift+A`** (Find Action): no action is bound to it, so a Kitty-capable
  terminal sends it without any effect. Use the command palette.
- **Alt+digit in tmux and ghostty**: `Alt+digit` is intercepted by default in
  tmux (`M-1`..`M-9`) and ghostty (tab switching). The profile binds
  `Alt+F12` to Terminal, the IntelliJ chord, and keeps `Alt+2` as a second
  binding for it. Users of the intercepting terminals reach a window through
  its bare digit when no input has focus, or through the palette from anywhere.
- **Cmd (macOS)**: the terminal application takes it, so it never reaches the
  shell. Super, Hyper and Meta reach the shell only where the terminal reports
  them under the Kitty protocol, and no shell action is bound to them.
- **`Ctrl+I`, `Ctrl+M`, `Ctrl+J`, `Ctrl+H`, `Ctrl+[`**: under `legacy` the
  same bytes as Tab, Enter, Backspace and Escape. Under `auto` the Kitty
  protocol tells `Ctrl+I` and `Ctrl+M` apart from Tab and Enter, and no action
  is bound to them, so they are never given their own action.

While a tool window with text input claims the keyboard
(`ToolWindow.capturesInput()`), the shell keeps escape and the reserved
keymap chords for itself: `Ctrl+C` goes to the captured input, but
`Alt+<digit>`, `Ctrl+P` and `F1` still drive the shell, so switching never
relies on bare digit keys once an input has focus. Opening an overlay
releases the capture; after closing it the shell keeps the focus until the
tab is reselected.

## Keyboard protocol

The shell picks its keyboard mode with the `vintelli.keyboard.protocol` system
property, `auto` or `legacy`, case-insensitive. Any other value fails the TUI
startup with a one-line message naming the valid values, the same way an
unknown `vintelli.keymap` profile does. Pass it as a JVM system property
(`-Dvintelli.keyboard.protocol=legacy`) on the command that starts the TUI.
The keymap and the terminal runner are built for the same mode, so the bindings
and the bytes the terminal sends always agree.

- **`auto`** (default): pushes the Kitty keyboard protocol flags TamboUI 0.7
  uses by default. A terminal without the protocol ignores the push, so `auto`
  behaves as `legacy` there. A Kitty-capable terminal delivers the chords below
  as their own keys:
  - `Ctrl+Shift+N` (Go to File), `Ctrl+Shift+F` (Find in Files) and
    `Ctrl+Shift+Z` (Redo). The plain `Ctrl+F` and `Ctrl+Z` no longer run Find
    and Undo while Shift is held.
  - `Ctrl+Alt+Shift+N` (Go to Symbol). The plain `Ctrl+N` runs Go to Class and
    does not answer while Shift is held, so `Ctrl+Shift+N` still runs Go to File.
  - `Ctrl+Shift+I` (Quick Definition), which the legacy protocol sends as `Ctrl+I`,
    the `Tab` byte.
  - `Ctrl+Shift+NumPad +` (Expand All) and `Ctrl+Shift+NumPad -` (Collapse All)
    in the project pane and from the palette.
  - The keypad keys as their own keys: the keypad arrows, Home, End, Page Up,
    Page Down, Insert, Delete and Enter act as their main keys. The keypad
    digits, the decimal point, the operators and the keypad separator type text.
  - `Ctrl+Left` and `Ctrl+Right` move by word, and `Ctrl+Backspace` and
    `Ctrl+Delete` delete a word, in the editor.
  - The user keymap file accepts keypad keys, F13 to F35 and Super, Hyper and
    Meta chords.
- **`legacy`**: pushes nothing and keeps the bytes of a plain terminal. The
  Ctrl+Shift chords, the keypad keys as their own keys, the word-wise chords and
  the keypad aliases are then not available, and the user keymap file rejects
  the triggers the legacy protocol never sends. Every action keeps its
  fallback chord below, so nothing is lost in this mode.

Terminals that implement the Kitty keyboard protocol and deliver these chords
include kitty, Ghostty, WezTerm, foot, Alacritty and recent iTerm2. A
terminal multiplexer between the terminal and the shell (tmux, for example) may
swallow the Kitty sequences, so run the shell outside it to get the chords.

Fallbacks for the chords that `auto` adds, which a terminal without the protocol
never sends:

| IntelliJ chord (`auto`) | Fallback chord, kept in both modes | Action |
|---|---|---|
| `Ctrl+Shift+N` | `Ctrl+O` | Go to File |
| `Ctrl+Alt+Shift+N` | none, the palette (`Ctrl+P`) and the Navigate menu reach it | Go to Symbol |
| `Ctrl+Shift+F` | `Alt+F` | Find in Files |
| `Ctrl+Shift+Z` | `Ctrl+Y` | Redo |
| `Ctrl+Shift+I` | `Alt+Space` | Quick Definition |
| `Ctrl+Shift+NumPad +` | `F4` | Expand All |
| `Ctrl+Shift+NumPad -` | `Alt+C` | Collapse All |

The keypad keys have no fallback of their own: in a terminal without the
protocol the keypad sends the bytes of its main key, so Enter, the arrows and
Delete already act the same. The word-wise chords have no fallback; in a
terminal without the protocol the editor keeps the plain keys and nothing else
is bound in their place.

The user keymap file cannot yet bind `Ctrl+Shift+<letter>` distinctly. The
TamboUI parser drops Shift for a single character, so `Ctrl+Shift+N` in the file
means `Ctrl+N`, and `Ctrl+N` is not bound. Named keys keep their Shift, so
`Ctrl+Shift+Left` in the file is the Ctrl+Shift chord it reads as. Vintelli
does not hand-roll a parser for this; it is a TamboUI limitation.

Not verified in a live terminal yet: the Ctrl+Shift chords, the keypad keys and
the word-wise chords are covered by tests of the TamboUI event model, and each
one still needs a check in kitty or Ghostty.

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

## Go to Class and Go to Symbol

`Go to Class` (`Ctrl+N` in both profiles, and the Navigate menu or the command
palette) lists the types of the workspace: classes, interfaces, enums and
structs, library types included. `Go to Symbol` (`Ctrl+Alt+Shift+N` in the
`intellij` profile, and the same menu and palette) lists every kind of symbol,
methods and fields included. Both ask the language server for the workspace
symbols, so the matching (a camel hump such as `AbCo` finds
`AbstractController`) is the server's.

- Each row shows the kind, the name and the container: the package of a
  top-level type, or the outer type of a member.
- The query is sent once typing pauses for 150 ms. An answer that arrives after
  the query has moved on is dropped, so the rows always belong to the text in
  the box.
- Enter opens the symbol at its line. A library type has a `jdt://` location,
  which the editor does not open yet: its row is drawn dimmed with
  `(not openable)`, and Enter leaves the overlay open.
- Without a language server the overlay shows the reason in place of the rows.

`Ctrl+N` is free in both profiles, and `Ctrl+Shift+N` still runs Go to File in a
Kitty-capable terminal. A terminal without the Kitty protocol sends `Ctrl+Shift+N`
as the same byte as `Ctrl+N`, so there it runs Go to Class; Go to File keeps its
`Ctrl+O` fallback. Like `Ctrl+O`, `Ctrl+N` stays with a window that captures
input: the terminal keeps it for shell history and the ACP chat for a new
conversation, as IntelliJ's terminal does by default. `Ctrl+Alt+Shift+N` is a Ctrl+Alt chord, and tmux and ghostty
may intercept Ctrl+Alt letters before the shell sees them (see Focus Editor
below); the command palette and the Navigate menu are the fallback there.
Search Everywhere does not list these symbols as a tab yet.

## Focus Editor

`Focus Editor` (`Ctrl+F11` in both profiles, and the View menu or the command
palette) hides every tool window island: the project pane, the bottom tool
windows and the right tool windows. The editor takes the full width and height
between the toolbar and the status row. The tool stripes, the toolbar and the
status bar stay, so a window can still be opened with the mouse.

- The first press enters the mode and the second press leaves it. Leaving it
  restores the windows that were visible, the project pane, the bottom height,
  the project width and the splitter ratios exactly as they were stored.
- Opening any tool window leaves the mode and shows that window with the others
  that were visible: a stripe icon, its shortcut (`Alt+1`, `Alt+4` and so on, or
  the bare digit while no input has focus), or the project pane when a file is
  revealed in it.
- The mode is kept in the layout file, so a session that ends in focus mode
  starts in it. A layout file without that entry starts as it always did.

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
  IntelliJ chord, and a keypad twin (`KP_UP` for the arrow) is the same key as
  its main key; both are left out of the comparison.
- `different`: IntelliJ binds the action to other chords. The reason says why
  the chord stays (or is planned to move, #365 and #364).
- `not-deliverable`: IntelliJ's chord cannot be sent by a terminal, even in
  the `auto` mode (Double Shift, which needs key release events). The active
  chord stays and the reason names the alternative.
- `vintelli-only`: the action has no IntelliJ chord that this table can
  confirm, or Vintelli has no IntelliJ counterpart. Unconfirmed IntelliJ chords
  are never invented: they stay empty here.

A binding change must update the table. `IntellijKeymapTableTest` builds the
default profile with the real tool windows and fails when a bound action has
no row, when a row names an action the profile does not bind, when a row's
active chords differ from the bound ones, when a `same` row differs from
IntelliJ or a `different` row does not, when a chord is bound to two actions
outside the shared chords it documents (`Enter` for `select`, `confirm` and
`editor_open`, `KP_ENTER` for `select` and `confirm`, `Delete` for
`deleteForward` and `project_delete`, and the mouse wheel for `scroll` and its
directions, all set by the TamboUI presets and the focus), and when a non-`same` row has no reason. Adding a shared chord means
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
| confirm | Enter \| KP_ENTER | - | vintelli-only | Confirms a choice in TamboUI widgets; shares Enter with select and editor_open, the focus decides |
| select | Enter \| Space \| KP_ENTER | - | vintelli-only | Selects in TamboUI widgets; shares Enter with confirm and editor_open, the focus decides; Space names no IntelliJ action here |
| deleteBackward | Backspace | - | vintelli-only | Text editing in capturing inputs; the reference names no chord for it |
| deleteForward | Delete \| KP_DELETE | - | vintelli-only | Text editing in capturing inputs; shares Delete with project_delete, the focus decides |
| focusNext | Tab | - | vintelli-only | Moves focus between panes here; IntelliJ binds Tab to Indent Selection in the editor |
| focusPrevious | Shift+Tab | - | vintelli-only | Moves focus back here; IntelliJ binds Shift+Tab to Unindent in the editor |
| home | Home \| Ctrl+Home | Home \| Ctrl+Home | same | Move Caret to Line Start (Home) and to Text Start (Ctrl+Home) |
| end | End \| Ctrl+End | End \| Ctrl+End | same | Move Caret to Line End (End) and to Text End (Ctrl+End) |
| pageUp | PageUp \| KP_PAGE_UP | PageUp | same | Page Up |
| pageDown | PageDown \| KP_PAGE_DOWN | PageDown | same | Page Down |
| moveUp | Up \| KP_UP | Up | same | Move Caret Up on the arrow keys; the reference page does not list arrows, the chord is the editor one |
| moveDown | Down \| KP_DOWN | Down | same | Move Caret Down on the arrow keys; the reference page does not list arrows, the chord is the editor one |
| moveLeft | Left \| KP_LEFT | Left | same | Move Caret Left on the arrow keys; the reference page does not list arrows, the chord is the editor one |
| moveRight | Right \| KP_RIGHT | Right | same | Move Caret Right on the arrow keys; the reference page does not list arrows, the chord is the editor one |
| quit | Ctrl+C | - | vintelli-only | Terminal interrupt and the only quit chord; IntelliJ has no quit chord and Ctrl+C is Copy there (#365) |
| toggleDebugOverlay | Ctrl+Shift+F12 | - | vintelli-only | TamboUI debug overlay; IntelliJ binds Ctrl+Shift+F12 to Hide All Tool Windows, which a terminal cannot deliver (#363) |
| focusEditor | Ctrl+F11 | Ctrl+Shift+F12 | different | Focus Editor hides every tool window island; IntelliJ's Hide All Tool Windows is Ctrl+Shift+F12, which is toggleDebugOverlay here (#363), so Focus Editor takes Ctrl+F11, an F-key with a Ctrl modifier that no profile binds (docs/keymap.md) |
| SearchEverywhere | Ctrl+P | Double Shift | not-deliverable | Double Shift needs key release events, which only the full Kitty keyboard mode sends and Vintelli does not enable; Ctrl+P is the command palette stand-in |
| KeymapHelp | F1 | - | vintelli-only | F1 is not on the IntelliJ default keymap page (it lists Ctrl+F1 for Error Description and Ctrl+Q for Quick Documentation); here it opens the keymap help |
| shell_menu | F10 | - | vintelli-only | Opens the shell menu; no IntelliJ chord confirmed against the reference page |
| navigation_focusBreadcrumbs | Alt+Home | - | vintelli-only | Focus the navigation bar; no IntelliJ chord confirmed against the reference page |
| project_new_file | F7 | - | vintelli-only | New project file; IntelliJ binds F7 to Step Into in the debugger, and no New File chord is confirmed here |
| project_new_directory | F9 | - | vintelli-only | New project directory; IntelliJ binds F9 to Resume Program, and no new-directory chord is confirmed here |
| project_rename | Shift+F6 | Shift+F6 | same | Rename in the project pane; F2 is Next Highlighted Error in IntelliJ, so F2 moved to the editor action |
| project_delete | Delete | - | vintelli-only | Deletes the project entry; no IntelliJ chord confirmed; shares Delete with deleteForward, the pane scope decides |
| project_refresh | F5 | - | vintelli-only | Refreshes the project tree; IntelliJ's F5 is Synchronize in the project view, not confirmed against the reference page |
| project_select_opened_file | Alt+F1 | Alt+F1 | same | Reveals the open file in the project pane (crosshair); the nearest IntelliJ chord, since IntelliJ's Alt+F1 opens the Select In list instead |
| project_expand_all | F4 \| Ctrl+Shift+KP_ADD | Ctrl+Shift+NumPad + | different | Expand All is Ctrl+Shift+NumPad + in IntelliJ, which a Kitty-capable terminal sends as its own chord; F4 stays as the fallback for terminals without the Kitty keyboard protocol |
| project_collapse_all | Alt+C \| Ctrl+Shift+KP_SUBTRACT | Ctrl+Shift+NumPad - | different | Collapse All is Ctrl+Shift+NumPad - in IntelliJ, which a Kitty-capable terminal sends as its own chord; Alt+C stays as the fallback for terminals without the Kitty keyboard protocol |
| project_view_menu | Alt+V | - | vintelli-only | Pane header view menu; no IntelliJ chord confirmed |
| project_new_menu | Alt+N | - | vintelli-only | Pane header New menu; no IntelliJ chord confirmed |
| project_options_menu | Alt+O | - | vintelli-only | Pane header options menu; no IntelliJ chord confirmed |
| project_hide | Alt+P | Alt+1 | different | IntelliJ toggles the Project window with Alt+1, which is ActivateProjectToolWindow here (#364); Alt+P hides the pane |
| editor_open | Enter | - | vintelli-only | Opens the selected file in the project pane; no IntelliJ chord confirmed; shares Enter with confirm and select, the focus decides |
| editor_save | Ctrl+S | Ctrl+S | same | IntelliJ Save All is Ctrl+S; our action saves the open file |
| editor_undo | Ctrl+Z | Ctrl+Z | same | Undo |
| editor_redo | Ctrl+Y \| Ctrl+Shift+Z | Ctrl+Shift+Z | different | Redo is Ctrl+Shift+Z in IntelliJ, which a Kitty-capable terminal sends as its own chord; Ctrl+Y stays as the fallback for terminals without the Kitty keyboard protocol (docs/keymap.md) |
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
| editor_editBreakpointCondition | Alt+F8 | - | vintelli-only | Edits the condition of the breakpoint on the caret line; IntelliJ offers conditions only from its gutter menu |
| editor_nextProblem | F2 | F2 | same | Next Highlighted Error; the bare F2 trigger refuses Shift, and F8 is left unbound (docs/keymap.md) |
| editor_previousProblem | Shift+F2 | Shift+F2 | same | Previous Highlighted Error on its own Shift+F2 trigger (docs/keymap.md) |
| editor_quickFix | Alt+Enter | Alt+Enter | same | Quick fixes from the language server for the selection or caret, offered in a popup (docs/keymap.md) |
| editor_completion | Ctrl+Space \| Ctrl+@ | Ctrl+Space | same | Code completion from the language server at the caret, offered in a popup; Ctrl+@ is the NUL spelling of the same key that some terminals send (docs/keymap.md) |
| editor_quickDocumentation | Ctrl+Q | Ctrl+Q | same | Quick documentation of the symbol at the caret from the language server, in a popup beside the caret (docs/keymap.md) |
| editor_generate | Alt+Insert | Alt+Insert | same | Generate from the language server's source actions for the class at the caret, in a popup beside the caret; the vim profile binds no chord (docs/keymap.md) |
| editor_quickDefinition | Ctrl+Shift+I \| Alt+Space | Ctrl+Shift+I | different | Quick Definition is Ctrl+Shift+I in IntelliJ, which a Kitty-capable terminal sends as its own chord; a terminal without the protocol sends it as Ctrl+I, which is Tab, so Alt+Space stays as the fallback (IntelliJ's macOS keymap uses it for the same action, no profile binds it elsewhere, and terminals send it as ESC and a space); the palette and the Navigate menu reach it too (docs/keymap.md) |
| moveWordLeft | Ctrl+Left | Ctrl+Left | same | Move Caret to Previous Word; the editor text area takes it under the Kitty keyboard protocol |
| moveWordRight | Ctrl+Right | Ctrl+Right | same | Move Caret to Next Word; the editor text area takes it under the Kitty keyboard protocol |
| deleteWordBackward | Ctrl+Backspace | - | vintelli-only | Deletes the word before the caret in the editor under the Kitty keyboard protocol; no IntelliJ chord is confirmed on the reference page for this action |
| deleteWordForward | Ctrl+Delete | - | vintelli-only | Deletes the word after the caret in the editor under the Kitty keyboard protocol; no IntelliJ chord is confirmed on the reference page for this action |
| navigation_goToFile | Ctrl+O \| Ctrl+Shift+N | Ctrl+Shift+N | different | Go to File is Ctrl+Shift+N in IntelliJ, which a Kitty-capable terminal sends as its own chord; Ctrl+O stays as the fallback for terminals without the Kitty keyboard protocol (docs/keymap.md) |
| navigation_findInFiles | Alt+F \| Ctrl+Shift+F | Ctrl+Shift+F | different | Find in Files is Ctrl+Shift+F in IntelliJ, which a Kitty-capable terminal sends as its own chord; Alt+F stays as the fallback for terminals without the Kitty keyboard protocol |
| navigation_goToLine | Ctrl+G | Ctrl+G | same | Go to Line with the IntelliJ chord; the vim profile binds Alt+G instead (docs/keymap.md) |
| navigation_goToClass | Ctrl+N | Ctrl+N | same | Go to Class with the IntelliJ chord; Ctrl+Shift+N stays Go to File, so the unshifted chord is bound (docs/keymap.md) |
| navigation_goToSymbol | Ctrl+Alt+Shift+N | Ctrl+Alt+Shift+N | same | Go to Symbol with the IntelliJ chord, which needs the Kitty keyboard protocol; tmux and ghostty may intercept it, and the palette and the Navigate menu reach it (docs/keymap.md) |
| layout_project_wider | Alt+= | - | vintelli-only | Widens the project panel; no IntelliJ chord confirmed |
| layout_project_narrower | Alt+- | - | vintelli-only | Narrows the project panel; no IntelliJ chord confirmed |
| layout_bottom_taller | Alt+. | - | vintelli-only | Grows the bottom tool window; no IntelliJ chord confirmed |
| layout_bottom_shorter | Alt+, | - | vintelli-only | Shrinks the bottom tool window; no IntelliJ chord confirmed |
| shell.theme.switch | Ctrl+T | - | vintelli-only | Switches the shell theme; Ctrl+T is Update Project in IntelliJ and stays here as the theme switch (docs/keymap.md) |
| ActivateProjectToolWindow | Alt+1 | Alt+1 | same |  |
| ActivateDebugToolWindow | Alt+3 \| 3 | - | vintelli-only | Opens the debugger inspection window |
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
