# Language servers

The generic LSP client, its profiles, the jdtls integration test and the version control debug endpoints.

`vintelli-lsp` hosts the generic LSP client: it launches a language server from
a profile through the LSP4J launcher (executor configured with daemon threads
so the process never lingers after a session), runs `initialize`/`shutdown`,
serves `didOpen`/`didChange`/`didClose`, the pushed diagnostics of
`textDocument/publishDiagnostics`, hover, definition, references, completion
and the `callHierarchy/*` requests. The diagnostics and call hierarchy ports of
`vintelli-api` are implemented by providers whose identifier is the active
profile, and the `vintelli-core` extension registers the LSP4J types and the
four dynamic proxies for the native image from the Jandex index at build time,
so an LSP4J upgrade needs no hand edited reflection config.

Profiles are configuration only. The `jdtls` profile of issue #20 is the first
tenant:

```properties
vintelli.lsp.profiles.jdtls.command=jdtls
vintelli.lsp.profiles.jdtls.args=-data,/home/me/workspace-vintelli
vintelli.lsp.profiles.jdtls.workspace-root=/home/me/projects/app
vintelli.lsp.profiles.jdtls.initialization-options={"bundles":["..."]}
vintelli.lsp.profiles.jdtls.java.min-version=25
vintelli.lsp.profiles.jdtls.java.home=/opt/jdk-25
```

`java.min-version` makes the client check the Java runtime of the profile
before it starts the server: jdt.ls 1.63.0 exits with code 13 on Java 21 to 24,
while its own launcher only rejects Java 20 and older (issue #100). The runtime
is `java.home` when set, otherwise `JAVA_HOME`, otherwise `java` on `PATH` (the
order the jdt.ls launcher uses), and its version is read from the `release`
file at the root of that installation, so no process starts for the check. A
runtime that is too old, or whose version cannot be read, makes the profile
unavailable with the reason, for example
`the profile 'jdtls' needs a Java 25 or newer runtime, found Java 21.0.5 at /usr/lib/jvm/java-21 (from JAVA_HOME)`.
`java.home` is also handed to the server process as `JAVA_HOME`, so the
launcher runs on the installation that was checked. Without
`java.min-version` the runtime is not checked.

`vintelli.lsp.profile` picks the serving profile when several are configured.
A missing binary or a server still indexing never breaks a call: the selection
and the status row carry an explicit unavailable reason instead.
`/lsp/diagnostics?file=...`, `/lsp/callers`, `/lsp/callees`, `/lsp/hover`,
`/lsp/definition`, `/lsp/references`, `/lsp/completion`, `/lsp/symbols` and
`/lsp/code-actions?file=...&line=...&column=...` (optionally `endLine` and
`endColumn`) expose the session
over HTTP for the native ITs, which run against the fake language server of
`vintelli-lsp`; they answer 404 unless
`vintelli.lsp.debug-endpoint.enabled=true`.

## Real jdtls integration test

`JdtlsFixtureTest` in `vintelli-lsp` runs the `LspClient` against the real jdt.ls
on the Maven fixture project in `vintelli-lsp/src/test/resources/jdtls-fixture`
(one clean class and one class with a type mismatch). It checks the initialize
handshake, the error diagnostic with its range, a hover on a method and a
shutdown that leaves no server process behind. The test is skipped unless
`VINTELLI_JDTLS_HOME` points to an extracted jdt.ls archive.

Run it locally with a Java 25 runtime (jdt.ls 1.63.0 does not start on Java 21
to 24, see issue #100):

```shell
VINTELLI_JDTLS_HOME=/opt/jdt-language-server ./mvnw -pl vintelli-lsp -am verify
```

The `LSP jdtls` workflow (`.github/workflows/lsp-jdtls.yml`) runs the test on
pull requests that touch `vintelli-lsp` or `vintelli-core`, and on demand. It is
not a required check. It fails early unless the runner has Java 25, downloads
the pinned jdt.ls 1.63.0 milestone archive from `download.eclipse.org`, checks
its SHA-256 against the value pinned in the workflow, caches the archive by
version and extracts it into the runner temp directory. To bump jdt.ls, change
the version, build, archive name, URL and SHA-256 env values together; take the
checksum from the `.sha256` file published next to the archive.

`/vcs/status` and `/vcs/diff?file=...` expose the Git provider through its
`VersionControlCapability` port so the native ITs drive JGit inside the image
over a real temporary repository the test prepares through JGit before
boot. They answer 404 unless `vintelli.vcs.debug-endpoint.enabled=true`; a
selection without a healthy provider answers 503 and a failed capability
result (not a repository, a path outside the project root) answers 409 with
the provider's reason. Confirmed `PUT /vcs/stage`, `PUT /vcs/unstage`,
`POST /vcs/commit` and `PUT /vcs/branch` operations use the same opt-in debug
setting and require `confirm=true`; branch switching accepts existing local
branches only and lets JGit refuse any checkout that would overwrite work.
The project root may sit below the repository root:
the provider confines every requested diff path and every status entry to the
project root, so no answer carries state from outside the project, and a
status longer than its bound says so with `truncated`. JGit needs no `git`
executable for status and diff; it may spawn one only to discover system wide
configuration when such a file exists.

The TUI VCS refresh marks changed files in the Project tree and editor tabs;
the active file's gutter marks lines changed against `HEAD`, and the VCS menu
can open that file's unified diff or the full working tree diff. Its write
actions stage or unstage the active file, commit staged changes, and switch
local branches after an on-screen confirmation.
