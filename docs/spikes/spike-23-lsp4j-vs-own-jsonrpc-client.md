# Spike 23: LSP4J vs own JSON-RPC client

**Status:** The native build experiment required by issue #23 (a native build of a minimal client with each option) was not run. The decision below rests on static analysis of the LSP4J 1.0.0 and jdt.ls artifacts, not on a native build. A native build of a minimal LSP4J client remains a follow-up.

## Decision

**Hand-rolled client on vintelli-jsonrpc (#19) for 0.1.0.** Reasons, in order of weight:

1. It is the option that most directly satisfies #19's native-image acceptance criterion ("no reflection configuration needed in native image"). LSP4J currently ships no GraalVM metadata (#349 open on 1.0.0), and the known working patterns all need hand-maintained or generated reflection and proxy registration (pkl-lsp registers its whole 565-class lsp4j tree plus 5 proxies; LemMinX ships a curated config with about 237 lsp4j entries), which has to be re-audited on every upgrade.
2. 0.1.0's message surface (roughly 30 methods, narrow direction, predominantly client-side) is small enough that the hand-written record surface stays manageable (an estimated 60 to 100 record types, not yet counted); the expensive parts of LSP4J (full Either/union surface, DAP, server-side hosting) buy us nothing.
3. One JSON-RPC stack serves LSP (#20) and ACP (#6); LSP4J would duplicate the jsonrpc module.
4. Blocking, final-bean Quarkus style matches a record/virtual-thread client naturally; LSP4J's proxy and future API does not match it (minor).

**Structure the code so revisiting stays cheap:** `vintelli-lsp` will sit behind the Vintelli capability interfaces (#18, not yet implemented) and the TUI/CLI/MCP-server only see `vintelli-api` (#15 dependency rule). A hypothetical LSP4J-based adapter would be one provider implementation away, with the app's `tui` etc. untouched.

**Baseline correction to the spike question:** no native minimal-client build was run for either side in this spike (research only). The practical output moves to #20 task one: a minimal `vintelli-lsp` client (initialize + sync + publishDiagnostics) passing the #11 native smoke, with no reflection or proxy registration (hand-written, annotation-driven or Quarkus-generated) for `vintelli-jsonrpc` or `vintelli-lsp` types, and an LSP session against jdt.ls running in the native binary.

## Measured and verified facts

### LSP4J status and footprint (measured on Maven Central, 2026-10-28)

- Latest release is v1.0.0 (Feb 2026) ([release notes](https://github.com/eclipse-lsp4j/lsp4j/releases/tag/v1.0.0), [Maven Central](https://repo1.maven.org/maven2/org/eclipse/lsp4j/)). It implements LSP 3.18.0 (specification not finalized) plus the SymbolTag proposal and DAP 1.70.0, per the 1.0.0 changelog; the 0.x line targeted 3.17.0. 1.0.0 also contained source-breaking type changes such as `Diagnostic.message` becoming `Either<String, MarkupContent>`. The project is active again: 0.22.0 (Feb 2024), 0.23.1 (May 2024), 0.24.0 (Jan 2025), 1.0.0 (Feb 2026).
- Artifact sizes (jar downloads measured):
  - `org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0`: 684 KB, 480 classes, `Bundle-RequiredExecutionEnvironment: JavaSE-11`, bytecode major 55 (Java 11).
  - `org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:1.0.0`: 155 KB, 85 classes.
  - Only transitive Maven dependency is Gson `[2.9.1,3.0)` (latest 2.15.0 jar = 316 KB). No Guava in the Maven POM (Guava only matters in the OSGi/p2 world). The deprecated javax `org.eclipse.lsp4j.websocket` artifact was dropped in 1.0.0 (last release 0.24.0); `org.eclipse.lsp4j.websocket.jakarta` still exists at 1.0.0 but is not a dependency of `org.eclipse.lsp4j` or `org.eclipse.lsp4j.jsonrpc`. The `debug` (DAP) artifact is separate and not pulled in by `org.eclipse.lsp4j`.
- Reflection is structural, measured from the 1.0.0 bytecodes:
  - 25 of the 85 `jsonrpc` classes reference `java/lang/reflect`; `ServiceEndpoints` and `EndpointProxy` use `java.lang.reflect.Proxy`. Every LanguageServer/Client endpoint in LSP4J is a dynamic proxy built by scanning `@JsonSegment`/`@JsonRequest` annotations at runtime.
  - 28 of 85 `jsonrpc` classes use Gson, and the DTO classes are plain POJOs with annotation-based adapters (`EitherTypeAdapter` in `org.eclipse.lsp4j.jsonrpc.json.adapters`, and per-union adapters such as `HoverTypeAdapter` in `org.eclipse.lsp4j.adapters`).
- Java 25: runs as an ordinary library; bytecode is Java 11 and upstream CI runs JDK 21 (PR [#918](https://github.com/eclipse-lsp4j/lsp4j/pull/918)). Expectation of no blocker on JVM 25 is an estimate (not tested on 25 in this spike). Since Vintelli runs native image through GraalVM for JDK 25, "runs on Java 25" is really a native-image question, which is the next point.
- Native image: LSP4J ships no GraalVM reachability metadata; the upstream issue asking for SVM metadata ([#349](https://github.com/eclipse-lsp4j/lsp4j/issues/349)) is still open. A real-world data point (documented 2026-10-15, [AUR pkl-lsp packaging notes](https://github.com/pngdeity/aur/blob/main/TASKS.md), section 14c): a native build took the tracing-agent route, and the produced metadata registered 0 of 565 lsp4j classes because the reflection surface only executes during a live session (proxy creation, `GenericEndpoint`, Gson adapter discovery). Their working solution registers the entire `org/eclipse/lsp4j/**` class tree (~565 classes) plus 5 proxies in `META-INF/native-image/.../reachability-metadata.json` (the proxies are pkl-lsp's server-side ones, so they do not transfer to a Vintelli client; upstream reference: apple/pkl-lsp PR #226). Same class count as measured on 1.0.0 (480 + 85). That is a blanket sweep, not proof that every class is used reflectively; it shows that nobody has a cheaper reliable way to enumerate the reflective surface, since the tracing agent alone reached only 182 of 565 in their own notes. Their fight also surfaced practical traps: `native-image` silently ignores metadata files not under `META-INF/native-image/<group>/<artifact>/`, and blanket `--initialize-at-build-time=` blew up on JNA static init.

### What jdt.ls minimally requires from a client (verified from eclipse.jdt.ls `main` source)

Full-featured clients do much more, but the required floor is small:

- `initialize`/`initialized`, `shutdown`/`exit`; server-side dynamic registration with `client/registerCapability`/`client/unregisterCapability` (`JDTLanguageServer`, `BaseJDTLanguageServer`, `JavaClientConnection`). E.g. `workspace/didChangeWatchedFiles` is registered dynamically by `StandardProjectsManager` and must be honored or file watching silently does not happen.
- `workspace/configuration` (server-to-client request, `JavaClientConnection.configuration`) and server-to-client `workspace/applyEdit` (used by document lifecycle handlers via the `ClientPreferences.applyEdit` check; whether jdt.ls falls back when the client does not support it was not verified).
- `window/showMessage`, `window/logMessage`, `window/showMessageRequest`, `$/progress` (workDoneProgress), and jdt.ls custom notifications (`language/status`, `language/actionableNotification`, `language/eventNotification`, `language/progressReport`). A diagnostics-only client may ignore the actionable and event ones, but `language/status` and `$/progress` are what #20's "clear status when `jdtls` is missing or still indexing" criterion needs, so the client must at least parse them.
- Document sync: `textDocument/didOpen`/`didChange`/`didSave`/`didClose` (sync kind negotiated, incremental supported).
- Diagnostics are pushed (`textDocument/publishDiagnostics`). Hover, completion, definition, call hierarchy, etc. are ordinary requests with no client-side prerequisites beyond the capability tree (extended `java.*` client capabilities are all optional; `ClientPreferences` defaults everywhere).

So a minimum usable list is: initialize with `rootUri` and capabilities, the four sync notifications, replies to `workspace/configuration` and `client/registerCapability`, a reply to the server-to-client `window/workDoneProgress/create` request and handling of `$/progress` notifications, `publishDiagnostics` reception, plus the requests Vintelli actually surfaces (hover, completion, definition, callHierarchy/*, documentSymbol, codeAction). Roughly 30 protocol methods (both directions) for the 0.1.0 scope defined by epic #15 (Problems view, hover, completion in the editor, go-to-definition, call hierarchy).

### Option A: LSP4J (1.0.0 + Gson)

**Pros**

- Complete LSP 3.17/3.18 message surface with `Either` unions, cancellation, tracing, and tests already written and debugged against real servers. jdt.ls itself is built on LSP4J, so DTOs fit jdt.ls exactly.
- Actively maintained again; 1.0.0 is a real stability marker, and the version range for Gson was broadened to `[2.9.1,3.0)` to ease upgrades.
- Zero protocol bugs on our side for features we did not model.

**Cons / risks**

1. Structural conflict with the reflection-free/native-ready rule and with #19's acceptance criterion ("no reflection configuration needed in native image"). Using LSP4J means either registering all ~565 protocol classes + 5 proxies wholesale (the pkl-lsp approach), or accepting that a metadata sweep must be regenerated for every LSP4J upgrade with failure modes that are silent (`initialize` crashes only when a real session runs). That conflicts with the Vintelli default of avoiding reflection and runtime proxies unless strictly required (AGENTS.md, epic #15 principle 5).
2. The boilerplate payoff shrinks for our surface: LSP4J's biggest value is the full type surface, but 0.1.0 needs ~25 methods in a narrow direction (client that talks to jdt.ls). We pay the full-cost dependency for a subset, and reflection metadata for the whole `org.eclipse.lsp4j` tree (480 classes: roughly 415 protocol types plus Gson adapters and service interfaces) ends up in the native image regardless.
3. API shape mismatch: LSP4J is `CompletableFuture`-based endpoints accessed through interfaces. A blocking-bean Quarkus caller must `join()` or adapt futures, and server-initiated requests arrive through the same proxy/annotation machinery that we want to avoid. Custom jdt.ls methods (`language/*`, `java/*`, `workspace/executeClientCommand`) need extra annotated interfaces extending `LanguageClient` / `LanguageServer`, as jdt.ls does itself; still proxy-based.
4. A second abstraction module (`org.eclipse.lsp4j.jsonrpc`) that duplicates what #19 already builds for ACP (framing, requests, notifications, cancellation, lifecycle): two JSON-RPC stacks in one process for the same protocol.
5. Two JSON libraries in the image: LSP4J brings Gson (`[2.9.1,3.0)`, resolved to 2.15.0 at 316 KB) next to the Jackson stack already in the project (`quarkus-rest-jackson`) and planned for #6.

**Effort to a native-green minimal client (estimate):** 1-2 days to wire the metadata sweep (script that expands `org/eclipse/lsp4j/**` from the jar into `META-INF/native-image/org.eclipse.lsp4j/<artifact>/reachability-metadata.json`) plus CI verification. But recurring: every LSP4J/Gson upgrade re-runs the sweep, and breakage appears at runtime, not at build time. The recurring part is partly mitigated by the native CI gate (#11, and the native integration tests merged in #42), provided a test drives a real LSP session; otherwise the failure is silent. In a Quarkus app the sweep can also be a build step or `@RegisterForReflection(targets = ...)` over the Jandex index, with no script to maintain, which weakens the "regenerate on every upgrade" argument; the remaining problems (proxies, Gson, a second JSON stack, silent runtime failures) still apply. No Quarkus or Quarkiverse extension for LSP4J was found on Maven Central under `lsp4j`; this still needs confirmation with `quarkus_searchDocs`.

### Option B: hand-rolled client on vintelli-jsonrpc (#19)

#19 (open) is scoped to build the hard part: `Content-Length`/newline framing, requests, responses, notifications, cancellation, server-initiated requests, process lifecycle, records only, virtual threads, echo-server round-trip tests. #20 then adds a typed layer on top.

**Message surface needed for 0.1.0** (driven by epic #15's capability table): initialize/initialized/shutdown/exit, `$/cancelRequest`, `$/progress` + `window/workDoneProgress/create`, `workspace/configuration`, register/unregisterCapability, `workspace/applyEdit`, `workspace/executeClientCommand` and `workspace/notify` (jdt.ls server-to-client, client handler; answer with an error or null if unsupported), window/showMessage + logMessage, document sync (4 notifications, incremental + full ranges), `textDocument/publishDiagnostics`, hover, completion (+ optional `completionItem/resolve`; jdt.ls works without), definition, documentSymbol, codeAction, `callHierarchy/prepare` + incoming/outgoing, references. That is roughly 30 methods and an estimated 60 to 100 record types once params, capabilities and options types are counted (not yet counted; the first task in #20 should produce the real number). The examples (Position, Range, Location, TextEdit, Diagnostic, Hover, MarkupContent, CompletionItem subset, CallHierarchyItem/IncomingCall/OutgoingCall, ...) leave out InitializeParams/InitializeResult, the ClientCapabilities tree, ServerCapabilities and its option types, Registration, ConfigurationItem, PublishDiagnosticsParams, document lifecycle params, WorkspaceEdit and the progress and message payloads, plus one generic "raw JsonValue" escape hatch for anything untamed.

**The real risks, honestly stated:**

- Union types. LSP 3.17 has unions exactly where our scope needs them: `Hover.contents` (`MarkupContent | MarkedString | MarkedString[]`), definition (`Location | Location[] | LocationLink[]`), `DocumentSymbol` vs flat `SymbolInformation` response, `CompletionItem.documentation`, the completion result (`CompletionItem[] | CompletionList`), `CompletionItem.textEdit` (`TextEdit | InsertReplaceEdit`), the code action result (`(Command | CodeAction)[]`), `Diagnostic.code` (`integer | string`) and the `ServerCapabilities` providers (`boolean | options`, `textDocumentSync` as number or options). These are the single biggest source of hand-rolled client bugs. Mitigation: model the two or three variants jdt.ls actually emits (jdt.ls's own code is inspectable), keep a raw-JSON fallback, and add protocol-level integration tests against a real jdt.ls, not just the echo server.
- Missed message details (params omitted when empty (LSP4J fixed exactly this in 1.0.0 via [PR #885](https://github.com/eclipse-lsp4j/lsp4j/pull/885), which is evidence of how easy it is to get wrong), null handling, `initialized` notification, registerCapability timing). Mitigation: mirror jdt.ls and lsp4j test cases; LSP4J's `CHANGELOG.md` doubles as a checklist of protocol pitfalls implemented by others.
- Rollout cost grows with scope. rename/refactoring (`WorkspaceEdit` with annotated-document edits, 3.x `documentChanges` + metadata), semantic tokens, inlay hints would inflate the surface well past the point where writing records is cheap. That is out of 0.1.0 scope per epic #15.
- Hidden work not in the estimate: `workspace/didChangeWatchedFiles` (jdt.ls registers file watchers dynamically per project, so the client needs a real file watcher with glob matching and debounce, or it silently misses build file changes); jdt.ls launch details (`-data` workspace directory, `initializationOptions`, `workspaceFolders`) belong to #20 but the smoke needs them; "still indexing" status handling (see the `language/status` note above).

**Effort estimate: ~1-1.5 dev-weeks for the typed layer, protocol tests and the real jdt.ls smoke (initialize/sync/diagnostics/hover/definition) in `vintelli-lsp`, on top of #19 (which ACP needs regardless).** The comparable LSP4J path is 1-2 days of metadata wiring plus the same jdt.ls smoke plus the client code on top of LSP4J (roughly 2-4 days), so the one-time gap is closer to one week than to two. The hand-rolled path is one-time, matches the blocking-bean design (each request is a synchronous method, notifications are direct consumers of the shared transport, cancellation is one transport-level `$/cancelRequest`), and keeps the reflection-free rule intact everywhere.

**JSON mapping (open question).** The doc does not yet say how a record becomes JSON, and that decides whether reason 1 holds. Epic #6 says "Jackson records for messages", and Jackson (like Gson) needs reflection registration for records in a native image. Quarkus registers types automatically only where it can see them (e.g. REST endpoints); otherwise `@RegisterForReflection` is needed. Either (a) records are mapped through a JSON tree API with hand-written codecs (reflection-free, more code, which belongs in the effort estimate), or (b) Jackson records use build-time registration (cheap, but then the real difference from LSP4J is the 480-class tree versus a handful of records, plus not carrying a second JSON stack). #19's criterion is "no reflection configuration needed in native image"; #11's is the weaker "no reflection configuration vintelli-specific beyond upstream libs".

## Risks and open questions

- Union types (`Hover.contents`, definition `Location | Location[] | LocationLink[]`, `DocumentSymbol` vs `SymbolInformation`, `CompletionItem.documentation`, the completion result (`CompletionItem[] | CompletionList`), `CompletionItem.textEdit` (`TextEdit | InsertReplaceEdit`), the code action result (`(Command | CodeAction)[]`), `Diagnostic.code` (`integer | string`) and the `ServerCapabilities` providers (`boolean | options`, `textDocumentSync` as number or options)) are the single biggest source of hand-rolled client bugs; the record surface must model the variants jdt.ls actually emits, keep a raw-JSON fallback, and gain protocol-level integration tests against a real jdt.ls (not just the echo server).
- Missed message details: params omitted when empty (LSP4J fixed exactly this in 1.0.0 via [PR #885](https://github.com/eclipse-lsp4j/lsp4j/pull/885), which is evidence of how easy it is to get wrong), null handling, `initialized` notification order, registerCapability timing. Mirror jdt.ls and lsp4j test cases; LSP4J's `CHANGELOG.md` doubles as a checklist of protocol pitfalls implemented by others.
- Effort figures are estimates based on the measured surface, not from a build. No native minimal-client build was produced on either side in this spike.
- Rollout cost grows with scope: rename/refactoring (`WorkspaceEdit` with `documentChanges` + metadata), semantic tokens, inlay hints would inflate the surface well past the point where writing records is cheap (out of 0.1.0 scope per epic #15).
- Own-client defect rate in LSP protocol nuances (cancellation semantics, params omission when null, null vs absent, sync kind per server) could turn into a standing bug stream against #20; two rounds of bespoke fixes should trigger the LSP4J re-evaluation criteria below.
- Transport: #19 is stdio, but spike #22 still lists the `intellij-server` transport (stdio or socket) as open. If `intellij-server` requires a socket transport, #19 needs a socket mode.
- JSON layer: the JSON mapping question above is open; the effort estimate must state which layer it assumes before #20 starts.

## Criteria to revisit

Reconsider LSP4J (or switch modules wholesale) when any of these flips:

1. Upstream native support lands: LSP4J either ships bare reachability metadata with the jar, or ships DTOs that can be (de)serialized without Gson reflection (the proxy-free `Endpoint`/`RemoteEndpoint` layer already exists, so proxies alone are not the blocker). Track #349 and the jsonrpc package docs after each 1.x minor release.
2. intellij-server (spike #22) demands capability types whose union modeling costs more than a full LSP4J bridge, e.g. `WorkspaceEdit` with `documentChanges`/`AnnotatedTextEdit` plus semantic tokens, inlay hints, or codeLens flows needed early. Re-cost the record surface at that point: above ~40 methods with heavy unions, LSP4J parity becomes the cheap move, and the ~30-method estimate inside `vintelli-lsp` no longer holds. Track record-class growth in `vintelli-lsp` per capability and revisit if hand-rolled additions exceed ~1 dev-week per new capability family.
3. Own-client defect rate in LSP protocol details turns into a standing bug stream against #20: two such rounds of bespoke fixes should trigger the LSP4J evaluation rather than a third.
4. ACP arrives on the shared transport: if #6 pulls the transport in a direction that distorts LSP framing or typing (framing is shared surface, typed layer is not), a hybrid (LSP4J DTOs over our own framing) removes the proxies but keeps Gson and the DTO reflection registration, so it only helps if the reason for revisiting is typing cost, not native-image cost. Evaluate it as one spike-week before any rewrite decision.

## Next steps

1. Move the practical output to #20 task one: implement a minimal `vintelli-lsp` client (initialize + sync + publishDiagnostics) on the vintelli-jsonrpc transport.
2. Pass the #11 native smoke with that client, verifying that the native build has no reflection or proxy registration (hand-written, annotation-driven or Quarkus-generated) for `vintelli-jsonrpc` and `vintelli-lsp` types, and that an LSP session against jdt.ls runs in the native binary.
3. Model the record types for the ~30-method 0.1.0 surface, starting with the union variants jdt.ls actually emits, plus one generic raw-JSON escape hatch.
4. Add protocol-level integration tests against a real jdt.ls (initialize/sync/diagnostics/hover/definition), mirroring jdt.ls and lsp4j test cases and pitfalls from LSP4J's `CHANGELOG.md`.
5. Track record-class growth in `vintelli-lsp` per capability and the revisit criteria above (upstream #349, intellij-server #22 union needs, defect rate, ACP transport direction).

## Links

- LSP4J 1.0.0 release: https://github.com/eclipse-lsp4j/lsp4j/releases/tag/v1.0.0
- Maven Central, LSP4J artifacts: https://repo1.maven.org/maven2/org/eclipse/lsp4j/ (sizes measured against the 1.0.0 jar files, 2026-10-28)
- Upstream native-image metadata request (open): https://github.com/eclipse-lsp4j/lsp4j/issues/349
- jdt.ls client-facing calls (verified in source): https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/main/org.eclipse.jdt.ls.core/src/org/eclipse/jdt/ls/core/internal/JavaClientConnection.java
- Real LSP4J native packaging case study: https://github.com/pngdeity/aur/blob/main/TASKS.md (section 14c)

Verification note: version numbers, jar sizes, reflection/Proxy class counts, Gson POM range, jdt.ls server-to-client method names come from downloaded artifacts and `main` branches on 2026-10-28. Effort figures are estimates based on the measured surface, not from a build.

## Follow-up issues

Work not executed in this spike is tracked in #47.
