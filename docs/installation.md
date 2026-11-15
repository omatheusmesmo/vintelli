# Installation

How the installer chooses between a release binary and a source build, every option, and the uninstall path.

Install the `vintelli` command with `scripts/install.sh` (Linux and macOS) or
`scripts/install.ps1` (Windows PowerShell). The installer checks GitHub
Releases for a pre-built native binary matching the detected operating system
and CPU architecture (verifying its SHA-256 checksum) and falls back to
cloning and building from source when no release artifact is published yet. If
a GraalVM/Mandrel `native-image` toolchain is detected, the installer builds
the native binary with `./mvnw package -Dnative`; otherwise it builds the JVM
package, copies `quarkus-app` into a persistent runtime directory outside the
source tree, and installs a `vintelli` launcher wrapper.

Unix (bash):

```bash
./scripts/install.sh                     # clone and install to ~/.local/bin/vintelli
./scripts/install.sh --source .          # build from an existing local clone
./scripts/install.sh --system            # install to /usr/local/bin/vintelli
./scripts/install.sh --prefix ~/bin      # install to a custom directory
./scripts/install.sh --dry-run           # preview health checks and actions
./scripts/install.sh --uninstall         # remove the installed binary and runtime
```

Windows (PowerShell):

```powershell
.\scripts\install.ps1                    # clone and install to %LOCALAPPDATA%\vintelli\vintelli.exe
.\scripts\install.ps1 --source .         # build from an existing local clone
.\scripts\install.ps1 --system           # install to %ProgramFiles%\vintelli\vintelli.exe
.\scripts\install.ps1 --prefix C:\bin    # install to a custom directory
.\scripts\install.ps1 --dry-run          # preview health checks and actions
.\scripts\install.ps1 --uninstall        # remove the installed binary and runtime
```

Supported flags on both scripts: `--source <dir>`, `--prefix <dir>`,
`--version <tag>`, `--system`, `--force`, `--uninstall`, `--dry-run`,
and `--help`. After installation, `vintelli --version` and `vintelli --help`
confirm the binary is ready.

## Font check

After the smoke test, both installers check whether the terminal can draw the
Nerd Font glyphs of the file icons, the tool window stripe icons and the tree
arrows (see [file icons](file-icons.md)). The check only reads and never fails
the installation: it does not change the exit code, it runs under `--dry-run`
too, and it never starts Vintelli. It prints nothing when one of these holds,
checked in this order:

1. The terminal is kitty, Ghostty or WezTerm. `TERM_PROGRAM` or `TERM` names
   one of them (`xterm-kitty` and `xterm-ghostty` included), or one of
   `KITTY_PID`, `KITTY_WINDOW_ID`, `GHOSTTY_RESOURCES_DIR` or
   `WEZTERM_EXECUTABLE` is set to a non blank value.
2. On Windows, the face `profiles.defaults.font.face` of the default profile of
   Windows Terminal is a Nerd Font: its name contains `Nerd Font`, `NerdFont`
   or `nerd-font` (case-insensitive), or has `NF`, `NFM` or `NFP` as a separate
   word (`CaskaydiaCove NF`). A face known to have little glyph support
   (`Consolas`, `Courier New`, `Lucida Console`, `Cascadia Mono` or
   `Cascadia Code`) warns even when a Nerd Font is installed.
3. A Nerd Font file is installed. The installers apply the same rules as the
   application's installed-font check (see [file icons](file-icons.md)): the
   same font directories, a walk at most four directory levels deep, the same
   name markers and short forms (`NF`, `NFM` or `NFP` right before `-` or `.`,
   compared case-sensitively), and the extensions `ttf`, `otf`, `ttc` and
   `woff2`. The font directories are:
   - Linux: `$XDG_DATA_HOME/fonts`, `~/.local/share/fonts`, `~/.fonts`, each
     absolute `$XDG_DATA_DIRS` entry plus `fonts`, `/usr/share/fonts` and
     `/usr/local/share/fonts`.
   - macOS: `~/Library/Fonts` and `/Library/Fonts`.
   - Windows: `%LOCALAPPDATA%\Microsoft\Windows\Fonts` and `%WINDIR%\Fonts`.

   A missing variable (`HOME`, `LOCALAPPDATA`, `WINDIR`) drops its directories.

Otherwise the installer prints a warning. It names what may look broken, gives
the install commands (`brew install --cask font-jetbrains-mono-nerd-font` on
macOS, `winget install DEVCOM.JetBrainsMonoNerdFont` on Windows, a copy to
`~/.local/share/fonts` followed by `fc-cache -f` on Linux, and the downloads
page at <https://www.nerdfonts.com/font-downloads>), says that the terminal font
must be switched to the Nerd Font, and names `vintelli.icons=ascii` as the safe
fallback.

The check cannot see everything:

- The font of the terminal, except the default profile face of Windows Terminal.
  An installed Nerd Font shows that the machine has one, not that the terminal
  draws with it. Other profiles, per-profile fonts and split panes are not read.
- Terminals other than kitty, Ghostty, WezTerm and Windows Terminal (for example
  Alacritty, iTerm2 or the terminal of an editor). For them only the installed
  font is a signal.
- The terminal on your own machine when you install over SSH: the remote
  machine has no view of the local terminal font.
- Whether the face named in Windows Terminal is installed. The name is trusted;
  unknown faces that are not in the list above fall through to the installed
  font rule.
- Glyph coverage. The check reads file and face names, not the font files.
- Git Bash or MSYS: `install.sh` skips the check and points to `install.ps1`,
  which has the Windows Terminal check.

On Windows, `install.ps1` reads the first existing file of these locations that
names a face: the stable package (`%LOCALAPPDATA%\Packages\Microsoft.WindowsTerminal_8wekyb3d8bbwe\LocalState\settings.json`),
the preview package (`...Microsoft.WindowsTerminalPreview_8wekyb3d8bbwe...`) and
the unpackaged install (`%LOCALAPPDATA%\Microsoft\Windows Terminal\settings.json`).
Comments and trailing commas, which Windows Terminal accepts, are allowed. A
file that cannot be read is skipped.

Set `VINTELLI_SKIP_FONT_CHECK=1` to skip the check on either installer.

The test scripts override the locations through two variables that are meant
for tests only and are not a supported configuration: `VINTELLI_FONT_DIRS` (a
path list that replaces the font directories, separated by `:` on Linux and
macOS and by `;` on Windows) and, for `install.ps1` only, `VINTELLI_WT_SETTINGS`
(one settings file that replaces the three Windows Terminal locations).
