# Themes and colors

The shell rendering stack, the two shipped themes, user theme files and the palette table.

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
  participate). The keymap binds a key to it, see the [keymap](keymap.md). Both
  themes render the whole shell and switch without a restart.

The chrome palette (menu bar, tab bar, pane headers, pane separators, status bar) and the
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
| `$editor-caret-row` | `#323232` | 236 | `#26282E` | 235 | editor scheme `CARET_ROW_COLOR` |

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
