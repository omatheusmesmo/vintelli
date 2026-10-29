# Spike 52: jdtls and intellij-server benchmark and the intellij-server socket mode

Part of #15; follow-up of the #22 desk-research spike.

**Status:** Executed on 2026-10-29. Both servers were launched on the same generated Maven project and driven by a small LSP probe (stdio and TCP, Content-Length framing only). The probe script and raw results are not in the repository (#105), so the numbers below cannot be reproduced from it yet.

- **Measured:** jdtls startup, first diagnostics, references, hover and call hierarchy on a three-class project; intellij-server startup, import behavior and memory while unlicensed.
- **Not measured:** intellij-server quality (the machine has no IntelliJ IDEA Ultimate license, and the feature requests that were tried were refused; see "Unlicensed behavior of intellij-server"), and jdtls on a real repository.
- **Answered:** intellij-server has a socket mode and it works; jdt.ls 1.63.0 needs Java 25, not Java 21; jdtls implements call hierarchy.
- **Sample sizes are small** (every table row is one run) and no medians, means or ranges are claimed. Each item under "Not verified" has its own follow-up issue.

## Environment

- Machine: AMD Ryzen 5 5600GT, 12 logical CPUs, 30 GiB RAM, Linux 7.2.5-3-omarchy. The server installs, `-data` and `--system-path` directories were on a RAM-backed tmpfs, so storage speed is not a factor. Other load on the machine was not controlled.
- Units: file sizes are in MB (10^6 bytes) unless MiB is written; RSS comes from the kiB values in `/proc` and is reported in GiB.
- jdtls: archive `jdt-language-server-1.63.0-202610191322.tar.gz` (51,143,565 bytes, 51.1 MB or 48.8 MiB; spike 22 says "about 51 MB") from https://download.eclipse.org/jdtls/milestones/1.63.0/. The SHA-256 computed locally equals the content of the published `.sha256` file: `b1f2abe0b3cccb462cf64fb4a5587c7d09df60cf8be7748b152cbd89d018bdc4`. The server reports `JDT Language Server (Standard)`, version `1.63.0-SNAPSHOT`, in `serverInfo`. Measured runs used Temurin JDK 25.0.3+9.
- intellij-server: VS Code Marketplace VSIX `JetBrains.intellij-server-1.0.27` for `linux-x64` (about 388 MB as served with gzip content encoding; the saved file is a regular VSIX zip of 389.7 MB). It contains the launcher, a bundled JetBrains Runtime (JBR 25.0.4.1+1-b601.61) and a `vmoptions` file capping the heap at 2048 MB. It reports `IntelliJ Language Server by JetBrains`, version `ILS-263.7084.0`. See "Where the VSIX came from".
- Benchmark project: generated Maven project, three classes (`App`, `Caller`, `Broken`), no external dependencies, `maven.compiler.release=21`. `Broken.java` has a compile error (`notAMethod()` undefined); `Caller` calls `App.greet`, so reference and call-site data exist.

## Method

The probe spawns the server (or connects to its TCP port), sends `initialize`, answers server-to-client housekeeping requests, opens the three files, timestamps the first `publishDiagnostics` carrying non-empty diagnostics, waits until the message stream has been quiet for 25 s, then measures `textDocument/references` (callers of `App.greet`, `includeDeclaration=false`) and `textDocument/hover` on the `greet` name. It samples the resident set size (RSS) of the spawned process from `/proc` every 250 ms.

Commands (run from the project root):

```
jdtls/bin/jdtls -data <data dir> --jvm-arg=-Xmx2048m
intellij-server --stdio --system-path <dir> --eula <hash>
intellij-server --system-path <dir> --socket 127.0.0.1:5999 --port-file <path> --eula <hash>
```

Definitions:

- **fresh**: the `-data` (jdtls) or `--system-path` (intellij-server) directory was created empty for the run. **reused**: the directory left by the previous run was used again. These replace the labels "cold" and "warm" of earlier notes; neither says anything about the operating system file cache.
- **Spawn to `initialize`**: process start to the `initialize` response. **First diagnostics**: first non-empty `publishDiagnostics` for an opened file (jdtls: `The method notAMethod() is undefined for the type Broken`; not the empty "clear" publishing).
- **Last message in the diagnostics window**: time of the last protocol message received before the probe saw 25 s of silence (for jdtls only after the first diagnostics; intellij-server never published any, so 25 s of silence alone). Later timestamps in the raw timelines are the probe's own retry and request replies, not server traffic: run I1 ends its window at 71.3 s and its first retry reply is at 96.5 s, which is 71.3 s plus the 25 s quiet period.
- **Peak RSS**: the sampled maximum for the spawned process only. The probe's walk over child processes has a bug (it compares a process with itself), so children are not included. For jdtls the launcher replaces itself with the JVM, so the figure is the JVM; for intellij-server, helper processes started by the import are not counted (#101).
- **n**: every row below is one run.

## Results

| Run | Server and directory | Spawn to `initialize` | First diagnostics | Last message in window | References | Hover | Peak RSS |
| --- | --- | --- | --- | --- | --- | --- | --- |
| J1 | jdtls, fresh, first launch of the unpacked archive | 33.0 s | not seen (probe stopped after 8.0 s of silence at 39.3 s) | 39.3 s | not recorded | not recorded | 0.62 GiB (partial run) |
| J2 | jdtls, fresh | 17.0 s | 28.6 s | 28.8 s | not recorded | not recorded | 1.37 GiB |
| J3 | jdtls, reused (J2's directory) | 18.8 s | 27.7 s | 30.0 s | 1.2 s | 1.4 s | 0.49 GiB |
| J4 | jdtls, fresh | 26.2 s | 35.7 s | 36.0 s | 0.5 s | 0.1 s | 1.25 GiB |
| I1 | intellij-server, fresh, unlicensed | 27.8 s | none published | 71.3 s | refused, 0.08 s | refused, 0.00 s | 1.65 GiB |
| I2 | intellij-server, reused (I1's directory), unlicensed | 37.3 s | none published | 106.3 s | refused, 0.06 s | refused, 0.00 s | 2.25 GiB |

Notes on the runs:

- J1 was the first launch after unpacking the archive and used a shorter 8 s quiet window, so the probe gave up before any diagnostics arrived; its last-message time and RSS are not comparable with the others and it is listed so the omission is visible. J2 and J3 lack references and hover because that probe version did not record them.
- The fresh jdtls runs span 17.0 to 33.0 s for `initialize`. The two slowest (J1, J4) coincide with a freshly created OSGi `configuration` cache: J1 was the first launch, and the directory's creation timestamp falls at the start of J4. This is a hypothesis; a controlled test that varies only the OSGi cache was not run (#101).
- Reused jdtls (J3) reached first diagnostics at 27.7 s against 28.6 s (J2) and 35.7 s (J4) when fresh, so no benefit from reusing `-data` shows at this project size. Its lower RSS (0.49 against 1.25 to 1.37 GiB) is unexplained; a JIT and GC ramp is one possible reason and was not measured.
- J3's references (1.2 s) and hover (1.4 s) were slower than J4's (0.5 s and 0.1 s), and I2's `initialize` (37.3 s) and RSS (2.25 GiB) were above I1's (27.8 s, 1.65 GiB). With one run each these are not a trend and the causes are unknown (#101).
- The intellij-server runs were unlicensed, so their RSS reflects the import and index work, not served features, and cannot be compared with jdtls on equal terms. The import does real work before the refusals: it runs a Maven import with a bundled helper plugin, logs `BUILD SUCCESS` and flushes a RocksDB index.

What these numbers do and do not support:

- jdtls starts in tens of seconds (17.0 to 33.0 s) and publishes the expected error diagnostics after 27.7 to 35.7 s on a project with no dependencies. No threshold for "fast enough for a TUI session" was defined, so none is asserted; the long window is one more reason for the clear "still indexing" status that #20 already asks for.
- Startup is not shown to be slower or faster on either server: I1's 27.8 s lies inside the spread of the fresh jdtls runs.
- Both servers ran with a 2048 MB heap cap (`-Xmx2048m` was chosen by this benchmark for jdtls; the intellij-server `vmoptions` set it). JetBrains documents about 2 GB of free memory for intellij-server (spike 22); jdtls has no documented figure.

## Verified facts

### Socket mode of intellij-server

- Documented by the launcher `--help`: `--socket=<value>` (default `127.0.0.1:9999`; without `--stdio` the server runs in socket mode), `--port-file=<path>` ("Removed when the server stops"), `--idle-timeout`, `--multi-client` ("If not set, server will be shut down after the first client disconnects"), `--client`, `--license-key`, `--license-key-file`, `--license-server`, `--show-eula`, `--reset-eula`, `--eula`, and a `license` subcommand (`status`, `login`, `activate`, `trial`, `reset`).
- Observed: the server logged `Server is listening on /127.0.0.1:5999`; `--port-file` contained `5999`; a plain LSP client over TCP got the full capability response to `initialize` 8.0 s after connecting. That 8.0 s is connect to `initialize` against a server that was already starting up, so it is not comparable with the 27.8 s spawn to `initialize` of stdio run I1; spawn to `initialize` in socket mode was not measured.
- The port file was still on disk after the server process was gone. How the process was stopped (clean shutdown or kill) was not recorded, so whether the file is removed on a clean stop is unverified (#99).
- A second connection after the first client disconnected was accepted but never answered `initialize` within 30 s. The `--multi-client` help text (the server shuts down after the first client disconnects unless it is set) is consistent with this, but the process state was not checked and `--multi-client` was not run (#98).
- Consequence: intellij-server offers stdio and socket; jdtls offers stdio, sockets and named pipes (spike 22, from its README, not exercised here). The stdio-only restriction of #19 is therefore not forced by intellij-server's transport surface; it remains a product choice.

### Unlicensed behavior of intellij-server

- `intellij-server license status` printed `No valid license found` and `License: not licensed, feature requests are parked (StartedUnlicensed)`. The server log says `No usable license found at startup, starting unlicensed` and names the environment variables `INTELLIJ_SERVER_LICENSE` and `JETBRAINS_LICENSE_SERVER` as alternatives to `license login`. A JetBrains Account was signed in on the machine.
- Spike 22 records the intellij-lsp.nvim README claim that every server build starts with a free 30-day trial. That did not happen here, and the cause is unknown; candidates, none tested, are that the trial only starts through `license trial`, that a trial had already been used, or that the account has no matching subscription (#102).
- `initialize` is answered completely and the workspace import runs. The three feature requests tried (`textDocument/references`, `textDocument/hover`, `textDocument/prepareCallHierarchy`) got an immediate refusal: `{"code": -32802, "message": "Request refused: no active JetBrains license. Run 'intellij-server license login' to activate, or 'intellij-server license --help' for options."}`. Other methods were not probed (#104).
- No `publishDiagnostics` arrived in the watched windows (until 96.4 s after spawn for I1 and 131.4 s for I2); a longer watch was not done (#104). Spike 22 recorded "starts but answers nothing" as read from the README; what was observed is that it answers, with a fixed refusal for the methods above.
- The trial was not started. The help describes `license trial` only as "Start an anonymous trial"; whether it uses up trial state of the signed-in account is neither documented there nor tested, so it was not run without the user's go-ahead. The licensed benchmark is #73.

### EULA flag and side effects on the research machine

- All intellij-server runs passed `--eula cd82e6cdaf2790f1`, the first 16 hex characters of the SHA-256 of the bundle's own `EULA.txt` (the algorithm in JetBrains' `intellij-lsp.nvim` `eula.lua`). The researcher supplied it directly, without a consent screen. That is acceptable for this research only (spike 53 read the macOS bundle's EULA text; the Linux bundle's text was not compared, see #65); it is exactly the step that spike 53 says Vintelli must gate on explicit user consent.
- `--eula` is documented as remembered "for the user and machine", with `-Dintellij.server.eula.persistence=false` to disable the record. Only run I1 set that property. The later runs (I2 and the socket runs) did not, so an accepted-hash record exists in the JetBrains config directory of the research machine's user; `intellij-server --reset-eula` removes it.
- During import the server installs its bundled helper (`com.jetbrains.ls:imports-maven-plugin`) into the user's default Maven local repository (`Installing ... to ~/.m2/repository/com/jetbrains/ls/imports-maven-plugin`; the log also reports `Detected Maven repo` for that path). Starting the server therefore changes persistent state outside its `--system-path` (#103).

### Java runtime for jdtls

- jdt.ls 1.63.0 needs a Java 25 runtime. With Temurin 21.0.12 the OSGi layer fails to resolve (`Unresolved requirement: Require-Capability: osgi.ee; filter:="(&(osgi.ee=JavaSE)(version=25))"`) and the process exits (exit code 13, re-checked while preparing this document).
- Temurin 25.0.3 and OpenJDK 27 (build 27, 2026-09-15) both started the server and answered `initialize` and the call hierarchy requests below. An earlier note that Java 27 did not start cleanly was not reproduced and is withdrawn. Java 22 to 24 were not tested (#100).
- This corrects the "Java 21 (minimum)" line in spike 22. #20's runtime detection must look for Java 25 or newer for this jdtls milestone.

### Call hierarchy

- The probe's built-in call hierarchy step sent `textDocument/callHierarchy/prepare`, which is not an LSP method; the correct name is `textDocument/prepareCallHierarchy`, followed by `callHierarchy/incomingCalls` and `callHierarchy/outgoingCalls`. The "no items" results in the raw files of runs J1 to J4, I1 and I2 are therefore invalid, and no conclusion is drawn from them.
- Both servers advertise `callHierarchyProvider: true` in their `initialize` response (intellij-server: line 144 of the raw output of the socket run, and again in the re-probe; jdtls: read in the re-probe).
- Re-probe with the correct methods on the same project, one run each, 30 s (intellij-server) or 45 s (jdtls) after opening the files; it is not part of the table above and was not repeated. jdtls answered `prepareCallHierarchy` at `App.greet` in 0.18 s with one item (`greet(String) : String`, `bench.App`), `incomingCalls` in 0.44 s with one caller (`run() : int`) and `outgoingCalls` in 0.54 s with none. On OpenJDK 27 the same sequence took 0.32 s, 4.02 s and 0.84 s with the same results. intellij-server (unlicensed) refused `prepareCallHierarchy` with `-32802` in 0.08 s; incoming and outgoing calls were not reached.
- Consequence for #20: call hierarchy can stay in the LSP capability plan for jdtls. Its latency over repeated runs and on real repositories is not established (#101, #97), and intellij-server's call hierarchy needs a license to be exercised (#73).

### Where the VSIX came from

- The VSIX was fetched with plain `curl` from the VS Code Marketplace with no sign-in and no cookies. The exact URL was not recorded. This is how the research copy was obtained, not evidence about the download path Vintelli would use.
- Spike 53 decided that an auto-download must come from `download.jetbrains.com` with a pinned URL and a SHA-256 check, behind an explicit EULA gate, and never from a mirror. That is its reading and not legal advice, and it left the Marketplace and Open VSX hosting terms unreviewed. This document draws no conclusion from the Marketplace fetch about licensing or about any download policy, and does not revisit spike 53.

## Decision

The #22 default stands: **jdtls remains the default; intellij-server stays opt-in and user-licensed.** This spike adds measurements for jdtls and the unlicensed behavior of intellij-server; it does not establish that either server is better.

- jdtls works end to end on the benchmark project (diagnostics, references, hover and call hierarchy) when it has a Java 25 runtime.
- intellij-server cannot be compared on quality without a license, so it cannot be chosen on merits here. #54 still owns the agentic licensing reassessment and #73 the licensed benchmark.
- A socket profile is possible for intellij-server; stdio remains sufficient for Vintelli (#19), and a socket profile stays an optional runtime profile if #20 wants one.

## Not verified

- intellij-server quality, import latency with real dependency resolution, diagnostics, call hierarchy and call-site latency under a license, and memory while features are served (#73). The license is the blocker; see "Unlicensed behavior of intellij-server" for the trial question.
- Which LSP methods other than references, hover and `prepareCallHierarchy` the unlicensed server refuses, and whether diagnostics arrive after the watched windows (#104).
- Why the server started unlicensed instead of in the trial described in spike 22 (#102).
- jdtls on real multi-module and dependency-heavy repositories, including a profile of the import phase; the project here measures the floor, not the ceiling (#97).
- Repeatability of every number above, the cause of the reused-slower-than-fresh cells, the OSGi cache hypothesis, and child-process memory (#101).
- The `--multi-client` and second-connection behavior (#98), port-file removal on stop (#99), and the intellij-server writes outside its system path (#103).
- Java 22 to 24 for jdtls 1.63.0 (#100).

## Follow-ups

- #73: licensed intellij-server benchmark, now including call hierarchy with the correct method names.
- #97: jdtls on real repositories, including an import-phase profile.
- #98: intellij-server `--multi-client` and what happens when the first client disconnects.
- #99: port file removal on clean stop.
- #100: require a Java 25 runtime for the jdtls profile in #20 and detect it at runtime.
- #101: repeat the runs with enough samples and a corrected RSS sampler.
- #102: why a fresh intellij-server starts unlicensed instead of in the documented trial.
- #103: intellij-server writes outside its system path, starting with the user Maven repository.
- #104: probe every LSP method on the unlicensed intellij-server and watch diagnostics for longer.
- #105: commit the LSP probe and trimmed raw results.
- #54: agentic use licensing; unchanged by this spike.
- #53 is closed; its verdict (spike 53) is not changed by this spike.
