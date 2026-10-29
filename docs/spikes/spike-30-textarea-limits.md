# Spike 30: TamboUI TextArea limits

Feasibility check for editor option C: building our own editor on top of the TamboUI `TextArea` widget instead of embedding nvim or writing a full custom widget. Scope covers inspection of the TamboUI 0.6.0 artifacts, a micro-benchmark of steady-state rendering and keystroke latency, and a gap analysis against the editor v1 feature list.

Inspected artifacts: `dev.tamboui:tamboui-widgets:0.6.0`, `tamboui-core/0.6.0`, `tamboui-toolkit/0.6.0`, `tamboui-tui/0.6.0` (with official 0.6.0 sources jars from Maven Central). The micro-benchmark was a scratch program, not committed to the repo, run on JDK 25 (`.sdkmanrc` pins `25.0.2.r25-mandrel`; the exact build used was not recorded), Linux, 100x30 text area, renderer-only (no real terminal), synthetic Java-like text.

## Decision

**GO for option C (own editor on `TextArea`) for 0.1.0 / #31**, with two mandatory wrappers in the editor module (not in vendored code):

1. Own `SyntaxHighlighter` implementation with per-line token caching (re-tokenize only the changed line on keystroke; cross-line constructs (block comments, text blocks) are handled by caching the end-of-line lexical state per line and re-tokenizing forward from the edited line until the end state equals the cached one; the built-in tokenizer is private and whole-text, so the custom highlighter carries its own tokenizer, LOC not yet estimated (to be sized in #31)). This is the single blocking perf fix.
2. Diagnostics as a sibling gutter column + severity text tint via the custom highlighter (no fork of the widget).

Undo/redo and search go through an adapter over the `TextAreaState` primitives as described below. nvim embedded (#32) stays a documented fallback and a dedicated future item for modal editing, not a v1 dependency.

## API inspection

- `TextAreaState` is **`final`** with a private line list; `TextArea` is also `final`. Extension is composition-only.
- Buffer model: `List<StringBuilder> lines`, one logical line per entry. `text()` rebuilds the whole document string; `computeDisplayRows()` is cached per (text version, width, overflow), so wrapping is cheap after the first pass for scrolling and idle frames (it is recomputed after every edit).
- **No selection, no undo/redo, no search, no word motions, no clipboard, no mouse/click-to-position** in either the widget or the toolkit `TextAreaElement`. The element handles only: Backspace, Delete, Left/Right, Up/Down (visual-row aware under wrap), Home/End, Enter, Tab (inserts 4 spaces), printable chars, and bracketed paste (`PasteEvent`). Ctrl/Alt chars are deliberately left unconsumed, which is a good fit for the keymap system (#29). Missing for later: Ctrl+Left/Right word motions, Shift+arrows, PageUp/PageDown, Ctrl+Home/Ctrl+End, Undo/Redo (adapter, see below).
- Unicode/wide chars/IME: strong. All cursor math goes through `GraphemeClusters` + `CharWidth`; ZWJ sequences and regional-indicator flags are handled, CJK double-width columns are respected in wrapping, scrolling and caret math. IME composed characters arrive as normal CHAR events from the backend, so IME works within terminal limitations.

### Syntax highlighting

- Exists: `dev.tamboui.widgets.syntax` (`RegexSyntaxHighlighter`, `Grammar`, `SyntaxTheme`, 13 `TokenType`s; built-ins include java, kotlin, javascript, typescript, python, json, yaml, bash, sql, go, rust, xml, css, properties).
- Java grammar quality is decent for a regex grammar: block comments, `//` comments, text blocks (`"""`), double/single strings, `@annotations`, numbers, SCREAMING_CASE constants, capitalized types, keywords including `record`/`sealed`/`permits`/`var`/`yield`, function-call detection before `(`, operators, punctuation. Generics: `List<String>` tokenizes `List` and `String` as TYPE and `<`/`>` as operators (from the grammar rules, not run), which is acceptable.
- Quality gaps: no generality (a custom annotation/`@Override` is same as any `@word`), no javadoc-specific rendering, and it is purely textual (no scope tracking).
- **Performance defect: the full document is re-tokenized on every render.** `TextArea.render` calls `highlighter.highlight(state.text(), ...)` per frame with no caching, and `text()` itself rebuilds the entire document string per frame. Also: highlighting works only in `CLIP` mode; `WRAP_WORD`/`WRAP_CHARACTER` render unstyled (documented limitation), so the v1 editor has no soft wrap with highlighting; v1 is CLIP only.
- **Escape hatch: `SyntaxHighlighter` is a public interface.** We can plug our own highlighter that (a) caches per-line tokens keyed by a line hash and re-tokenizes only changed lines, and (b) optionally maps LSP semantic tokens (#20) onto `TokenType` plus the span `Tags` extension. This removes the re-tokenization cost without forking the widget. The widget still rebuilds the full text string and the highlighter must still return one `Line` per logical line on every frame, so a small O(document) cost per frame remains (to be measured in #31). Semantic-token quality can be added later through the same hook.

## Benchmark numbers

Indicative only: a scratch program, not committed, renderer-only, on a single machine. Two runs; each cell gives the range between the two runs. Warm-up, iteration counts and the exact statistic were not recorded. Steady render means repeated render calls of an unchanged buffer; keystroke means insert + render, where the render includes the highlight.

| lines | chars | steady render + built-in highlighter | steady render plain | steady render wrapped | keystroke (insert + render, incl. highlight) | scroll 1 row (plain) | highlighter.full-doc alone |
|---|---|---|---|---|---|---|---|
| 100 | ~3k | 6.6-13.9 ms | 0.9-1.6 ms | 1.0-1.4 ms (unstyled) | 9.3-12.8 ms | 2.2-2.3 ms | 5.8-9.2 ms |
| 1 000 | ~29k | 34.9-52.4 ms | 0.7-0.9 ms | 0.5-0.7 ms | 30.7-45.7 ms | 1.2-2.0 ms | 43.4-44.2 ms |
| 10 000 | ~287k | 273.6-410.8 ms | 0.9-3.3 ms | 0.4-2.4 ms | 216-278 ms | 2.1-2.2 ms | 351-376 ms |

- `setText` full rebuild (file-open path): 0.03 / 1.9-4.7 ms. File loading is not a concern.
- Conclusion: the **only** large cost is full-document re-highlighting per frame (about 35-90 us per line depending on size). Steady rendering and scrolling at 10k lines stay under about 3 ms per frame in plain and wrapped mode; edit latency without the highlighter was not measured (wrapped mode recomputes the display rows on every edit). The criteria "10k lines not usable" would only be triggered by the built-in highlighter as-is, but that is replaced, not forked.

## Required additions

Gap analysis against #31 (editor v1) needs. v1 scope is: open/edit with line numbers + highlighting, save/dirty, basic undo/redo, simple search, diagnostics gutter, goto-definition/usages. Advanced selection, multi-cursor, completion popup, refactorings, code folding, replace across files and vim motions are out of scope in #31.

- **Undo/redo:** absent, but feasible **on top of** `TextAreaState` primitives in our adapter (coalesced edit diffs; undo restores by cursor-walking to the position from the start of the document, then `insert`/`deleteBackward` on the primitives). Edits are captured by wrapping the key and paste handling in the adapter (`TextAreaElement` is final and `onTextChange` only delivers the full text, not a diff); this wrapping is part of the 300-500 LOC estimate. Cursor positioning to an arbitrary `(row, col)` is not exposed but is reproducible via `moveCursorToStart` + repeated `moveCursorRight`/`moveCursorDown`, cheap when walking by line first (about 0.25-0.7 ms for 10k `moveCursorDown` calls, indicative: measured on a loaded machine with load average ~71) and then by column within the line. Walking the whole document by `moveCursorRight` is linear in characters (about 565k calls took about 70 ms on the same loaded machine, indicative), so the adapter must walk by line, not by character. Est. size: ~300-500 LOC in the editor adapter, no replacement of `TextAreaState`.
- **Simple search:** same technique (cursor-walk + `computeDisplayRows`/`findDisplayRowIndex` are public, so match highlighting in the viewport could ride on a custom highlighter). Est. ~150-300 LOC.
- **Diagnostics gutter:** `TextArea`'s gutter is hardcoded to line numbers only, with a fixed style, and text styling cannot be applied per visible row from outside. Workable without forking: sibling tool-window column to the left of the `TextArea` (aligned via the public `scrollRow()`/viewport math) for severity markers (e.g. `E W I`), plus painting the diagnostic line's background/foreground through our custom highlighter (tint covers the text extent only, because the widget fills the rest of the row with the base style and the highlighter does not receive the viewport width; trailing-space padding is a possible workaround to verify; no true squiggles). True in-text squiggles/underlines would need our own widget.
- **Completion popup:** renderable as an overlay above the `TextArea` (ratatui-style layering); anchor computable from public `computeDisplayRows` + cursor. Out of v1 scope, but not blocked.
- **Hover:** not evaluated in this spike. A hover popup could reuse the overlay approach of the completion popup, but a pointer-triggered hover needs mouse events, which `TextAreaElement` does not handle. Size it with #31.
- **Own widget path:** if we ever need mouse-driven selection, inline squiggles, clipboard integration, or mouse positioning, `TextArea` cannot host them: that requires **our own StatefulWidget with our own buffer**, estimated at 2.5k-4k LOC including tests (rope/line buffer, cursor/selection model, undo, search, mouse-to-position, render). That is well beyond v1.

## Key handling and the global keymap

- While the editor has focus it consumes all printable characters, so bare-key global bindings (spike 27 / PR #40 notes that some profiles bind bare `q`) must not fire while the editor has focus. The routing order between global handlers and the focused element in `ToolkitRunner` (PR #36) still needs to be confirmed in #31.
- Terminal limits from PR #40 that apply directly to the editor: legacy encodings cannot report Ctrl+Shift+letter, so IntelliJ's redo (Ctrl+Shift+Z) is unreachable and redo must be bound to Ctrl+Y (or similar). Shift is not reported for printable characters. Ctrl+I, Ctrl+M and Ctrl+H collapse to Tab, Enter and Backspace.

### Vs what nvim embedded (#32) would offer

Feature delta in nvim's favor: real vim motions, macro selection, replace, folds, marks, tree-sitter/LSP highlighting maturity, incremental screen updates (`grid_scroll`, `ext_linegrid`) for 100k+ line files, popups/cmdline externalized, decades of edge-case hardening (encodings, IME, CJK, weird terminals). Costs: a MessagePack-RPC client over stdio (no mature JDK lib; native-image risk), a grid renderer and key-translation layer inside TamboUI, neovim as a hard runtime dependency (install/version drift), a second LSP config unless diagnostics flow is redirected, keymap ownership disputes (#29: modal keys inside our window), and Pilot tests (#31-style `@QuarkusTest`s) would have to assert on a grid renderer's buffer rather than on editor state. Rough estimate, not sized in a spike: codec + grid renderer + key translation, several weeks, higher risk surface than everything above combined.

## Fallback criteria evaluation (nvim embedded, #32)

The no-go criteria from the issue, evaluated against the findings:

- **Criterion 1 (selection, undo/redo and search cannot be added without replacing `TextAreaState` with a non-trivial buffer):** not triggered for v1. Undo/redo (~300-500 LOC) and search (~150-300 LOC) fit in an adapter over the `TextAreaState` primitives. Keyboard selection (Shift+arrows) is also feasible in the adapter by keeping the anchor outside `TextAreaState` and painting the range through the custom highlighter, at an estimated cost not yet measured; #31 declares only advanced selection and multi-cursor out of scope. Mouse-driven selection, inline squiggles and clipboard integration cannot be hosted by `TextArea` and would require our own widget and buffer (2.5k-4k LOC). If those become requirements, criterion 1 is triggered and #32 must be re-evaluated.
- **Criterion 2 (10k-line file not usable):** not triggered on the evidence available, with one open item. Measured: steady render and scroll are fast (about 1-3 ms at 10k lines with no highlighter). Not yet measured: edit latency in plain and wrapped mode (the wrap cache is invalidated on every edit, so wrapped editing scales with document size), and the custom incremental highlighter, which is a projection until prototyped. The built-in highlighter, as shipped, would trigger this criterion (about 270-410 ms per frame at 10k lines), which is why it is replaced rather than used. The #31 smoke test below is what confirms this.
- **Criterion 3 (no acceptable Java path beyond regex):** fails. `SyntaxHighlighter` is a pluggable public interface with per-token `Tags`/`TokenType` extensions, so an LSP-semantic-tokens highlighter is a clean upgrade path.

Re-evaluation triggers for #32: mouse selection, inline squiggles, clipboard integration, or the #31 keystroke budget failing.

Re-verification for #31 acceptance: a Pilot test asserting the custom incremental highlighter, and a CI smoke test with a generous wall-clock budget, with the ~10 ms keystroke target checked locally (shared CI runners are noisy).

## Risks and open questions

- The incremental highlighter and the 10k-line keystroke budget are unproven until #31 (see the Criterion 2 evaluation above).
- Wrapped-mode editing cost (display rows are recomputed on every edit) and no highlighting in wrap mode (v1 is CLIP only).
- The built-in highlighter silently falls back to plain text when any single line exceeds 10,000 characters (`RegexSyntaxHighlighter.DEFAULT_MAX_LINE_LENGTH`), relevant for minified or generated files.
- No mouse positioning, selection or clipboard support in `TextArea`.

## Follow-up issues

Work not executed in this spike is tracked in #50.
