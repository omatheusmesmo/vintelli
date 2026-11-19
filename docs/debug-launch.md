# Debug launch

Choose a run configuration from the toolbar or **Choose Run Configuration**, then
click Debug or use **Debug Selected Configuration** in the action palette
(`run.debug`). The Run window shows the loopback address and console output.
The status bar shows **Debug** until the run completes or is stopped.

Supported local configurations:

| Configuration | Debug switch |
| --- | --- |
| `maven:quarkus:dev` | `-Ddebug=<port> -DdebugHost=127.0.0.1 -Dsuspend=y` |
| `maven:spring-boot:run` | `-Dspring-boot.run.jvmArguments=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:<port>` |
| `maven:test` | `-Dmaven.surefire.debug=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:<port>` |

`vintelli.debug.port` defaults to `5005`. A busy or invalid port fails before
launching the build. Port availability can change between this check and the
child JVM binding; failures from the JVM remain visible in the console.

Attach an external debugger with `jdb -attach 127.0.0.1:5005` or an IDE's remote
JVM debugger. The debuggee waits for the debugger before running application
code. Stop terminates the build and its child process tree, releasing the port.
The existing local process timeout applies to debug launches too.

The Debug button is dimmed for unsupported configurations. Invoking it displays
the reason in Run. Gradle, arbitrary exec goals and providers without an explicit
debug launch contract are unavailable. Surefire must fork a test JVM; a project
that disables forking cannot expose a test debug port. The optional Java Debug
Adapter provider is a separate integration through jdtls; see
[language-server setup](language-servers.md#java-debug-adapter).

The toolbar starts the run provider with the configured loopback debug port and
attaches the selected Debug provider to that process. Stopping the run detaches
the DAP session before cancelling the run handle; the run provider remains the
owner of process cleanup. Before starting, the toolbar sends each complete source
breakpoint set exposed by the selected editor to the Debug provider. This also
loads persisted editor breakpoints into a newly attached session.

## Stepping and the current frame

While a debug session is active, use the Run window controls, their mouse targets,
or the action palette: **Resume** (`C`), **Pause** (`P`), **Step Over** (`O`),
**Step Into** (`I`) and **Step Out** (`U`). Actions that do not apply to the
current session state or are unsupported by the adapter show a reason in Run.
Repeated actions are serialized while a DAP request is in flight.

When the adapter reports a stopped thread, Vintelli reads its top stack frame,
opens the frame's source at that line, and marks the execution line in the gutter.
The marker is transient and distinct from a breakpoint in both ASCII and Unicode
icon modes. It clears when execution resumes, the session disconnects, the
process exits, or attach fails. The marker never edits source text.

## Debug tool window

Open **Debug** from the tool-window stripe (shortcut `3`) while a session is
active. Press `r` to refresh inspection data, use `j`/`k` or the arrow keys to
move through threads and frames, and press Enter to select a frame or expand a
scope/variable. Selecting a frame navigates the existing editor to its source
location. Inspection is requested on demand and runs off the render thread;
late results from a prior session or frame are discarded. The window reports
unavailable, running, loading, empty and failed states separately.

## Evaluate an expression

With a stopped frame selected in the Debug tool window, press `e`, type a Java
expression and press Enter to review it. Evaluation runs code in the debuggee,
so the window then asks for a second key: `y` runs the expression in the selected
frame, and any other key cancels. Nothing runs on Enter alone. The result comes
from the debug adapter's evaluate response, never from the displayed variable
text. Values longer than 1000 characters are shortened and marked as truncated.
The adapter has 5 seconds to answer. Adapter errors, unsupported evaluation and
timeouts appear as a failed line in the window. If the debuggee resumes before the
answer arrives, the result is reported as discarded instead of being shown as
current. Side effects of a confirmed expression stay in the debuggee.

## Set the value of a variable

With a variable row selected in the Debug tool window, press `F2` (Set Value in
IntelliJ), edit the value and press Enter. The input opens prefilled with the
value the row shows, Escape cancels, and an empty value cancels. The value is
one line of Java source of at most 1000 characters (`42`, `"abc"`) and is sent
to the adapter's `setVariable` request, so it works for locals, fields of `this`
and the fields of an expanded object. Nothing asks for a second confirmation:
as in IntelliJ, Enter assigns. After a successful assignment the expanded scopes
and variables are read again, so values that depend on the new one update. An
adapter error, for example a type mismatch, appears in the window status and the
old value stays. `F2` is Next Problem in the editor and works as Set Value only
while the Debug window holds the keyboard; **Set Value of Selected Variable** in
the palette starts the same input. The request carries the stop it was typed
for, so a resumed or advanced session never applies it. An adapter that does not
advertise `supportsSetVariable` reports that setting a value is not supported.

## Conditions, logpoints and hit counts

Put the cursor on a breakpoint line and run **Edit Breakpoint Condition**
(`Alt+F8`). The small breakpoint editor has four fields: **Condition**, **Log
message**, **Hit count** and **Suspend**. Up, Down and Tab move between them,
Space flips **Suspend**, Enter saves and Escape cancels. An empty text field
clears that setting. All three texts are one line, stored with the breakpoint in
the same per-project file as its line, and they move with the line when the text
above it changes.

- **Condition** is a Java condition of at most 500 characters that must hold to
  stop.
- **Log message** (at most 500 characters) turns the breakpoint into a logpoint:
  it prints the message on every hit, evaluating each `{expression}` (for
  example `count={count}`), and the program never suspends. The output goes to
  the Debug console prefixed with the `file:line` of the logpoint. A debug
  adapter logs or suspends but never both, so a log message needs **Suspend**
  off, and **Suspend** off needs a log message; the editor stays open with the
  reason otherwise. A condition and a hit count still apply to a logpoint.
- **Hit count** is a positive number such as `3`, which triggers on the third
  hit, optionally after `>=`, `>`, `==`, `<=`, `<` or `%` (`% 2` is every
  second hit). The adapter evaluates it.

The gutter draws a logpoint as `◆` (`L` in ASCII icon mode) in the
`editor-breakpoint-logpoint` role, and a breakpoint with a condition or a hit
count as its usual marker followed by `?` (condition) or `#` (hit count only).
A rejected breakpoint keeps the rejected marker.

The toolbar and each edit send the complete source breakpoint set, including
these fields, to the Debug provider. The adapter reports a malformed condition,
log message expression or hit count as a rejected breakpoint with its message.
An adapter that does not advertise conditional breakpoints, logpoints
(`supportsLogPoints`) or hit counts (`supportsHitConditionalBreakpoints`)
rejects only the lines that use them with a message saying so, and it never
receives them as plain breakpoints that would suspend.

## Source breakpoints

Click a line's gutter marker or run **Toggle Line Breakpoint** (`Ctrl+F8`) to
toggle a breakpoint. Locations are stored per canonical project and source file
as zero based line anchors. An anchor follows its text: when an edit inserts or
removes lines above it, the anchor moves to the same line of text. This covers
Enter, paste, cut, duplicate line, replacing a selection, and Backspace or Delete
across a line break. An anchor on a line that an edit removes is removed. When an
edit splits a line, the anchor stays on the first part. When two lines are joined,
the first line keeps its anchor and the anchor of the joined second line is
removed. Undo and redo restore the anchors recorded with each text revision, so
undoing an edit brings back the anchors that edit removed. A breakpoint toggled
after an edit is not part of that revision, so undoing the edit drops it. Renames
do not migrate anchors: the saved
location remains associated with the old relative path, and the renamed file
starts without those breakpoints. Deleted-file locations and empty source sets
remain stored as tombstones. They are included in the toolbar's complete
breakpoint snapshot so a provider can clear stale adapter state; locations for
a deleted source are not rendered until that path is opened again. If the
adapter rejects a missing source, its rejection is retained with the anchor.

When no debug provider is available, stored locations remain pending. The Java
DAP provider sends a complete replacement set for each source; adapter replies
mark each location pending, verified, or rejected. Rejected locations remain
visible so the user can identify and remove them. The gutter keeps the requested
source and line as its persisted anchor; an adapter's resolved source and line
are retained separately in the capability response. Across live sessions, a
rejection takes precedence, otherwise any pending reply keeps the aggregate
pending, and all verified replies make it verified.

The existing inspect gate also enables `/tui/debug-launch-probe`: POST accepts
only the fixed `kind=test|quarkus|spring` scenarios, GET reads bounded output and
completion, and DELETE cancels. It starts the project's corresponding Maven goal;
use it only in an intentional inspect session. It accepts no command, path or
extra argument input and returns 404 when inspect is disabled. Native tests use
wrapper twins and real suspended JVMs, attach `jdb`, cancel, and bind the released
port again. They do not claim a contract test against installed Maven plugins.

References: [Quarkus development mode](https://quarkus.io/guides/maven-tooling#dev-mode),
[Spring Boot debugging](https://docs.spring.io/spring-boot/maven-plugin/run.html),
[Surefire debugging](https://maven.apache.org/surefire/maven-surefire-plugin/examples/debugging.html).
