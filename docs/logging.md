# Logging

What each run mode logs, where the log file lives and how to turn on debug logging.

The Quarkus banner is off in every mode, and the logging setup depends on what
the process serves. `VintelliLauncher` resolves the mode and `--debug` before
Quarkus boots and sets the `quarkus.log.*` properties for it:

| Mode | Console | Log file |
|------|---------|----------|
| `--tui` | off | `WARN` and above |
| one shot command and `--repl` | `ERROR` and above, on stderr | off |
| no argument (headless server) | `INFO` on stdout | off |

So a TUI never has log lines drawn over it, and `vintelli inspect | jq` receives
only the command output. A property you set yourself (a `-Dquarkus.log.*`
system property or a `QUARKUS_LOG_*` environment variable) always wins over the
mode defaults.

When something goes wrong, start with `--debug` as a leading flag
(`vintelli --debug --tui`, `vintelli --debug inspect`) or set
`VINTELLI_DEBUG=1`. It raises `dev.omatheusmesmo` to `DEBUG`, turns the log file
on and prints its path on stderr at start. A `--debug` after a command word is
passed to the command untouched.

The log file is `vintelli.log` in the user state directory:

- Linux: `$XDG_STATE_HOME/vintelli/vintelli.log`, by default
  `~/.local/state/vintelli/vintelli.log`
- macOS: `~/Library/Logs/vintelli/vintelli.log`
- Windows: `%LOCALAPPDATA%\vintelli\logs\vintelli.log`

If the directory cannot be created, Vintelli starts without file logging. When
the TUI cannot start, the failure message names the log file.
