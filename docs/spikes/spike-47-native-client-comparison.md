# Spike 47: native cost of a minimal LSP4J client versus the hand-rolled client

**Status:** Follow-up of [spike 23](spike-23-lsp4j-vs-own-jsonrpc-client.md) required by issue #47. The two minimal clients required by issue #23 were built and ran the same LSP round trip (`initialize`, `initialized`, one `textDocument/hover`, `shutdown`, `exit`) against a common echo server, on the JVM and as native images, all on GitHub CI.

**Result:** the native build of a minimal `LSP4J` client works. With the dynamic proxies registered, a standalone `LSP4J` 1.0.0 native image and an `LSP4J` client hosted in a Quarkus native image complete the session. Without that registration the Quarkus-hosted client fails on its first launcher call. The first version of this spike reported that `LSP4J` could not complete a native session; that was an artifact of the benchmark's own config generation (see "Correction of the first version") and is withdrawn. The decision in spike 23 is therefore no longer supported by native evidence, and it is not refuted by it either: what is left is the cost of maintaining the registration over the real 0.1.0 surface, which this spike did not measure (#106).

**Evidence and lifetime:** the CI run [40857595820](https://github.com/omatheusmesmo/vintelli/actions/runs/40857595820) is the source of every number below unless another run is named. The bench files of the final commit are identical to the ones that run exercised (the final commit was only rebased and its document edited), and its own `native-client-bench` run, [40859331744](https://github.com/omatheusmesmo/vintelli/actions/runs/40859331744), passed as well. The workflow `.github/workflows/native-client-bench.yml` and the directory `docs/spikes/spike-47-native-client-bench/` were spike-scoped and temporary; they were removed in #111. Workflow logs and artifacts expire, so the tables and log excerpts below are the record, and the run URLs are the evidence.

## What was built

Standalone reproducers, outside the repo source tree (the Maven module split, #16, is in flight and must not be touched):

- `echo-lsp-server.java`: single-file echo LSP server answering `initialize` with capabilities (`textDocumentSync` 1, `hoverProvider`), `textDocument/hover` with markdown contents, `shutdown` with `null`, and exiting on `exit`. It never sends notifications, server requests or error responses.
- `lsp4j-minimal-client.java`: Option A, the minimal `LSP4J` client (`org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0`, `jbang` single file) on the stock stack: dynamic proxy endpoints through `LSPLauncher`, `Gson`, `CompletableFuture` calls.
- `handrolled-minimal-client.java`: Option B, a minimal hand-rolled client: `Content-Length` framing, a small JSON tree parser, records with hand-written mapping, JDK only, no reflection. It is a stand-in that mirrors what `vintelli-jsonrpc` (#19) would ship; it is not the shipped transport.
- `lsp4j-quarkus-minimal/`: the `LSP4J` flow hosted in a minimal Quarkus application (`@QuarkusMain`, Quarkus 3.40.1.1, the BOM main uses), built as a Quarkus native image in CI with the `container-build` flag the repo's `build-native` job uses.

Both clients support spawn mode (the client starts the echo server, so timings include that spawn) and stdio mode (server externally piped through two fifos, timings cover the client and the wait for the server). Both print one summary line: `<client> <init_ms> <hover_ms> <total_ms> ok`.

## Limits of the comparison

The hand-rolled client is happy-path only, so size and time comparisons are not like-for-like and its numbers are lower bounds for what #19 will ship:

- it is single-threaded and synchronous; its timeout loop is not enforced because the body read blocks;
- it never answers server-to-client requests; messages that carry a `method` are skipped when waiting for a response, so an id collision is not mistaken for the response;
- error responses are surfaced as an exception carrying the server error, with no handling of `LSP` error codes;
- the echo server sends none of these cases, so the server-to-client and error paths are untested on both clients (#109).

The first version of the hand-rolled client also lacked `\uXXXX` escapes and matched `Content-Length` case-sensitively; both are fixed in this version. The echo server's header length check is applied after buffering, so it is not a bound; acceptable for a throwaway test double.

## JVM round trips (CI, five runs each)

| mode | client | init ms (min / median / max) | total ms (min / median / max) |
|---|---|---|---|
| spawn | hand-rolled | 553 / 611 / 655 | 566 / 629 / 668 |
| spawn | `LSP4J` | 725 / 739 / 755 | 742 / 756 / 784 |
| stdio | hand-rolled | 7 / 7 / 9 | 9 / 10 / 14 |
| stdio | `LSP4J` | 271 / 275 / 288 | 279 / 282 / 296 |

Spawn mode includes the JVM echo server spawn for both clients (roughly 550 to 650 ms), so the gap there is about 125 ms at the median. In stdio mode, with the server already warm, `LSP4J` needs about 275 ms before `initialize` returns against 7 ms. This is a JVM cost measured on one runner with five runs per cell; nothing was profiled, so it is not attributed to any component (a hypothesis to test in #107). An earlier version of this document quoted "1 to 3 seconds" from a local machine without run counts; that figure is removed.

## Tracing agent and reachability metadata

GraalVM tracing agent on the `LSP4J` stdio session (GraalVM Community Edition `graalvm-community-jdk-25i4-25.0.4.1.1`, GraalVM 25 `reachability-metadata.json` format):

| item | count |
|---|---|
| `reflection` entries, total | 204 |
| class entries (`type` is a string) | 200 |
| class entries in `org.eclipse.lsp4j` | 176 |
| class entries in `com.google.gson` | 1 |
| proxy entries (`type` is `{"proxy": [...]}`, inside `reflection`) | 4 |
| `resources` entries | 1 |
| entries kept after dropping JDK and tool-internal types | 181 (177 classes and the 4 proxies) |

In this format proxies are dict-typed entries of the `reflection` array, not a top-level `proxy` key. The agent recorded four proxy sets, each the service interface plus `org.eclipse.lsp4j.jsonrpc.Endpoint`: `LanguageServer`, `TextDocumentService`, `WorkspaceService` and `NotebookDocumentService` (all in `org.eclipse.lsp4j.services`). `LanguageClient` was not recorded because the client flow never proxies it. The first version of this spike read a non-existent top-level `proxy` key, reported zero proxy entries, and concluded that the agent cannot see `LSP4J` proxies; that was wrong.

One tiny session already reflects 176 `LSP4J` classes (the result tree, union type adapters such as `HoverTypeAdapter$Factory` and `CodeActionResponseAdapter`, and the endpoint service classes). This is the floor for the single flow measured; how it grows on the full surface of #20 is not measured (#106). The raw agent output is not committed because it also carries local tool noise (`sun.security.x509`, `jdk.*` internals); the CI run uploads the agent, filtered and sweep configs as the artifact `spike-47-bench-results`, which expires.

## Native builds and sessions (CI)

Environment: GitHub `ubuntu-latest`, GraalVM Community Edition `graalvm-community-jdk-25i4-25.0.4.1.1` for the standalone builds, Mandrel builder `jdk-25` for the Quarkus builds (`container-build`), `--no-fallback`, default optimization, Linux x86_64. Sizes are in bytes with MiB in parentheses.

| variant | config | build time | image size | sessions completed |
|---|---|---|---|---|
| hand-rolled, standalone | none | 73 s | 11,405,568 (10.88 MiB) | 5 of 5 |
| `LSP4J`, standalone | agent config, 181 entries | 84 s | 13,895,936 (13.25 MiB) | 5 of 5 |
| `LSP4J`, standalone | member-complete sweep, 565 classes and 5 proxies | 97 s | 16,451,840 (15.69 MiB) | 5 of 5 |
| `LSP4J`, Quarkus runner | none | 147 s (whole Maven build; native generation 1 m 45 s) | 36,486,200 (34.80 MiB) | 0 of 1: exit 1 after 22 ms |
| `LSP4J`, Quarkus runner | agent config, 181 entries | 118 s (whole Maven build; native generation 1 m 46 s) | 37,862,456 (36.11 MiB) | 5 of 5 |

Startup bench against the compiled echo server (a JVM started per run), five runs per binary. The init figure includes the wait for that server JVM to come up, so it is a floor shared by all rows rather than a client cost. Values are min / median / max in milliseconds:

| binary | wall | init | total |
|---|---|---|---|
| hand-rolled | 90 / 99 / 104 | 71 / 81 / 83 | 72 / 84 / 85 |
| `LSP4J`, agent config | 100 / 111 / 112 | 80 / 91 / 95 | 83 / 93 / 98 |
| `LSP4J`, sweep config | 95 / 103 / 108 | 75 / 86 / 87 | 77 / 89 / 92 |
| `LSP4J` Quarkus runner, agent config | 93 / 99 / 107 | 66 / 73 / 85 | 69 / 75 / 87 |

The native `LSP4J` binaries finish the session within roughly 10 ms of the hand-rolled one in this bench; the JVM gap of about 275 ms does not appear in native here. With five runs and a JVM server dominating the floor, differences of this size are not resolved (#107). There is no hand-rolled Quarkus baseline, so the Quarkus runner size says nothing about the client choice.

The workflow fails the job unless the hand-rolled binary completes 5 of 5 sessions with exit code 0; the `LSP4J` results are recorded as data.

### What failed and why

The Quarkus runner built with no reflection config exits with code 1 after 22 ms. Quarkus writes its console log to stdout, which in this bench is the protocol channel; with `QUARKUS_LOG_CONSOLE_STDERR=true` the real error is visible, which explains the silent exit that #79 was opened for (stack abridged):

```
ERROR [io.quarkus.runtime.Application] (main) Failed to start application: org.graalvm.nativeimage.MissingReflectionRegistrationError: Cannot reflectively access the proxy class inheriting ['org.eclipse.lsp4j.services.LanguageServer','org.eclipse.lsp4j.jsonrpc.Endpoint'].
  at org.graalvm.nativeimage.builder/com.oracle.svm.core.reflect.proxy.DynamicProxySupport.getProxyClass(DynamicProxySupport.java:200)
  at java.base@25.0.4.1/java.lang.reflect.Proxy.newProxyInstance(Proxy.java:924)
  at org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints.toServiceObject(ServiceEndpoints.java:41)
  at org.eclipse.lsp4j.launch.LSPLauncher.createClientLauncher(LSPLauncher.java:106)
```

So Quarkus does not auto-provision the `LSP4J` proxies. With the agent-recorded config placed under `src/main/resources/META-INF/native-image/` of the reproducer (CI only, not committed), the same flow completes: `lsp4j-quarkus 73 1 75 ok` and four more runs in the final CI run.

## Correction of the first version

The first run of this spike (CI run [40821723324](https://github.com/omatheusmesmo/vintelli/actions/runs/40821723324), and earlier) reported that both `LSP4J` native binaries abort at launcher creation and concluded that the missing set was large. Both failures came from the benchmark's own config generation:

- the `keep()` filter treated dict-typed entries as having an empty type and dropped the four proxy entries the agent had recorded, so the agent-config binary failed on exactly a proxy that had been discarded (`Cannot reflectively access the proxy class inheriting [NotebookDocumentService, Endpoint]`);
- the "blanket sweep" emitted bare `{"type": ...}` entries, which make a class reachable by name but register no constructors, methods or fields, and no proxies, so it failed on `Cannot reflectively invoke constructor 'public org.eclipse.lsp4j.adapters.WorkspaceSymbolResponseAdapter()'`.

The filter now keeps dict-typed proxy entries whose interfaces are `org.eclipse.*` or `com.google.gson`. The sweep now emits `allDeclaredConstructors`, `allDeclaredMethods` and `allDeclaredFields` per class plus five proxy sets (the four recorded by the agent, and `LanguageClient`, added from reading the code and not agent output). Both configs then complete the session. The earlier statements "the missing set is large, not a single hole" and "neither agent output nor a full-class sweep covers it" are withdrawn.

## Other measured facts

- The default `LSPLauncher` executor is a cached pool of non-daemon threads. In CI run [40853693506](https://github.com/omatheusmesmo/vintelli/actions/runs/40853693506), before the client was changed to exit explicitly, every `LSP4J` process printed `ok` and then stayed alive: the JVM processes for about 61 s each (the idle expiry of the cached pool), the native processes until the 60 s `timeout` killed them (`exit_rc=124`, wall 60,008 ms); the hand-rolled client exits at once. The `LSP4J` client in this spike now calls `System.exit(0)` after the summary line. A real client has to configure the executor or exit explicitly (#106).
- Build and size: `LSP4J` adds 11 s of build time and 2.4 MiB to the standalone image with the agent config, and 24 s and 4.8 MiB with the sweep.
- The tracing-agent metadata has to be filtered before use: the agent also records `sun.security.x509` and `jdk.*` internals triggered by the tooling, which are dropped (204 entries recorded, 181 kept).

## Decision

**Not confirmed by native evidence; the native blocker is withdrawn.**

1. Measured: a minimal `LSP4J` client completes a native session, standalone and inside Quarkus, with a 181-entry config that one tracing-agent run produces. It does not need a blanket sweep, and it fails without registration (Quarkus runner, no config). Spike 23's reason that `LSP4J` cannot run natively without large manual registration does not hold for this flow.
2. Measured: the native cost in this bench is small (2.4 MiB of image, 11 s of build time, about 10 ms in the startup bench); on the JVM the cost is about 275 ms of initialization, and the process needs explicit handling to exit.
3. Not measured: how the config grows with the full #20 surface, how stable it is across `LSP4J` and `Gson` upgrades, and whether the hand-rolled risks of spike 23 (unions, missed protocol details) cost more than that upkeep. Those are the remaining grounds of the spike 23 decision (hand-rolled client on `vintelli-jsonrpc`), which stands provisionally on them until #106 provides numbers. Whoever implements #19 should treat the native argument as closed and decide on those grounds.

## Not verified here

Each item has its own follow-up issue in milestone 0.1.0.

- Growth and upgrade stability of the `LSP4J` registration on the real 0.1.0 surface, and the executor setup a real client needs: #106.
- A native session against the real `jdt.ls`: belongs to #20 task one and needs the `vintelli-lsp` module on the #19 transport; a standalone reproducer cannot cover the real server surface (acceptance bar in #11 and #20).
- JVM and native timing with at least ten runs, median and p95, and attribution of the `LSP4J` initialization cost: #107.
- The Mandrel standalone failure (`sun.security.x509.AlgorithmId` type not found) seen once on the signed `org.eclipse.lsp4j` jars before the switch to GraalVM Community Edition. No log was kept, so it is an observation, not a result; the Quarkus Mandrel container build of the same jars succeeded in every run here: #108.
- Server-to-client notifications and requests, error responses, and the hand-rolled client's gaps listed under "Limits of the comparison": #109.
- Other platforms (macOS, Windows), other optimization levels, and Mandrel versus GraalVM Community Edition for the standalone builds: #110.
- The Quarkus-hosted runner (#79): the failure and the working configuration are recorded above; the issue can be closed once its acceptance criteria are checked against this document.
- Deletion of the workflow and the reproducers: #111.

## Run instructions (local)

1. `cd docs/spikes/spike-47-native-client-bench`
2. Spawn mode: `java handrolled-minimal-client.java java echo-lsp-server.java` and `jbang lsp4j-minimal-client.java java echo-lsp-server.java`
3. stdio mode: two fifos, server piped externally, client without args.
4. Native builds run on CI only (`.github/workflows/native-client-bench.yml`; it runs on pull requests touching the bench directory and on `workflow_dispatch`). The Quarkus reproducer builds with the root wrapper: `../../../../mvnw -f pom.xml package -Dnative -Dquarkus.native.container-build -DskipTests`. `-DskipTests` is acceptable there because the reproducer has no tests; the repo's `build-native` job is unaffected.
5. The workflow downloads a GraalVM tarball and the `jbang` installer without pinning or checksums; that is acceptable for a throwaway spike workflow and one reason it is to be deleted (#111).

## Links

- Issue prompting this spike: https://github.com/omatheusmesmo/vintelli/issues/47
- Source spike: [spike 23](spike-23-lsp4j-vs-own-jsonrpc-client.md)
- pkl-lsp native packaging case study (same as spike 23): https://github.com/pngdeity/aur/blob/main/TASKS.md
- Final CI run: https://github.com/omatheusmesmo/vintelli/actions/runs/40857595820
- CI run with the 60 s `LSP4J` process exit: https://github.com/omatheusmesmo/vintelli/actions/runs/40853693506
- CI run of the first version (withdrawn conclusions): https://github.com/omatheusmesmo/vintelli/actions/runs/40821723324
