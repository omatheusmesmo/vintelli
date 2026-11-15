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

## Validation still pending

This implementation is under review in the draft for #295. The latest scheduler
changes still need a native idle-CPU comparison with main and fresh packaged
startup, completion-effects and terminal-resize validation before release.
