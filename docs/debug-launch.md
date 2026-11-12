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

## Source breakpoints

Click a line's gutter marker or run **Toggle Line Breakpoint** (`Ctrl+F8`) to
toggle a breakpoint. Locations are stored per canonical project and source file
as zero based line anchors. Text edits and undo keep an anchor on its current
line; if an edit shortens the file past an anchor, that breakpoint is removed
permanently, including after undo. Renames do not migrate anchors: the saved
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
