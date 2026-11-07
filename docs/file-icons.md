# File icons

How file and folder names map to icons, the two icon faces and the optional user mapping file.

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
