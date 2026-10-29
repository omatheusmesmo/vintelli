# Spike 22: jdtls vs IntelliJ LSP server

Part of #15; feeds #20.

**Status:** Desk research only. Neither `jdtls` nor `intellij-server` was launched, and no quality, latency or memory measurement was made. The decision rests on the vendors' own documentation and on the source of JetBrains' Neovim client, read on 2026-10-28. Items not verified are listed under "Not verified" and tracked in the follow-up issues. Some of those items were later executed or corrected in `spike-52-server-benchmark.md`; see "Updates from spike 52" below, which supersedes the matching lines here.

## Decision

`jdtls` is the default language server for Vintelli. `intellij-server` (the JetBrains IntelliJ-based LSP) is an opt-in profile that the user licenses themselves.

Reasons recorded in this spike, focused on licensing:

- `intellij-server` requires an IntelliJ IDEA Ultimate or All Products Pack subscription after a 30-day trial. No Community or free tier is offered.
- The 1.0 announcement says the license is designed for human use and that licensing for purely agentic use will be updated. Vintelli is a human-driven TUI that also lets agents call the server, so whether it falls under "purely agentic use" is unclear until JetBrains publishes the terms.
- The bundle has its own EULA, accepted on first start. Whether a third-party client may bundle or auto-download it is not verified.
- Kotlin/kotlin-lsp targets only Kotlin, so it does not cover Java.
- `jdtls` does not support Kotlin, so mixed Java and Kotlin needs `intellij-server`. Kotlin is not an MVP requirement per epic #15.

**Auto-download:** go for `jdtls` (EPL-2.0, public archives with published SHA-256 files, downloaded at runtime so Vintelli redistributes nothing). No-go for `intellij-server` until the bundle `EULA.txt` and JetBrains' terms for agentic use are read and settled. JetBrains' own Neovim client downloads the bundle on first start and requires the user to accept the EULA (or set `accept_eula = true` in headless mode); mirroring that is the safe pattern, but it is not permission for a third party to do the same.

