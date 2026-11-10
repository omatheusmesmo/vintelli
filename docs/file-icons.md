# File icons

How file and folder names map to icons, the two icon faces and the optional user mapping file.

The `icons` package of `vintelli-tui` maps a file or folder name to an icon.
The mapping is data: the built-in resource
`dev/omatheusmesmo/vintelli/tui/icons/file-icons.properties` lists, per exact
file or folder name (`name.pom.xml`, `name..git`) and per extension
(`ext.java`), a Nerd Font glyph, a pure ASCII marker and an optional color.
The color is either a fixed `#RRGGBB` value or an ANSI name such as `red`. The
built-in mapping uses fixed hex values close to the IntelliJ file type icons,
so the icons look the same in every terminal theme; an ANSI name is painted in
the palette of the terminal instead. Lookup order for a file: exact name, then the extension (case
insensitive, after the last dot), then the default file icon. Folders use the
exact name, then the default folder icon. Only the last path segment is
matched, so whole paths can be passed.

Two faces render the same mapping, chosen by the `vintelli.icons` system
property:

| Value | Face |
|---|---|
| `auto` (default) | Nerd Font glyphs when the terminal is kitty, Ghostty or WezTerm, or when a Nerd Font file is installed on the machine (see below); ASCII markers otherwise |
| `nerd` | Nerd Font glyphs, for a terminal with a patched font |
| `ascii` | ASCII markers, colored, for exact terminal compatibility |

The font of the terminal is not observable from the process, so `auto` applies
these rules in order and takes the first that matches:

1. The terminal is kitty, Ghostty or WezTerm, by name or by its environment marker: Nerd.
2. No terminal is attached to the process (no console, or stdin or stdout is redirected): ASCII.
3. The session is remote (`SSH_CONNECTION`, `SSH_CLIENT` or `SSH_TTY` is set and not blank): ASCII.
4. `TERM` is `dumb` or `linux`, ignoring case: ASCII.
5. A Nerd Font file is installed on the machine: Nerd.
6. Otherwise: ASCII.

The installed-font check is a bounded walk, at most four directory levels deep,
of the font directories of the platform: on Linux `$XDG_DATA_HOME/fonts`,
`~/.local/share/fonts`, `~/.fonts`, each absolute `$XDG_DATA_DIRS` entry plus
`fonts`, `/usr/share/fonts` and `/usr/local/share/fonts`; on macOS
`~/Library/Fonts` and `/Library/Fonts`; on Windows `%LOCALAPPDATA%\Microsoft\Windows\Fonts`
and `%WINDIR%\Fonts`. A file matches when its name, case-insensitively, contains
`nerdfont`, `nerd font` or `nerd-font`, or when it carries a Nerd Font short
form (`NF`, `NFM` or `NFP` right before `-` or `.`, compared case-sensitively,
as in `JetBrainsMonoNF-Regular.ttf`), and its extension is `ttf`, `otf`, `ttc`
or `woff2`. A missing or unreadable directory counts as empty. No process is
started and no AWT is loaded.

The installed font is a proxy and has a limit: it shows that the machine has a
Nerd Font, not that the terminal draws with it. A terminal without font fallback
may still show boxes or blanks for the glyphs. In that case set
`vintelli.icons=ascii`. The explicit values `nerd` and `ascii` always win and
skip every rule above.

On a terminal that is not kitty, Ghostty or WezTerm, a glyph from the private use area
shows as a tofu box, a blank or an overlapping character, depending on the font.
Set `vintelli.icons=nerd` there only when the font is patched. Each entry
defines both symbols with the same cell width, so switching the face does not
shift the columns of a tree. The glyph width was not verified on any terminal:
a consumer must reserve `FileIcon.symbolWidth(mode)` cells plus one separator
cell after the icon.

## Java source kinds

A `.java` file shows the icon of the kind of its first top-level type: class,
interface, enum, record or annotation (`@interface`). An abstract or sealed
class is a class; the modifiers are not icons of their own. The built-in
entries are the `type.class.*`, `type.interface.*`, `type.enum.*`,
`type.record.*` and `type.annotation.*` keys of the mapping resource.

| Kind | Nerd glyph (codicon) | Code point | ASCII marker | Colour |
|---|---|---|---|---|
| class | `nf-cod-symbol_class` | U+EB5B | `C` | `#4A9FE0` |
| interface | `nf-cod-symbol_interface` | U+EB61 | `I` | `#6CBF6C` |
| enum | `nf-cod-symbol_enum` | U+EA95 | `E` | `#E8873A` |
| record | `nf-cod-symbol_structure` | U+EA91 | `R` | `#3CB8A8` |
| annotation | `nf-cod-mention` | U+EB1F | `@` | `#D6B24A` |

The glyphs are Nerd Fonts codicons in the Basic Multilingual Plane private use
area, as the other built-in glyphs are, so every kind takes one cell like the
ASCII marker. The Material Design icons were not used: they live outside the
BMP, where a Java string holds them as a surrogate pair and the cell width
depends on the terminal. The code points were checked against the glyph names
of a Nerd Font v3 file (JetBrainsMono Nerd Font), and `nf-dev-java` (U+E738) was
checked the same way to confirm the lookup.

The kind is read from the file itself, not from the name: the scan skips the
package, the imports, comments, string and character literals, annotations with
their arguments and the modifiers, and the first declaration keyword decides.
The scan reads at most the first 8192 bytes of a file, so a declaration after
a very long header is not found. A file with no type (or text that is not a
declaration, such as a field before the type) and an unreadable file keep the
extension icon.

The tree never reads a file while it draws. A row whose kind is not known yet
draws the extension icon and asks for a scan on a background thread; when the
answer arrives, the tree redraws on the next frame. Each file is read at most
once per modification time, and a tree refresh (for example `F5` or a file
operation) checks the modification time again and reads the file only when it
changed. A user `ext.java` entry in the user mapping file replaces the kind
icons for every Java file, so a user who wants the extension icon keeps it by
choosing it there.

The kind icons are chosen by the tree only. The editor tabs and the search
overlay still show the extension icon.

A user file `vintelli/file-icons.properties` inside the config directory
(the shared `vintelli.config.dir` override first, then `XDG_CONFIG_HOME`,
then `APPDATA`, then `$HOME/.config`) is layered on top
with the same keys and is optional when missing. The `vintelli.icons.file`
property names an explicit user file instead and must exist. Each key
overrides exactly the field it names, so `ext.java.ascii=Jv` changes only the
ASCII marker of Java files; keys are case-insensitive. A color is written as a
hex value, for example `ext.zig.color=#E8A56B` (six digits after the `#`, in
any case), or as an ANSI name such as `ext.zig.color=magenta`. An invalid key,
a duplicate key, a color that is neither (for example `#FFF` or `#GG0000`) or an
unknown `vintelli.icons` value fails fast and names the source.

Project tree disclosure arrows are chosen separately from icon fonts. The tree
draws no connector lines. With `vintelli.icons=nerd` the folders use the thin
chevrons of the Nerd Font, otherwise Vintelli uses the `▸` and `▾` arrows when the
active locale is UTF-8 (or unset) and the output charset can encode them.
`TERM=dumb`, an explicitly non-UTF-8 locale, or an output charset that cannot
encode the arrows selects the `+` and `-` ASCII fallback.
