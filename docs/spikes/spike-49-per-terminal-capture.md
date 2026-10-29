# Spike 49: per-terminal keyboard and color capture

Follow-up of spike 27 (`docs/spikes/spike-27-keyboard-and-colors.md`); the per-terminal capture matrix it deferred.
Sources: issue #49 (chord matrix in alacritty, kitty and ghostty, plain and inside tmux with extended-keys off, on,
always; COLORTERM and truecolor behavior; 256 color fallback) plus the extra TERM variants (foot, xterm-256color,
vt100, linux console) and encoding families (kitty protocol / CSI u, modifyOtherKeys, legacy X10 mouse) that came up
while scoping the spike.

Every statement below says what the recorded data supports. Anything else is labeled UNVERIFIED or hypothesis and has
its own follow-up issue (see "Follow-up issues").

## Summary

- The chord matrix was captured live on one machine (Omarchy, Hyprland 0.56): alacritty 0.17.0, kitty 0.49.2,
  ghostty 1.3.1, foot 1.28.0, tmux 3.8. Coverage is not uniform: alacritty, kitty and ghostty were run plain and
  inside tmux with `extended-keys off / on / always`; foot was run plain and inside tmux with `extended-keys off`
  only. One more run used the user's own tmux config (`~/.config/tmux/tmux.conf`, the Omarchy-flavored one), in
  alacritty only.
- Reproducibility: the recorder script and the raw logs are NOT committed, so this capture cannot be independently
  reproduced from this PR. The tables are the author's transcription of those logs (#82).
