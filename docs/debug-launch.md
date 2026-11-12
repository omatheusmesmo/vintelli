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
that disables forking cannot expose a test debug port. Breakpoints, stepping and
variable inspection inside Vintelli are tracked separately in #387 through #390. The
optional Java Debug Adapter provider is a separate integration through jdtls;
see [language-server setup](language-servers.md#java-debug-adapter).

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
