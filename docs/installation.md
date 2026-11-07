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