- Legacy encodings were identical across the four terminals for every chord captured, with one exception: kitty
  produced no bytes for Ctrl+Shift+Left (UNVERIFIED, hypothesis: injection quirk, #92). Observed: Ctrl+I arrives as
  the Tab byte (0x09), Ctrl+[ as Escape (0x1b), Alt+digit as ESC + digit, Ctrl+Shift+arrow as `ESC[1;6D`, F5/F12 as
  `ESC[15~`/`ESC[24~`, Shift+Tab as `ESC[Z`. Ctrl+M arrived as the same byte as Enter, recorded as 0x0a; that value
  is suspect (the recorder may not have cleared ICRNL, and a real terminal sends 0x0d for Enter, #81), so only the
  equality Enter = Ctrl+M is supported. Ctrl+H arrived as 0x08, a different byte from Backspace (0x7f); that TamboUI
  maps 0x08 to Backspace comes from spike 27, not from this capture.
- With the kitty keyboard protocol flag 1 pushed by the app (`CSI > 1u`), the five Ctrl chords probed (Ctrl+I, Ctrl+M,
  Ctrl+H, Ctrl+[, Ctrl+Space) arrived as `CSI <codepoint>;5u` and Escape as `CSI 27u` on all four terminals, while
  arrow and F-key finals stayed in their CSI form and Tab stayed 0x09 (Ctrl+Enter is a gap on ghostty, see below).
  TamboUI 0.6.0 parses none of this (spike 27), so this is an enable-side option to keep OFF for 0.1.0.
- `modifyOtherKeys=2`: foot and ghostty emitted xterm-style `CSI 27;mod;code~` forms (for example Ctrl+I ->
  `ESC[27;5;105~`); alacritty and kitty kept the legacy bytes. TamboUI cannot parse these either.
- tmux 3.8 (minimal config): chord bytes passed through unchanged for every chord delivered, and the only
  re-encoding observed was Home (plain `ESC[H`, under tmux `ESC[1~`). The `extended-keys` setting made no observable
  difference, but only legacy-mode input was tested: the app never pushed CSI-u or modifyOtherKeys, so this says
  nothing about what tmux does with those protocols, in particular with the Omarchy config (`extended-keys on`,
  `extended-keys-format csi-u`). That case is NOT tested (#83). tmux's prefix keystroke (Ctrl+B) was swallowed.
- Inside the Omarchy tmux config (alacritty only): Alt+1, Alt+2, Alt+3, Alt+Left and Alt+5 reached the inner app as
  zero bytes. Those are bound in that config (M-1..M-9, M-Left/Right/Up/Down and others). Alt+letter inside that
  config was NOT captured (#84). The config's primary prefix is Ctrl+Space (`prefix C-Space`, `prefix2 C-b`); Ctrl+Space
  was not captured live there (#85).
- Colors: `COLORTERM` was `truecolor` in the inner app environment on all four terminals (origin, terminal or spawner,
  not determined); kitty and ghostty force their own `TERM`; inside tmux the inner app sees `TERM=tmux-256color`,
  `TERM_PROGRAM=tmux`. Truecolor fills sampled from screenshots matched the requested values within about 10 per
  channel on all four (not exact). `Color.indexed(173)` sampled as the xterm-256 value `#d7875f` (215,135,95), but for
  two of the four terminals only after correcting for an assumed uniform dim factor of about 0.8 that was not measured
  (#89). One machine, one theme.
- UNVERIFIED or not run: legacy X10 mouse reporting (#93), vt100 and the Linux console (#94), Windows Terminal and the
  panama backend (#95), kitty legacy Ctrl+Shift+Left (#92), ghostty Ctrl+Enter and lone modifiers (#90), decoding of
  press/release sequences (#91), Home and arrows under DECCKM (#96).

## How the capture was done

Raw byte recorder: a small ad hoc Python script under `/tmp` put the pty in a raw-like mode (no echo, no ISIG, ICANON
or IEXTEN) and logged every read chunk with a millisecond timestamp; timestamps let chunks be attributed to the chord
injected right before them. The exact termios flags are not documented: it is not recorded whether ICRNL, INLCR and
IXON were cleared, which affects the Enter and Ctrl+M rows (#81). Chords were injected synthetically: for alacritty,
kitty and foot via Hyprland's `hl.dsp.send_shortcut` (targeted at the window by class, no focus changes), for ghostty
via Hyprland focus plus `wtype`, because `send_shortcut` did not deliver key events to ghostty windows on this build.
Ghostty results for modifiers (lone modifiers, Ctrl+Enter) may therefore be injection artifacts rather than ghostty
behavior (#90). Enable and disable sequences (`CSI > 1u`, `CSI > 8u`, `CSI > 4;2m` and their pop/reset) were printed
by the recorder itself, so each phase starts with the wanted keyboard mode. Color checks were full-screen background
fills sampled from `grim` screenshots with PIL; some samples were taken while a Hyprland dim or animation was still
active (see "Colors").

Not committed: the recorder script and the raw logs are not part of this PR. The capture is therefore not
independently reproducible from the repository, and the tables cannot be checked against their source. Committing the
recorder and a trimmed log is tracked in #82. What reaches the inner app is what TamboUI's `EventParser` would see
byte-for-byte, so the parser ceiling from spike 27 applies unchanged; no TamboUI build was used for input.

Slot/order: each phase re-runs the same chord order (Char `a`, Tab, Escape, Backspace, Enter, Home, F5, F12, Shift+Tab,
Ctrl+I, Ctrl+M, Ctrl+H, Ctrl+[, Ctrl+Space, Ctrl+Left, Ctrl+Shift+Left, Alt+1, Alt+5, Ctrl+B last). The trailing Ctrl+B
is deliberate: with tmux it must be swallowed (prefix test). Ctrl+J is not in this list, and Alt+S appears only in the
modifyOtherKeys phase.

## Legacy encodings (plain, no tmux, no keyboard-mode pushes)

Identical bytes in alacritty 0.17.0, kitty 0.49.2, ghostty 1.3.1 and foot, except where noted:

| Chord | Bytes | Note |
|---|---|---|
| char `a` | 61 | plain CHAR path |
| Tab | 09 | |
| Escape | 1b | late by up to the ESC peek timeout (spike 27) |
| Backspace | 7f | |
| Enter | 0a | SUSPECT: a terminal normally sends CR (0x0d); ICRNL may have rewritten it (#81) |
| Home | `ESC[H` | 1b 5b 48; normal cursor-key mode only, DECCKM not tested (#96) |
| F5 | `ESC[15~` | |
| F12 | `ESC[24~` | |
| Shift+Tab | `ESC[Z` | the legacy Shift+Tab report; shifted arrows, Home and F-keys use a modifier-parameter form (for example `ESC[1;2D`) per xterm ctlseqs, not captured here |
| Ctrl+I | 09 | same byte as Tab: not a distinct key |
| Ctrl+M | 0a | same byte as the recorded Enter value (see the Enter note): not a distinct key |
| Ctrl+H | 08 | a different byte from Backspace (0x7f); equating them is TamboUI's mapping (spike 27), not a captured fact |
| Ctrl+[ | 1b | same byte as Escape: not a distinct key |
| Ctrl+Space | 00 | NUL (Ctrl+@) |
| Ctrl+Left | `ESC[1;5D` | |
| Ctrl+Shift+Left | `ESC[1;6D` | alacritty, ghostty and foot only; kitty produced no bytes (UNVERIFIED, #92) |
| Alt+1 | `ESC 1` | 1b 31 |
| Alt+5 | `ESC 5` | 1b 35 |
| Ctrl+B | 02 | last chord in the batch; swallowed inside tmux, see below |

kitty and Ctrl+Shift+arrow: in every kitty run (legacy and `modifyOtherKeys` phases) the synthetic `ctrl shift Left`
did not produce bytes, while under the CSI-u push it arrived as `ESC[1;6D`. Hypotheses, none tested: a synthetic-event
quirk of this capture, or a default kitty binding consuming the chord. The row is UNVERIFIED for kitty (#92). The
other three terminals delivered modified arrows.

## Kitty keyboard protocol (`CSI > 1u` pushed by the app, flag 1: disambiguate)

Byte-identical on all four terminals for the rows below, except where noted:

| Chord | Bytes | Note |
|---|---|---|
| Ctrl+I | `CSI 105;5u` | distinct event at last |
| Ctrl+M | `CSI 109;5u` | |
| Ctrl+H | `CSI 104;5u` | |
| Ctrl+[ | `CSI 91;5u` | |
| Ctrl+Space | `CSI 32;5u` | |
| Alt+1 | `CSI 49;3u` | |
| Ctrl+Enter | `CSI 13;5u` | alacritty and foot; ghostty emitted nothing in this run (cause unknown; a default `ctrl+enter` binding is a hypothesis, not checked); kitty not recorded in this row; UNVERIFIED for ghostty and kitty, #90 |
| Ctrl+Shift+Left | `ESC[1;6D` | arrows stay CSI even with flag 1 |
| F5 | `ESC[15~` | F-keys stay CSI |
| Tab | 09 | Tab stays legacy even under flag 1 |
| Escape | `CSI 27u` | standalone Escape is now an encoded event (twice sent, twice same) |

Practical: since each of these bytes is a complete CSI-u event, an app that enables flag 1 can finally tell Ctrl+I and
Tab apart, but it must own a CSI-u decoder for the keyboard as a whole (partial support would be worse: mixed
protocols per keystroke). TamboUI 0.6.0 has no CSI-u support (spike 27), and it never enables the protocol itself, so
today's state in TamboUI: legacy bytes only.

## modifyOtherKeys=2 (`CSI > 4;2m` pushed by the app)

| Chord | foot | ghostty | alacritty | kitty |
|---|---|---|---|---|
| Ctrl+I | `ESC[27;5;105~` | `ESC[27;5;105~` | 09 (unchanged) | 09 (unchanged) |
| Ctrl+Shift+Left | `ESC[1;6D` | `ESC[1;6D` | `ESC[1;6D` | no bytes in this capture |
| F5 | `ESC[15~` | `ESC[15~` | `ESC[15~` | `ESC[15~` |
| Ctrl+Enter | `ESC[27;5;13~` | no bytes in this run | 0a (unchanged; see the Enter note) | 0a (unchanged) |
| Alt+S | `ESC[27;3;115~` | `ESC[27;3;115~` | `ESC s` (1b 73) | `ESC s` (1b 73) |
| Tab | 09 | 09 | 09 | 09 |

So foot and ghostty honored xterm's modifyOtherKeys (the `CSI 27;mods;code~` form), while alacritty 0.17 and kitty
0.49 left the bytes unchanged, identical to the legacy table. Whether that means "not supported" or is a
configuration or injection effect was not checked. Same protocol-class caveat as CSI-u: parsing `27;...` sequences is
something TamboUI does not do, so the app must not rely on it.

## `CSI > 8u` (flag 8: report all keys) and lone modifiers

Lone modifier presses under the legacy encoding produced zero bytes (all four terminals). With flag 8 pushed, the
bytes below were recorded. They are transcribed verbatim and were NOT decoded in this spike. The first column is the
chord that was injected, and the attribution is not fully consistent with the kitty keyboard protocol (see below):

| Injected chord | alacritty | kitty | foot | ghostty |
|---|---|---|---|---|
| lone Shift press (1st / 2nd) | `ESC[57441;2u` / same again | `ESC[57441;2u` / same again | `ESC[57441;2u` / second press bytes not preserved (an earlier draft had the malformed `ESC[574431u`) | no bytes captured |
| lone Ctrl press | `ESC[57442;2u` | `ESC[57442;2u` | `ESC[57442;2u` | no bytes captured |
| Tab | `ESC[9u` | `ESC[9u` | `ESC[9u` | `ESC[9u` |

Decoding against the kitty keyboard protocol: keycode 57441 is Left Shift and 57442 is Left Control. In the modifier
field, Shift is 2 and Ctrl is 5 (modifier bit value plus 1). A lone Left Shift press, `57441;2`, is consistent. A lone
Ctrl press would be `57442;5`, but `57442;2` was recorded: either Shift was still held at that moment or the row is
attributed to the wrong chord. This is unresolved and the Ctrl row must not be relied on. The second foot Shift press
is not recoverable from the notes (#91). Ghostty produced nothing for lone modifiers, possibly an injection artifact
(`wtype`, #90). Press, repeat and release distinction (kitty event types) needs flag 2 and was not exercised (#91).

Double-Shift: at the byte level a lone Shift press was seen on three of four terminals (alacritty, kitty, foot) under
flag 8, so it is reachable in principle. It remains unusable for 0.1.0 because TamboUI 0.6.0 has no parser for these
sequences (spike 27), and because ghostty produced nothing in this synthetic setup (UNVERIFIED, #90, #91).

## tmux 3.8: `extended-keys off / on / always`, escape-time 10, minimal config

For alacritty, kitty and ghostty (foot: `off` only, #87): every chord that was delivered arrived byte-identical to the
plain terminal across all extended-keys values tested, with two differences:

- Home is re-encoded: plain `ESC[H` becomes `ESC[1~` under tmux (TamboUI's `parseVT` maps `1~` to Home per spike 27, so
  behavior survives; a value-based check for both forms is recommended in #29 tests). Application cursor-key mode was
  not tested (#96).
- The trailing Ctrl+B never arrived (tmux prefix). In one run the first Ctrl+I / Ctrl+M of the following phase also
  arrived as zero bytes (2 to 3 zero-byte chords in a row). This was observed once and not reproduced, and the cause is
  unknown (hypotheses: timing or phase boundaries of the recorder, `repeat-time`); tmux normally consumes only the
  single key after a prefix. Only the Ctrl+B swallow is established (#86).

What this does and does not show about `extended-keys`: the app inside tmux never pushed CSI-u (`CSI > 1u`) or
modifyOtherKeys (`CSI > 4;2m`), so every chord sent was legacy-mode input and `extended-keys off / on / always` had
nothing to act on. "No difference observed" applies to legacy-mode input only. Passing CSI-u or modifyOtherKeys through
tmux was NOT tested, neither with the minimal config nor with the Omarchy config (which sets `extended-keys on` and
`extended-keys-format csi-u`). The spike 27 guidance (leave extended-keys at the user's default; never bind anything
that exists only in a CSI-u world) is neither confirmed nor contradicted by this capture (#83).

### Inside the Omarchy tmux config (`~/.config/tmux/tmux.conf`)

Captured live (alacritty pane inside a tmux server on a separate socket using the user config, one run): char `a` and
Tab arrived; Alt+1, Alt+2, Alt+3, Alt+Left and Alt+5 produced zero bytes; Ctrl+B produced zero bytes (it is `prefix2`
there). Reading the config file: Alt+1..9, Alt+arrows, Alt+Enter, Alt+Escape and several Ctrl+Alt chords are bound
there, so Alt+digit and Alt+arrows being consumed matches the config. No Alt+letter binding was found when reading it,
but Alt+letter was NOT captured live in this setup, so whether it reaches the inner app is UNVERIFIED (#84). The
config's primary prefix is Ctrl+Space (`prefix C-Space`); by the config it should be swallowed, but it was not captured
live (#85). Conclusion limited to the data: on this machine Alt+digit and Alt+arrows are not available to an app inside
Omarchy tmux, and Ctrl+B is consumed. Nothing is established for the Alt family as a whole.

## Ctrl+digit / Ctrl+Shift+digit probe (tool window switching candidates)

The keyboard mode is stated per cell where the capture notes know it; cells from different modes are not comparable.

| Chord | alacritty | kitty | foot | ghostty |
|---|---|---|---|---|
| Ctrl+1 | 31 (the digit itself; mode not recorded) | 31 (same) | not determined: Ctrl+1 and Ctrl+2 landed as one 00 chunk, so neither can be attributed | 31 (same) |
| Ctrl+2 | 00 | 00 | not determined (see Ctrl+1) | 00 |
| Ctrl+Shift+1 | 21 (0x21 is the printable `!`: Shift applied, the Ctrl modifier lost; mode not recorded) | no bytes in this capture | `ESC[27;6;33~` (modifyOtherKeys phase) | `CSI 33;5u` (CSI-u form; the modifier field 5 means Ctrl only, Shift is carried by the `!` keycode) |
| Alt+1 | `ESC 1` | `ESC 1` | `ESC 1` | `ESC 1` |

Conclusions limited to the data: in the cells above for Ctrl+1 and Ctrl+2, Ctrl+1 is the digit's own byte and Ctrl+2
is NUL, so Ctrl+digit is not a distinct key there. Ctrl+Shift+digit differs per terminal and per mode (a printable
`!`, nothing, an xterm `27;` form, a CSI-u form). Ctrl+digit was not probed under kitty protocol flag 1, where it
could be a distinct key, and that is the probe tool window switching would need (#88). Until that exists, the #26/#29
keymap should not build on digit chords; digits stay available as plain `CHAR` (focused-editor rule from spike 30).

## Colors

Environment as seen by the inner app (`env` dump in each run):

| Terminal | TERM | COLORTERM | Other |
|---|---|---|---|
| alacritty 0.17.0 | xterm-256color (inherited from the spawner; alacritty's own default is alacritty) | truecolor | - |
| kitty 0.49.2 | xterm-kitty (kitty forces its own value) | truecolor | - |
| ghostty 1.3.1 | xterm-ghostty (ghostty forces its own value) | truecolor | TERM_PROGRAM=ghostty, TERM_PROGRAM_VERSION=1.3.1-arch2 |
| foot 1.28.0 | xterm-256color (inherited from the spawner; foot's own default is foot) | truecolor | - |
| inside tmux (all) | tmux-256color (tmux forces its own value) | truecolor (forwarded from the client) | TERM_PROGRAM=tmux, TERM_PROGRAM_VERSION=3.8 |

Two notes: the client environment is forwarded by tmux, so `COLORTERM=truecolor` showed up inside tmux here (it is not
guaranteed to survive ssh, as spike 27 already says), and for alacritty/foot the captured TERM value came from
inheritance: parsing a TERM-style rule against anything other than forced values (tmux-256color, xterm-kitty,
xterm-ghostty) is unreliable, so COLORTERM-first detection stays the recommendation. Whether COLORTERM came from the
terminal or from the spawning environment was not determined.

Truecolor render check. A full-screen fill of `48;2;255;0;0` and of `48;2;0;128;255` was presented and the window
pixel-sampled:

- red: alacritty (255,0,0), kitty (254,4,4), ghostty (255,2,2), foot (255,2,2), and inside tmux (254,4,4): all within
  4 per channel of the requested value; no terminal visibly quantized or dithered the fill.
- mid blue (0,128,255): ghostty (4,128,253), kitty (10,127,249), foot (4,128,253), alacritty (3,102,202 in one dimmed
  sample; that is close to 0.8 times (0,128,255) = (0,102,204) only under the assumed uniform Hyprland dim).

The undimmed samples deviate by up to 10 per channel (the kitty blue sample, red channel), so the supported statement
is "within about 10 per channel", not "exact". The alacritty blue sample agrees only under the assumed dim factor.

256-color fallback. The palette's planned 256 fallback `Color.indexed(173)` was displayed and sampled. The value
expected from the standard xterm-256 palette is #d7875f (215,135,95), and that is what the samples gave. Two of the
four samples were dim-altered and were accepted as equal after dividing by the same assumed uniform factor of about
0.8, which was not itself measured, so for those two terminals the identity was not directly measured; which two
terminals those were is not recorded (#89). This is one machine and one theme. No proprietary 256 palette correction
was seen in the samples.

tmux passthrough: truecolor fills inside tmux sampled like direct fills (the red fill inside tmux is recorded as
(254,4,4) above and as (255,2,2) in the original notes, both within the same tolerance). That suggests the Omarchy
`terminal-overrides ",*:RGB"` line is doing its job on this machine; it was not confirmed with the line removed.

Consequence for the #26 palette and spike 27's three-representation rule: on the sampled terminals and theme there is
no sign that per-terminal corrections are needed beyond a deviation of about 10 per channel; the palette switch can
stay driven by COLORTERM/NO_COLOR plus a user override. Broader claims need #89.

## Mouse reporting (X10 vs SGR mouse): UNVERIFIED

No mouse events were captured (the injection path used here is keyboard-only). What is already proven in spike 27
stands: TamboUI emits `?1000h ?1002h [?1003h] ?1015h ?1006h` and its parser only decodes SGR mouse (`parseMouseSGR`),
so legacy X10 tracking bytes would yield nothing. The open per-terminal question (do these terminals report
X10/URXVT-style bytes when only `?1000h` is enabled and `?1015h`/`?1006h` are never requested) stays open (#93).

## vt100 / linux console / Windows Terminal: not run

- TERM=vt100 and the Linux virtual console (`showkey -a` on a real console) were not exercised in this capture: there
  is no serial console attached to this machine, and a TERM environment change does not convert a GUI terminal into
  console behavior. UNVERIFIED (#94).
- Windows Terminal and the TamboUI panama Windows backend remain open per spike 27 (#95).

## Keymap consequences for #29 (default keymap re-checked against this table)

Rules are limited to what the tables above support. Items marked UNVERIFIED are not recommendations.

- Byte-stable in the legacy captures on all four terminals: plain CHAR, Tab, Escape, Backspace, F5, F12, Shift+Tab,
  Home (with the tmux re-encoding noted), Ctrl+Left and Alt+digit (outside tmux). Of the Ctrl+letter chords, only
  Ctrl+I, Ctrl+M, Ctrl+H, Ctrl+[, Ctrl+Space and Ctrl+B were captured; other Ctrl+letter chords follow from spike 27's
  protocol analysis, not from this capture. Ctrl+Shift+Left was stable on three terminals; on kitty in legacy mode it
  is UNVERIFIED.
- Alt+letter: UNVERIFIED. It was captured only in the modifyOtherKeys phase on bare terminals (Alt+S) and never in the
  legacy phase or inside the Omarchy tmux config. It is not recommended as safe (#84).
- Not available on this machine inside the Omarchy tmux config, live-verified: Alt+1, Alt+2, Alt+3, Alt+Left, Alt+5
  (consumed by tmux bindings), and Ctrl+B (prefix). Ghostty's default alt+digit bindings are from spike 27 (config
  reading, not captured here).
- Expected but not captured live: Ctrl+Space inside the Omarchy tmux config is the primary prefix there (config file
  reading, #85).
- Byte-unstable or terminal specific: Ctrl+digit (the digit byte, NUL, or not determined), Ctrl+Shift+digit (see the
  probe table), Ctrl+Enter (0a recorded on alacritty and kitty in the modifyOtherKeys phase, nothing on ghostty,
  `ESC[27;5;13~` on foot, `CSI 13;5u` under CSI-u on alacritty and foot), lone modifiers under flag 8, and the whole
  CSI-u / modifyOtherKeys byte class while TamboUI 0.6.0 has no parser for it.
- Unreachable in TamboUI 0.6.0 per spike 27: Ctrl+Shift+letter (legacy), Super/Cmd, key release. Double-Shift is
  reachable at the byte level on three of four terminals under flag 8 but TamboUI 0.6.0 cannot parse it.
- Concretely for the default keymap: quit stays `Ctrl+C`; search/palette stays a Ctrl+letter chord; tool window
  switching should not rely on digit chords or on Alt chords until #84, #85 and #88 report; and the #29 user-facing
  docs should say that Ctrl+I, Ctrl+M and Ctrl+[ arrive as the same bytes as Tab, Enter and Escape (captured here), and
  that Ctrl+H is 0x08, which TamboUI treats as Backspace (spike 27).

Corrections accepted from #49 into spike 27's chord matrix: tmux re-encodes Home to `ESC[1~`, and Ctrl+B is swallowed
inside tmux. The pending-prefix swallowing of following chords is a hypothesis (#86), not a correction.

## Follow-up issues

All in milestone 0.1.0. Each UNVERIFIED, not-run or needs-recapture item above has its own issue.

- #81 Re-capture Enter, Ctrl+M, Ctrl+J and Ctrl+H with a pty that has ICRNL cleared
- #82 Commit the spike 49 key recorder and a trimmed raw log
- #83 tmux extended-keys: test with the app pushing CSI-u and modifyOtherKeys, using the Omarchy tmux config
- #84 Capture Alt+letter and the other Alt chords inside the Omarchy tmux config
- #85 Capture Ctrl+Space and Ctrl+B inside the Omarchy tmux config
- #86 Reproduce the tmux pending-prefix state swallowing following chords
- #87 Capture foot inside tmux with extended-keys on and always
- #88 Re-probe Ctrl+digit and Ctrl+Shift+digit one chord at a time, with the keyboard mode stated per cell
- #89 Re-sample terminal colors without the Hyprland dim and report tolerance
- #90 Capture ghostty Ctrl+Enter and lone modifier keys with real key input
- #91 Decode kitty keyboard protocol press and release sequences for lone modifiers
- #92 Capture Ctrl+Shift+Left in kitty in legacy mode
- #93 Check legacy X10 and URXVT mouse reporting per terminal
- #94 Capture keyboard input on TERM=vt100 and the Linux virtual console
- #95 Capture keyboard input on Windows Terminal with the TamboUI panama backend
- #96 Check Home and arrow keys when DECCKM (application cursor keys) is enabled
