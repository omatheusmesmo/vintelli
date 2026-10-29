# Spike 27: keyboard events and terminal colors

## Summary

- Reachable: printable characters, Ctrl+letter (except Ctrl+I, Ctrl+M, Ctrl+J, Ctrl+H and Ctrl+[, which are the same bytes as Tab, Enter, Backspace and Escape), Alt+character via the ESC prefix, navigation keys, Shift+Tab, and F-keys with modifiers.
- Unreachable: a lone Shift and Double-Shift, Ctrl+Shift+letter (same bytes as Ctrl+letter in legacy mode), Ctrl+Alt+letter (UNKNOWN), Super/Cmd, and key release. Double-Shift needs kitty flag 8 plus CSI-u support, which TamboUI 0.6.0 lacks.
- Terminal, multiplexer and window manager defaults (Omarchy tmux, ghostty, Hyprland) intercept Alt+digit and Ctrl+Shift+arrow before TamboUI sees them. The per-terminal capture was done afterwards in spike 49 (`docs/spikes/spike-49-per-terminal-capture.md`); its unverified items are tracked as follow-up issues there.
- Keymap: driven by `Bindings`. Vintelli must build its own quit binding because the built-in presets keep bare `q`. A focused text editor consumes plain text and Tab, so global chords use Ctrl/Alt or F-keys.
- Colors: three representations per palette slot, with the level chosen from environment variables (not from `CapabilityReport`), a user override, and `NO_COLOR` support.

## Findings

Scope: all facts below were verified directly against the five TamboUI 0.6.0 jars in the local repo
(`~/.m2/repository/dev/tamboui/{tui,core,widgets,jline3-backend,aesh-backend}/0.6.0/*.jar`)
by disassembling the classes with `javap` and reading the built-in resources inside
`tamboui-tui-0.6.0.jar`. The panama backend jar was checked from Maven Central. No code was modified.
The chord matrix below is protocol-level, derived from what the parser accepts; no key was pressed in any
terminal for this spike. The live per-terminal capture is in spike 49 (see "Per-terminal capture").

Note on the backends: 0.6.0 ships `tamboui-jline3-backend`, `tamboui-aesh-backend` and `tamboui-panama-backend`
(Maven Central and `tamboui-bom` 0.6.0; the local `~/.m2` only has 0.1.0 and 0.2.0-SNAPSHOT of panama, so the
0.6.0 jar was checked from Central). On Unix all three feed code points through `Backend.read(int)` into the
shared `EventParser`, so the parser defines the protocol ceiling. The panama `WindowsTerminal` reads Win32
console input records (`Kernel32.readConsoleInput`) and translates them, so Windows key delivery is not covered
by this analysis and is an open item. Panama is the native-image-friendly backend (epic principle 5), so this matters for the project.

### Keyboard event model

Event hierarchy in `dev.tamboui.tui.event` (`tamboui-tui`):

- `Event` marker interface; concrete types: `KeyEvent`, `MouseEvent`,
  `PasteEvent`, `ResizeEvent`, `TickEvent`, `ActionEvent`.
- `KeyCode` is an enum: `ENTER, ESCAPE, BACKSPACE, TAB, DELETE, INSERT, UP, DOWN,
  LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN, F1..F12, CHAR, UNKNOWN`.
  There is no `SPACE` constant; Space arrives as `CHAR` with code point 32.
- `KeyModifiers` is an immutable triple with constants `NONE, CTRL, ALT, SHIFT`
  and constructor `of(boolean ctrl, boolean alt, boolean shift)`. `KeyEvent`
  exposes `hasCtrl()`, `hasAlt()`, `hasShift()` and `is*` helpers such as `isCtrlC`,
  `isFocusNext` and `isConfirm`.
- `KeyEvent(code, modifiers, codePoint)` plus static factories `ofChar(char|int)`
  and `ofKey(KeyCode[, modifiers])`. Printable text is modeled as
  `KeyCode.CHAR` + `character()/codePoint()/string()`; control keys as a
  dedicated `KeyCode`. `isChar`, `isCharIgnoreCase`, `isKey` for matching.
- `isQuit()` is not hard-coded: it is `matches("quit")`, delegated to the bound
  `Bindings` (`KeyEvent.matches(String)` -> `Bindings.matches(Event, action)`).

### How raw bytes become events (`dev.tamboui.tui.event.EventParser`)

Disassembled `parseInput` / `parseControlChar` / `parseEscapeSequence` /
`parseCSI` / `parseSS3` / `parseVT` / `parseModifiedArrow`:

- Bytes 32..126 (and >= 128) arrive as plain `CHAR` events with `KeyModifiers.NONE`.
  Byte 0x7F is BACKSPACE, the usual Backspace byte. Uppercase letters are just uppercase chars:
  SHIFT is *not* reported for printable characters in the legacy path. Shift is only knowable for
  non-printable keys (`ESC[Z` -> `TAB + SHIFT`; `Shift+Tab` in property files).
- Control bytes 0..31 (`parseControlChar`): `Ctrl+letter` -> `ofChar(letter, CTRL)`
  (e.g. 0x03 -> `c`+CTRL, so `isCtrlC()` is true). Special mappings: 0
  (NUL, Ctrl+Space) -> `CHAR ' '` + CTRL; 8 -> `BACKSPACE` (0x7F is also Backspace); 9 -> `TAB`;
  10 and 13 -> `ENTER`; 27 (0x1B) -> `ESCAPE`; 1-7, 11, 12, 14-26 -> `Ctrl+letter` (26 is Ctrl+Z);
  28/29/30/31 -> `CHAR '\', ']', '^', '/'` + CTRL. Unmapped control bytes -> `KeyCode.UNKNOWN`.
- `ESC` handling: `parseEscapeSequence` peeks with a 50 ms timeout
  (`PEEK_TIMEOUT = 50`) after every ESC byte. Bindings are not consulted at parse time.
  Nothing behind ESC -> plain `ESCAPE`; `ESC[` -> CSI; `ESC O` -> SS3 (F1..F4, arrows, Home, End);
  any other printable after ESC -> `ALT` + char. So Alt+letter and Alt+digit arrive through the ESC prefix,
  and every standalone Escape is reported up to 50 ms late, independent of the keymap. Inside tmux the
  effective delay also includes tmux's `escape-time` (Omarchy sets 10 ms).
- CSI parsing (`parseCSI`): `<` -> SGR mouse (see below); finals
  `A B C D F H` -> arrows/Home/End; `Z` -> `Shift+Tab`; parameterized CSI
  (`ESC[1;5D`) -> `parseModifiedArrow` / `parseVT` with the xterm modifier
  parameter (param = 1 + shift*1 + alt*2 + ctrl*4, decoded by
  `parseModifierCode`).
- Final `~` with params (`parseVT`): 1=Home, 2=Insert, 3=Delete, 4=End,
  5=PageUp, 6=PageDown, 11-15 = F1-F5, 17-21 = F6-F10, 23-24 = F11-F12 (16 and 22 are unused),
  each with the modifier param. `ESC[7~` and `ESC[8~` (rxvt/linux-console Home/End) yield UNKNOWN,
  and `ESC[1;9C` (xterm Meta modifier 9) is decoded as an unmodified arrow. The built-in binding
  `toggleDebugOverlay = Ctrl+Shift+F12` works because F-key modifiers are legacy-encodable,
  while letter modifiers are not.
- Anything else, notably final `u` and the `ESC[27;...~` / `ESC[<code>;<mods>u`
  forms, reaches neither handler and yields `KeyCode.UNKNOWN`.

### Kitty keyboard protocol

- No kitty protocol support exists in 0.6.0: no CSI-u parsing, no `?u` enable
  sequence is ever emitted (grepped every class string in all five jars), and
  there are no key *release* events anywhere in the event model. `KeyEvent`
  has no notion of handedness (left/right variants) or key location either.
- Because TamboUI never sends the enable sequence, kitty, alacritty, ghostty and Windows Terminal
  keep sending legacy encodings, so modified letters are unreachable rather than broken. CSI-u only
  reaches TamboUI if something else turns it on (leftover state from a previous program, or tmux
  `extended-keys always`); in that case the keys become `UNKNOWN`.
- Protocol flags that a feature would need: flag 1 (disambiguate) for Ctrl+Shift+letter, Ctrl+I vs Tab
  and Ctrl+M vs Enter; flag 2 for release and repeat events; flag 8 (report all keys as escape codes)
  for modifier-only keys such as Double-Shift, and for Super/Cmd when the terminal or window manager
  does not grab it. The flag numbers are from the kitty specification as recalled, not re-checked against its text.
- Double-Shift (IntelliJ-style Search Everywhere): impossible in TamboUI 0.6.0. A lone Shift press emits
  no bytes in legacy encoding, so there is nothing to time. It needs the Kitty keyboard protocol with
  flag 8 (modifier keys reported as their own events) plus TamboUI support for CSI-u, and neither exists.
  For #29 the command palette needs another chord, for example `Ctrl+Space` or `Ctrl+P`, subject to the
  caveats in the chord matrix below.

### Ctrl+C / quit semantics and raw mode

- `JLineBackend.enableRawMode()` (`tamboui-jline3-backend`): saves
  `Terminal.getAttributes()`, calls `Terminal.enterRawMode()`, then explicitly
  clears `Attributes.LocalFlag.ISIG`, so the terminal driver never converts
  Ctrl+C to SIGINT: it is delivered as byte 0x03 and becomes a `KeyEvent` with
  `isCtrlC() == true`. App-level quit must go through bindings.
- Quit bindings: all five built-in sets (including `standard`) keep bare `q` and `Q` bound to `quit`.
  The `quit = Ctrl+c` lines in `emacs`, `intellij` and `vscode` append rather than replace, and `vim`
  inherits `q, Q, Ctrl+c`. Verified by loading the built-in sets: `describeBindings("quit")` returns
  `q, Q, Ctrl+c` for vim and `q, Q, Ctrl+c, Ctrl+c` for emacs, intellij and vscode. So with any built-in
  preset, typing a bare `q` or `Q` into a text input quits the app. Vintelli must build its own `Bindings`
  (`toBuilder().unbind("quit").bind(KeyTrigger.ctrl('c'), "quit")`) instead of using the presets as-is.
- In the TuiRunner error mode, `isQuit()` or `ESCAPE` quit immediately (`handleErrorModeEvents`).
- `AeshBackend` (`tamboui-aesh-backend`) feeds the same parser. Its raw mode (aesh `Connection.enterRawMode`)
  clears ICANON, ECHO, IEXTEN, ISIG, IXON, ICRNL and INLCR, so Ctrl+C behaves as on JLine.
- ISIG is cleared by both backends and IXON by aesh, so Ctrl+S, Ctrl+Q and Ctrl+Z arrive as bytes. Keep this
  in mind for the IntelliJ profile (Ctrl+S save, Ctrl+Z undo).
- `enableRawMode()` also writes DECSET 2027 (grapheme clustering) via `Mode2027Support.enable`; the class also
  exposes `query` (DECRQM), but the backends do not call it.

### Trigger / binding layer

- `Bindings` interface (`matches(Event, action)`, `actionFor(Event)`,
  `triggersFor(action)`, `describeBindings(action)`, `toBuilder()`), built via
  `DefaultBindings.Builder` (`bind/rebind/unbind/copyFrom/build`), and
  loadable from `.properties` resources/streams/paths via
  `BindingSets.load(Path)`/`load(InputStream)`/`loadResource(String)`.
  Built-in sets: `standard`, `vim`, `emacs`, `intellij`, `vscode`
  (`dev/tamboui/tui/bindings/*.properties` inside the jar).
- Trigger-string grammar (`BindingSets.parseTrigger`/`parseKeyTrigger`):
  case-insensitive `ctrl+`, `alt+`, `shift+` prefixes in any order; remainder
  matched against `KeyCode` names, `space`/`Space`, or a single literal
  character. Mouse triggers start with `mouse.` (`Mouse.Left.Press`,
  `Mouse.ScrollUp`, ...) and also carry ctrl/alt/shift
  (`MouseTrigger.ctrlClick()` etc.).
- `KeyTrigger` also has programmatic factories: `key(KeyCode, ctrl, alt, shift)`,
  `ch`, `chIgnoreCase`, `ctrl(char)`, `alt(char)`, with `describe()` producing
  `Ctrl+`, `Alt+`, `Shift+` prefixed strings for help menus.

### Chord matrix (protocol-level; the per-terminal capture is in spike 49, see "Per-terminal capture")

| Chord | Reachable in TamboUI 0.6.0 | Notes | Intercepts and caveats |
|---|---|---|---|
| printable char | yes | `CHAR`, `NONE` modifiers | - |
| `Ctrl+letter`, `Ctrl+@`, `Ctrl+]`, `Ctrl+^`, `Ctrl+_`, `Ctrl+Space` | yes, except `Ctrl+I` (= Tab), `Ctrl+M` and `Ctrl+J` (= Enter), `Ctrl+H` (= Backspace, also sent by many terminals as 0x7F), `Ctrl+[` (= Escape) | same bytes as the dedicated key; `Ctrl+Shift+letter` sends the same byte as `Ctrl+letter` in legacy terminals | Ctrl+B is tmux's secondary prefix in Omarchy |
| `Shift+letter` (as its own binding) | no | arrives as uppercase `CHAR`, `Shift` unreported; don't bind | - |
| arrows, Home/End, PgUp/PgDn, Insert, Delete, Tab, Esc, Enter, Backspace | yes | enum `KeyCode`; Escape is reported up to 50 ms late | - |
| `Shift+Tab` | yes | `ESC[Z` -> `TAB + SHIFT` | - |
| modified arrows / Home / End (`Ctrl+Shift+arrow`, `Alt+Left`, ...) | yes | `ESC[1;<mods>A` family | `Ctrl+Shift+arrow` is bound by default in ghostty (tab switching); `Alt+Left` is bound in Omarchy tmux |
| `F1..F12` bare | yes | SS3 + CSI | - |
| `F1..F12` with any modifier combo (`Ctrl+Shift+F12`) | yes | `ESC[<n>;<mods>~` | - |
| `Alt+letter/digit/punctuation` | yes | ESC prefix; standalone Escape delayed up to 50 ms | Intercepted by default in Omarchy tmux (`M-1`..`M-9`) and ghostty (`alt+1..`); not a safe fallback. xterm (`metaSendsEscape` off by default) and macOS Terminal/iTerm (Option as Meta off) do not send the ESC prefix by default (not verified here) |
| `Ctrl+Shift+letter` | no | indistinguishable from `Ctrl+letter` in legacy mode; becomes `UNKNOWN` only if the terminal is in CSI-u mode | ghostty binds many `ctrl+shift+<letter>` terminal actions |
| `Ctrl+Alt+letter` | no | terminal sends `ESC <ctrl byte>`; TamboUI maps it to `UNKNOWN` (verified) | - |
| `Alt+Backspace`, `Alt+Esc` | no | `ESC 0x7F` and `ESC ESC` map to `UNKNOWN` (verified) | - |
| `Shift+digit` | no | arrives as the shifted character; no SHIFT modifier | - |
| `Super` / Cmd | no | not in `KeyModifiers`; not encoded in legacy sequences | consumed by Hyprland/Omarchy, which binds almost everything on SUPER |
| `Shift` alone / Double-Shift | no | modifier-only presses are not sent in legacy encoding; needs kitty flag 8 + CSI-u support | - |
| key release / hold repeat distinction | no | no release events in the event model | - |

Unreachable-chord candidates for the keymap (`Super+*`, `Ctrl+Shift+letter`, `Shift+digit`, `Ctrl+Alt+letter`)
should be treated as reserved-but-inert in #29: never bind them, and never rely on them for a feature's only
entry point. Alt+digit is reachable at the protocol level but is intercepted by default in Omarchy tmux and
ghostty, so it is not a safe fallback for palette or workspace switching. Ctrl+Shift+letter is not reachable at all.

### Intercepts outside TamboUI

Terminal, multiplexer and window manager defaults decide what reaches TamboUI at all. Findings from the
development machine's configuration files:

- Omarchy tmux (`~/.local/share/omarchy/config/tmux/tmux.conf`, mirrored in `~/.config/tmux/tmux.conf`) binds
  `M-1`..`M-9` (select-window), `M-Left/Right/Up/Down`, `M-Enter`, `M-S-Enter`, `M-Escape`, and `C-M-` arrows
  and `C-M-S-` arrows in the root table. It also sets `prefix2 C-b`. Inside Omarchy tmux those Alt chords never
  reach Vintelli, and Ctrl+B is tmux's secondary prefix there (IntelliJ uses Ctrl+B for Go to declaration).
- Ghostty 1.3.1 defaults (`ghostty +list-keybinds --default`) include `alt+1..` (goto_tab),
  `ctrl+shift+arrow_left/right` (previous/next tab), `ctrl+shift+page_up/down` (jump_to_prompt), `ctrl+shift+tab`,
  and many other `ctrl+shift+<letter>` terminal actions.
- Hyprland/Omarchy defaults (`~/.local/share/omarchy/default/hypr/bindings/*.lua`) bind almost everything on
  SUPER. Plain ALT is bound only with media and Print keys. `CTRL+ALT+DELETE`, `CTRL+ALT+TAB` and
  `CTRL+ALT+SHIFT+TAB` are also bound.

### Mouse, bracketed paste, and tmux/screen considerations

- Mouse: `Backend.enableMouseCapture(boolean)` emits `?1000h ?1002h` always, `?1003h` only with motion
  tracking, then `?1015h ?1006h` (verified in the JLine and panama bytecode; the aesh backend implements the
  method, but its sequences were not checked). SGR mode is what the parser decodes (`parseMouseSGR`). The runner
  enables mouse only when `mouseCapture(true)`, and motion tracking only when `mouseMotion(true)`: a status-bar
  hover or mouse-menu design must treat motion as opt-in, since `?1003h` adds event volume.
- `TuiConfig.defaults()`: `mouseCapture`, `mouseMotion` and `bracketedPaste` are false;
  `rawMode`, `alternateScreen` and `hideCursor` are true.
- Bracketed paste: `TuiRunner` enables `?2004h` only when `TuiConfig.bracketedPaste(true)`. The parser folds
  the paste into one `PasteEvent`, so text inputs should consume `PasteEvent` rather than expect modified chars.
- tmux: because everything is SGR/legacy, basic chords survive tmux without special config. Leave
  `extended-keys` at `off` or `on` (Omarchy ships `on` with `csi-u`, which is safe because TamboUI never
  requests extended keys); do not set `always`, which turns Ctrl+Enter, Ctrl+Shift+letter and similar keys into
  `UNKNOWN`. tmux `escape-time` affects Escape and Alt latency (Omarchy: 10 ms). tmux also intercepts keys in its
  root table (see "Intercepts outside TamboUI"). Mouse inside tmux requires `set -g mouse on` (Omarchy already
  sets it); scroll then reaches the app as SGR events. Bracketed paste passes through tmux when the inner app
  enables `?2004`. Verifying all three inside tmux/screen is an explicit acceptance item for #29, since the
  backends never query the terminal during raw mode.
- No focus tracking (`?1004`) exists in the jars; do not design for focus
  in/out events in the status bar.

### Colors

`dev.tamboui.style` (`tamboui-core`):

- `Color` is an interface with constants `RESET`, `BLACK..WHITE`, `GRAY`, and the bright variants
  `DARK_GRAY`, `LIGHT_RED..LIGHT_CYAN` and `BRIGHT_WHITE`. The `AnsiColor` enum uses `BRIGHT_*` for all eight
  bright names and has `fgCode()`/`bgCode()`.
- Factories: `ansi(AnsiColor)`, `indexed(int 0..255)`,
  `rgb(int r, g, b)` (throws `IllegalArgumentException` outside 0..255), and
  `hex(String)` -> `Color$Rgb.fromHex`, which strips an optional leading `#`
  and requires exactly 6 hex digits (else "Hex color must be 6 characters").
- Degradation: there is **none** in the library.
  `Color$Rgb.toAnsiForeground()/toAnsiBackground()` unconditionally emit
  `38;2;r;g;b` / `48;2;r;g;b` truecolor SGR. `Color$Indexed` emits `38;5;n` /
  `48;5;n`; `Color$Ansi` emits the 30/90 family. Nothing in the jars reads
  `COLORTERM` to decide (checked all jars for that string). On a 256-color or
  16-color terminal, hex colors are passed straight through and the *terminal*
  (or nothing) does the approximation, so the app must do its own downgrade.
- `Color$Named(String, Color)` exists for theme-overridable color slots;
  widgets already use this pattern with `PropertyDefinition`s
  (`TextInput.CURSOR_COLOR`, `TextInput.PLACEHOLDER_COLOR`).
- Environment detection is a reporting API, not styling logic. `dev.tamboui.capability.core.CoreCapabilityProvider`
  lists `TERM`, `COLORTERM`, `TERM_PROGRAM`, `TERM_PROGRAM_VERSION`, `LANG`, `LC_ALL`, `TAMBOUI_BACKEND` and
  `tamboui.backend.*` properties through `CapabilityReport`/`CapabilityReportBuilder`.
  `CapabilityReport.environmentBySource()` returns only the variable names; the values are read privately inside
  `print()`. `Capabilities.detect()` only lists which variables are inspected and can print them; the values are
  not exposed. A palette switcher must read `System.getenv("COLORTERM")`, `TERM` and `NO_COLOR` directly.

### Color strategy

Define palette constants carrying *three* representations per slot:
`Color.rgb(...)`/`Color.hex(...)` as the primary,
`Color.indexed(n)` as the 256-color fallback, and an
`AnsiColor`-based 16-color fallback. Pick the level from the environment:

- `COLORTERM=truecolor` or `24bit` -> hex.
- `TERM` containing `256color` -> indexed.
- Otherwise -> Ansi.

Honor `NO_COLOR`, and provide an override (env var or config key) so users can force a level.
Caveats: `COLORTERM` may not be set by Windows Terminal (not verified here), is usually not forwarded over ssh,
and inside tmux depends on the outer client's environment. tmux passes 24-bit colors through only when the
RGB/Tc flag is known (Omarchy adds `terminal-overrides ",*:RGB"`).

The 256 fallbacks below were recomputed with `Color.indexedToRgb` over indices 16-255 using Chebyshev, Euclidean and
Manhattan distance. Nine of the original ten agree across all three; orange is the exception and is set to 173.
Rows marked TBD must be recomputed for the shipped values. Visual tuning happens in the theme issue.

| Slot | Hex | 256 fallback | 16-color fallback |
|---|---|---|---|
| background | `#2B2B2B` | 235 | `AnsiColor.BLACK` |
| panel/alt background | `#3c3f41` (shipped on `main`) | TBD | `AnsiColor.BRIGHT_BLACK` |
| foreground | `#A9B7C6` | 146 | `AnsiColor.WHITE` |
| comment/secondary | `#808080` | 244 | `AnsiColor.BRIGHT_BLACK` |
| keyword/accent orange | `#CC7832` | 173 (172 is the saturated alternative) | `AnsiColor.YELLOW` |
| string green | `#6A8759` | 65 | `AnsiColor.GREEN` |
| number blue | `#6897BB` | 67 | `AnsiColor.CYAN` |
| annotation/warn | `#E8BF6A` | 179 | `AnsiColor.BRIGHT_YELLOW` |
| red/error | `#FF6B68` | 203 | `AnsiColor.BRIGHT_RED` |
| selection | `#365880` (shipped on `main`) | TBD | `AnsiColor.BLUE` |
| bright/highlight | `#ffffff` (shipped on `main`) | TBD | `AnsiColor.BRIGHT_WHITE` |

Background and panel both map to `BLACK` in the 16-color column, which hides the panel distinction; `BRIGHT_BLACK`
is suggested for the panel, or separate the panel with borders. The panel, selection and bright hexes come from
`VintelliShell` on `main`; the other hexes are not verified as Darcula values.

Recommendations: expose slots as `Color$Named` defaults so users and themes can
override them (CSS properties already support this), keep the "one slot, three
representations" rule instead of relying on the terminal to degrade hex, and
never hardcode hex-only colors in widgets. Underline colors (`Color.toAnsiUnderline()`) follow the same
downgrade rule as foreground and background.

## Per-terminal capture

Issue #27 asked for a table of working and unreachable chords per terminal. It was produced in the follow-up issue
#49, committed as `docs/spikes/spike-49-per-terminal-capture.md` (live chord bytes for alacritty, kitty, ghostty and
foot, plain and inside tmux with `extended-keys off / on / always` (foot: off only), plus one run against the Omarchy
tmux config, CSI-u, modifyOtherKeys and the color checks). The chord matrix above survives it with two corrections:
tmux re-encodes Home to `ESC[1~`, and tmux swallows Ctrl+B (its prefix). Several items in that capture are marked
UNVERIFIED there and each has its own follow-up issue.