**Run/debug and git:** not standard LSP. The JetBrains blog describes debugging through the Debug Adapter Protocol, and the Neovim client exposes run, debug (nvim-dap), `:IntellijGit*` and "intellij/ protocol extensions" as separate options. They are therefore not provided by a plain LSP client and are not a differentiator for Vintelli, which plans run and git through its own providers (#15 capability table).

## Verified facts

Nothing in this spike was executed: neither `jdtls` nor `intellij-server` was launched, and no benchmark was run. Every fact below was read on 2026-10-28 from the sources named next to it: the JetBrains 1.0 announcement, the Visual Studio Marketplace listing for `JetBrains.intellij-server`, the `JetBrains/intellij-lsp.nvim` README and `launch.lua`, the `Kotlin/kotlin-lsp` README, and the `eclipse-jdtls/eclipse.jdt.ls` README.

- The extension and server require an IntelliJ IDEA Ultimate or All Products Pack subscription after a free 30-day trial. Each server build starts its own 30-day trial. No Community or free tier is offered. Sources: the 1.0 announcement ("Using it now requires an IntelliJ IDEA Ultimate subscription ... after a 30-day trial period", https://blog.jetbrains.com/idea/2026/10/intellij-idea-s-java-and-kotlin-for-cursor-and-vs-code-hits-1-0/), the Marketplace listing (adds All Products Pack, https://marketplace.visualstudio.com/items?itemName=JetBrains.intellij-server), and the intellij-lsp.nvim README ("Every server build starts with a free 30-day trial; after that it needs an IntelliJ IDEA Ultimate subscription", https://github.com/JetBrains/intellij-lsp.nvim). Per the same README, without a license "the server then starts but answers nothing"; this was not tested.
- The 1.0 announcement states the license is designed for human use, with an update on purely agentic use to follow.
  Source: https://blog.jetbrains.com/idea/2026/10/intellij-idea-s-java-and-kotlin-for-cursor-and-vs-code-hits-1-0/
- The `intellij-server` bundle is a separate JetBrains product with its own EULA, accepted on first start. No statement was found on whether a third-party client may bundle or auto-download it.
  Source: https://github.com/JetBrains/intellij-lsp.nvim
- Kotlin/kotlin-lsp targets only Kotlin (it is not a Java option). Its README marks it Alpha and "partially closed-source", and the August 2026 JetBrains post (https://blog.jetbrains.com/idea/2026/08/intellij-idea-goes-lsp/) calls it free to use with Apache 2.0 source, which the README does not confirm.
  Sources: https://github.com/Kotlin/kotlin-lsp and the August post.
- `jdtls` is licensed EPL-2.0. It needs a Java 21 (minimum) runtime on the user's machine, supports compiling projects from Java 1.8 through 25, and is distributed as milestone and snapshot archives at https://download.eclipse.org/jdtls/milestones/ and /snapshots/ (the 1.63.0 milestone, published 2026-10-19, is a tar.gz of about 51 MB with a `.sha256` file next to it). It supports stdio, sockets and named pipes.
  Sources: the eclipse.jdt.ls README (https://github.com/eclipse-jdtls/eclipse.jdt.ls) and the GitHub license API (`EPL-2.0`).
  Consequence for #20 (inference, not stated upstream): Vintelli's native binary does not provide a JVM, so the jdtls profile needs a JDK 21+ discovered at runtime, and the "missing binary shows a clear status message" criterion must also cover a missing or too-old Java.

## Risks and open questions

- Agentic/programmatic use of `intellij-server` is not yet licensed: the current license is designed for human use, and JetBrains has announced (but not yet published) an update covering purely agentic use. Until then, using it from Vintelli's agent-facing profiles sits in an unclarified licensing zone. Tracked in #54.
- Redistribution/bundling of the JetBrains bundle by a third-party client is unverified. The bundle has its own EULA; nothing found confirms or denies that Vintelli may bundle or auto-download it on the user's behalf. Tracked in #53.
- Memory and quality were not measured. JetBrains documents "about 2 GB of free memory for the server", more while a Maven or Gradle import runs (intellij-lsp.nvim README). `jdtls` publishes no figure; its README launch example uses `-Xmx1G`, which is a heap cap, not a measurement. Diagnostics and call hierarchy quality and latency of both servers are unknown. Tracked in #52.
- Transport: `jdtls` supports stdio (default when no socket variables are set), sockets and named pipes (eclipse.jdt.ls README). `intellij-server` is started with `--stdio --system-path <cache dir>` and an optional `--eula <hash>` by the JetBrains Neovim client (`lua/intellij-lsp/launch.lua`); no socket mode is documented. Both are stdio-capable, so #19 (stdio only) is sufficient. Not verified: whether `intellij-server` offers a socket mode, and none of the launch commands were run. Tracked in #52.
- The exact EULA wording on third-party clients has not been reviewed yet. Tracked in #53.

## Not verified

- Diagnostics and call hierarchy quality and speed, startup time and memory of both servers on the same Maven project (JetBrains' 2 GB figure is a documented number, not a measurement). Tracked in #52.
- Whether `intellij-server` has a socket mode; every launch command above was read, not run. Tracked in #52.
- Whether the license is enforced exactly as documented ("starts but answers nothing" was read from the README, not observed). Tracked in #52.
- Whether the bundle `EULA.txt` permits a third-party client to bundle or auto-download the bundle. The EULA text itself was not read; only the statement that the user must accept it on first start. Tracked in #53.
- Content of JetBrains' forthcoming licensing terms for purely agentic use. Tracked in #54.

## Updates from spike 52

The text above is kept as the desk-research record. `spike-52-server-benchmark.md` established the following, and where it differs it supersedes this document:

- **Java runtime:** jdt.ls 1.63.0 does not start on Java 21 (the OSGi layer requires `JavaSE` version 25); Temurin 25.0.3 and OpenJDK 27 started it. The "Java 21 (minimum)" line and the JDK 21+ consequence for #20 are stale for this milestone; #100 tracks the Java 25 requirement. Java 22 to 24 were not tested.
- **Socket mode:** `intellij-server` has one. The launcher `--help` documents `--socket` (default `127.0.0.1:9999`), `--port-file`, `--idle-timeout` and `--multi-client`, and a plain LSP client was served over TCP. "No socket mode is documented" no longer holds. Multi-client behavior and port-file removal are open (#98, #99).
- **Unlicensed behavior:** observed instead of read. The server answers `initialize` and runs the import, and refuses `textDocument/references`, `textDocument/hover` and `textDocument/prepareCallHierarchy` with `-32802`; other methods were not probed (#104). "Starts but answers nothing" was therefore not accurate for those methods. The fresh server started unlicensed instead of in a 30-day trial; the cause is unknown (#102).
- **Measurements:** jdtls startup, diagnostics, references, hover, call hierarchy and memory on a small Maven project, with a few runs only; intellij-server quality is still not measured (#73). Repeats and real repositories are tracked in #101 and #97.

## Follow-up issues

Work not executed or not verified in this spike is tracked in:

- #52: benchmark `jdtls` and `intellij-server` on the same Maven project, and probe the `intellij-server` socket mode.
- #53: read the `intellij-server` EULA and settle third-party bundling and auto-download.
- #54: reassess the `intellij-server` profile when JetBrains publishes licensing for agentic use.

jdtls provisioning (JDK 21+ detection and archive download with SHA-256 check) belongs to issue #20.
