# Run execution states and motion

The Run header, toolbar, tab and stripe distinguish idle, starting, running,
stopping and completed runs. Active runs show elapsed time and an output pulse
next to the Console title. Console text stays still. The configuration list
folds away during an active run to give its output more room.

Use Up/Down or `j`/`k` to select a configuration, Enter or the Run toolbar action
to start it, and Stop or the `Stop Current Run` palette action to request
cancellation. The header keeps the debugger controls during a debug session.
The toolbar's Run slot shows the current execution state while a configuration
runs; clicking the configuration still opens the shared chooser.

Set `vintelli.motion=reduced` to keep run indicators still while retaining
elapsed time. `full` enables motion. Reduced motion is automatic for `TERM=dumb`,
`NO_COLOR`, and terminals without color. ASCII mode uses plain state glyphs and
an ASCII output pulse. Completion effects run once and then stop.

## Validation

The JVM tests on Linux, Windows and macOS and the native image job cover the
startup and completion-effect recordings in `VintelliTuiStartupIT`. Unit tests
check that `ShellTicker` schedules no frame while nothing runs, that an active
run ticks between 80 ms and 1 s, and that reduced motion ticks once per second.

Still pending: a native idle-CPU comparison with main and a packaged
terminal-resize check. #295 stays open until both are done.