## Key map recommendation

1. Bind only legacy-encodable chords: `CHAR` (plain, Ctrl+, Alt+), all
  `KeyCode` nav keys, `Shift+Tab`, modified arrows/Home/End, F1..F12 with
  modifiers. Treat `Ctrl+Shift+letter`, `Ctrl+Alt+letter`, `Shift+digit`, `Super+*`, and key
  release as reserved-but-inert. Do not use Alt+digit as the only path to a feature.
2. Quit is bindings-driven (`quit` action). Do not use the built-in presets as-is, because all five keep bare
  `q` and `Q` bound to quit. Build Vintelli's own `Bindings` (`toBuilder().unbind("quit").bind(KeyTrigger.ctrl('c'), "quit")`).
  Keep raw-mode expectations (ISIG off, no SIGINT) even in inline mode.
3. Do not plan Double-Shift. Offer Search Everywhere via the palette chord.
4. Prefer `Bindings.Builder`/properties (`BindingSets.load`) for the user
  keymap; keep the five built-in sets as presets and honor
  `describeBindings` in the status bar/help menu.
5. Ctrl+I, Ctrl+M, Ctrl+J, Ctrl+H and Ctrl+[ are the same keys as Tab, Enter, Backspace and Escape. Do not give
  them their own action.

## Toolkit and text input routing

- PR #36 (spike 24) moves the shell to the Toolkit API (`ToolkitRunner`, `ToolkitApp`, `ToolkitTestRunner`).
  The `TuiRunner` vocabulary in this document applies to the lower-level runner; the acceptance criteria below name both.
- PR #37 (TextArea limits, issue #30): `TextAreaElement` inserts four spaces on `Tab`, which conflicts with
  `focusNext = Tab`, and leaves Ctrl and Alt characters unconsumed.
- Routing rule: a focused text editor consumes plain text and Tab; global chords must use Ctrl/Alt or F-keys.
  Because Ctrl+I is the same byte as Tab, a global Ctrl+I would be taken by the focused editor's Tab handling.

## Acceptance criteria for #29

- A visible "key echo" debug surface dumping `KeyEvent.toString()` plus raw code points, used to capture
  the chord matrix manually on alacritty, kitty, ghostty, Windows Terminal,
  plain xterm and tmux/screen inside each. (The `toggleDebugOverlay` action can be reused as the trigger only;
  `DebugOverlay` shows render statistics, not key input.)
- The chord matrix from this doc re-confirmed or corrected on those terminals;
  unreachable chords documented as such in the keymap docs.
- Unit tests via TamboUI Pilot (`TuiTestRunner`), or the Toolkit equivalent (`ToolkitTestRunner`), feeding synthetic
  `KeyEvent`s/`PasteEvent`s through `Bindings.matches`/`actionFor` for the
  whole default keymap, including the `quit`/cancel conflicts (bare `q` vs
  text input) and the Escape + 50 ms window behavior.
- Keymap source of truth is `Bindings` (not ad-hoc key checks), and
  user-config file support exercised through `BindingSets.load(Path)`.
- A `TuiConfig` decision recorded: `mouseCapture`, `mouseMotion`,
  `bracketedPaste`, `altScreen/off for status bar` per the defaults above (or the Toolkit equivalents:
  `ToolkitRunner.Builder.mouseCapture/bracketedPaste/bindings`, `EventRouter`).

These criteria are not yet in the body of #29 and must be copied there.

## Follow-up work

- Existing code on `main` (`VintelliShell`) hardcodes `Color.hex(...)`, uses `key.isChar('k')`/`isChar('j')`,
  uses digit keys for tool windows, and uses `isQuit()` (bare `q` quits). These are follow-up work for #26/#29, not part of this spike.

## Follow-up issues

Work not executed in this spike was tracked in #49; the per-terminal capture was done in spike 49, and its
unverified items are listed with their issue numbers in the "Follow-up issues" section of that document.
