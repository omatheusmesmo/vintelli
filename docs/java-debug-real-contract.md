# Real Java Debug contract

`JavaDebugRealContract` is an opt-in integration contract for the complete
`vintelli` LSP → jdt.ls → Java Debug adapter → JDWP attach path. It is not part
of the default test patterns and never falls back to the DAP twin. The
contract starts an external JVM with JDWP on loopback, verifies that the real
adapter reports its `main` thread, detaches, and checks that the external JVM
is still alive.

The selected test resource requires both environment variables. It starts the
real `${VINTELLI_JDTLS_HOME}/bin/jdtls`, sends the selected Java Debug bundle
through jdt.ls initialization options, and creates an isolated temporary
workspace and jdt.ls data directory. Missing or invalid values fail the
selected contract instead of silently selecting a fake server.

## Prerequisites

- A Java runtime supported by the installed jdt.ls and Java Debug bundle. The
  profile currently requires Java 25 or newer.
- jdt.ls with `bin/jdtls` and its platform configuration available.
- A Java Debug bundle JAR compatible with that jdt.ls installation.
- Loopback access for the JDWP debuggee and the application's HTTP probe.

Set the paths for the local installations, for example:

```sh
export VINTELLI_JDTLS_HOME="$HOME/.local/share/nvim/mason/packages/jdtls"
export VINTELLI_JAVA_DEBUG_BUNDLE="$HOME/.local/share/nvim/mason/packages/java-debug-adapter/extension/server/com.microsoft.java.debug.plugin-0.53.2.jar"
```

Run only this contract through Failsafe:

```sh
./mvnw -pl vintelli-app -am verify \
  -Dnative -Dquarkus.native.container-build -Dit.test=JavaDebugRealContract
```

The class deliberately does not use an `*IT` suffix, so normal `*IT` discovery
does not select it. The explicit `it.test` selector is required. The contract
is not a default CI test and is not an assertion that every jdt.ls or Java
Debug version is compatible.

## Current verification status

Verified on 2026-11-11 on the Linux native runner with jdt.ls 1.58.0 and Java
Debug 0.53.2: the real-service attach contract passed, and detaching left the
external debuggee alive. This result covers those installed versions; it does
not establish compatibility with other jdt.ls or Java Debug releases.
