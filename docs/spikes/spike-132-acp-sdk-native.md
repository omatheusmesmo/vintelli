# Spike 132: the ACP Java SDK as a native image client

Part of #15. Blocks #6. The project already decided to reuse libraries instead of hand-writing protocol code (#20, #23, the rework findings of #129): for ACP the choice was the official Java SDK, `com.agentclientprotocol:acp-core` + `acp-json-jackson3` (both Apache-2.0, version 0.80.0 from Maven Central). The SDK's own Quarkus extension (`acp-quarkus` 0.80.0) states "JVM mode; native images are not supported in 0.80.0", so the extension is not adoptable as is. This spike measures whether `acp-core` can serve as the native image client of Vintelli without the extension.

Everything below is a measurement unless marked as a hypothesis. The probe ran inside the Quarkus application (`vintelli-app`), through a config-gated HTTP endpoint (`AcpProbeResource`) that drives the SDK client, with the agent replaced by a twin: `EchoAcpAgent`, a minimal stdio agent built on the SDK's own agent side, spawned by the application as a separate process. The twin is the only JVM-only part of the probe; the client runs inside the artifact under test (the Mandrel native image in the `build-native` CI job, which is what supplies the native measurement).

## Decision

**GO** (final; the `build-native` CI job of this PR, Mandrel `25.0.4.1` JDK 25 in the `ubi9-quarkus-mandrel-builder-image:jdk-25` container, passed with the probe included, see the Native section below). The SDK is adoptable as a native image client of Vintelli without its `acp-quarkus` extension: `AcpClientProducers` in `vintelli-core` builds the JSON mapper explicitly and `AcpClientProcessor` in `vintelli-core/deployment` keeps the `AcpSchema` wire types reachable with `ReflectiveClassBuildItem` from the `CombinedIndexBuildItem` (no `@RegisterForReflection`, no hand-written reflection config). The GO covers the stdio happy path only. The coverage gaps listed below are not decided by this PR.

Dispositions:

- **#6**: proceeds on the SDK. The SDK does not check the protocol version of the `initialize` response and does not gate MCP servers by the agent's capabilities (measured in the bytecode, see Findings). Both checks are implemented in #145 as `AcpAgentGate`, so #6 reuses them instead of writing its own.
- **#19** and **PR #129**: on hold, not merged. Close both as superseded once the #145 items that matter to #6 and #8 pass natively (client handlers including permission, the other `session/update` kinds, and the foreign agent `opencode acp` from `@opencode/cli@2.1.4`): the ACP consumer moves to the SDK, and the LSP consumer uses LSP4J (#20, spike 23), so the shared transport would have no consumer. Carry the #129 review lessons (byte-exact framing, ordered dispatch, process shutdown escalation) into a note and keep the branch. If #145 finds a blocker, the #129 transport is the fallback and stays open.
- **#7**: not blocked by this spike and not changed by it.
- **#8**: not blocked by this spike, but it depends on the foreign-agent check in #145.

The fallback transport from the #129 rework stays open for what the SDK does not cover (see the coverage gaps in this document).

## What was measured and how

The probe drives a full ACP session over stdio, every phase named in the endpoint answer:

- `initialize`: `AcpSyncClient.initialize()` with `clientInfo` `vintelli 1.0.0`.
- `session/new`: `newSession(NewSessionRequest)`.
- `session/prompt`: a prompt the twin answers with one `AgentMessageChunk` and `end_turn`; the wire check is that the `session/update` notification arrived through `sessionUpdateHandler` and `stopReason` came back.
- `session/cancel`: a second `newSession`, a prompt whose twin waits for the cancel notification, `client.cancel(CancelNotification)` from the caller, expected `stopReason` `cancelled`.
- `session/close`: `closeSession(CloseSessionRequest)`, answered by the twin.
- `client/close`: sync client close driving the stdio transport down.
- `mcp-session` (separate endpoint `POST /acp/probe/mcp`): `newSession` with an `McpServerStdio` in the request; the twin refuses MCP servers with a JSON-RPC error, so the measured observable is the `AcpError` the client surfaces.

The `@QuarkusIntegrationTest` conventions of the integration tests apply: `VintelliAcpClientIT` and `VintelliAcpProbeDisabledIT` are `@QuarkusIntegrationTest` classes, run by the `build-native` CI job against the native runner (and locally against the JVM artifact with `-DskipITs=false`).

## Findings

### The SDK's ServiceLoader lookup of the JSON mapper is a hazard, and explicit wiring avoids it

- `acp-json-jackson3` registers its mapper through `META-INF/services/com.agentclientprotocol.sdk.json.AcpJsonMapperSupplier` (`Jackson3AcpJsonMapperSupplier`, priority -100). `AcpJsonMapper.createDefault()` resolves that file through `ServiceLoader`; transports' constructors without an explicit mapper call it (`StdioAcpClientTransport(AgentParameters)`; measured by reading the bytecode of 0.80.0). The system property escape hatch is `acp.json.mapper.supplier`.
- Measured fix on the JVM: `StdioAcpClientTransport(AgentParameters, AcpJsonMapper)` takes the mapper explicitly, and `AcpClientProducers.acpJsonMapper()` builds it from a direct `new Jackson3AcpJsonMapperSupplier().get()`. No `ServiceLoader.load` runs in the client path. The mapper's own version check (`Jackson3Versions.requireSupported`) reads compile-time `PackageVersion` constants (read from bytecode, not the classpath), so it is native-degradable.
- Native (measured, see the Native section): the `ServiceLoader` path never ran in the native client, so it produced no failure. Whether `createDefault()` would fail under `-H:-UseServiceLoaderFeature` with no supplier found is not measured, because nothing in the probe calls `createDefault()`; whether the runtime image needs the service file retained is therefore also not measured.

### Version coexistence with the Quarkus BOM (measured in the Quarkus application on the JVM)

Resolved versions of the app build (`dependency:build-classpath` of `vintelli-app`):

| Artifact | Version | Who picked it |
| --- | --- | --- |
| `tools.jackson.core:jackson-core` | 3.1.7 | the SDK's own `tools.jackson:jackson-bom` (Quarkus does not manage `tools.jackson`) |
| `tools.jackson.core:jackson-databind` | 3.1.7 | the SDK's own `tools.jackson:jackson-bom` |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.21 (2.21.x line) | the Quarkus BOM |
| `com.fasterxml.jackson.core:jackson-core`, `jackson-databind` | 2.21.7 | the Quarkus BOM (still Jackson 2, used by `quarkus-rest-jackson`) |
| `io.projectreactor:reactor-core` | 3.8.7 | the Quarkus BOM (the SDK builds against reactor-bom 2025.0.7, the same 3.8.x line) |

Jackson 2 and Jackson 3 share a classpath without conflicts (including the `jackson-annotations` jar, which Jackson 3 reads through its 2.x compatibility line), and Jackson 3 only on the client side: the HTTP endpoints serialize plain records with Jackson 2 and never expose `AcpSchema` types. The SDK's JVM floor (`jackson-core` and `jackson-databind` 3.0.0+) is met by the resolved 3.1.7.

### Full session on the JVM (measured)

`EchoAcpAgent` twin, all phases:

```text
state=OK
initialize:protocolVersion=1
session/new:OPENED
session/prompt:end_turn:STREAMED
session/cancel:cancelled
session/close:CLOSED
client/close:CLOSED
mcp-session:REFUSED:code=-32602
```

- The protocol version the agent answers is 1 and the client accepts the session afterwards. This is a happy-path observation only.
- Measured in the `acp-core` 0.80.0 bytecode: the client performs no protocol version check of the `initialize` response. The `initialize` call sends the request and builds the `NegotiatedCapabilities` from the response, without comparing the agent's version with the one requested. The client therefore accepts whatever version the agent answers. The probe did not exercise a mismatched version offer. Implemented in #145 (`AcpAgentGate`).
- The `StopReason` record stringifies to the wire value (`end_turn`, `cancelled`), which the probe reply records verbatim.
- The `-32602` (`INVALID_PARAMS`) `AcpError` is the twin's `AcpProtocolException(INVALID_PARAMS, "the echo agent accepts no MCP servers")` surfaced through the client. It is the agent's refusal, not a client obligation.
- Measured in the `acp-core` 0.80.0 bytecode: `AcpAsyncClient.newSession` gates only on `additionalDirectories` (through `withDirectories`). `NegotiatedCapabilities.supportsMcpHttp()` and `supportsMcpSse()` exist, but no client code calls them, so the client sends MCP servers over HTTP and SSE without checking the agent's capabilities. The probe uses stdio MCP servers, so only the agent's own refusal is observed. Implemented in #145 (`AcpAgentGate`).

### Native (measured, CI run of this PR)

The `build-native` job (`./mvnw verify -Dnative -Dquarkus.native.container-build`, Mandrel `25.0.4.1` on JDK 25) built the image and ran `VintelliAcpClientIT` (2 tests) and `VintelliAcpProbeDisabledIT` (1 test) against the Mandrel runner with `-H:-UseServiceLoaderFeature` in effect:

- Measured no native failure at all. The ServiceLoader path did not run in the client (nothing in the client path calls `createDefault()`, the mapper comes from the explicit producer). Reflection: the `AcpSchema` registration build step was in place from the first native run, so the image was never built without it. The registration is now measured as necessary (#145, experiment on a throwaway branch with only the `AcpSchema` registration removed): the native build still succeeds, but every `initialize` and session call of the probe client times out after 20 s with `AcpTimeoutException`, because the responses cannot be read into the `AcpSchema` records. The `AcpClientProcessor` registration stays.
- The probe client inside the native binary spawned the `EchoAcpAgent` twin process, ran the full session and closed it cleanly (`ACP agent starting`, `ACP agent started`, `agent process stopped (exit code 0)` for each endpoint call).
- The IT assertions passed natively, which pins the phase measurements of the full session section to the native image too: `initialize:protocolVersion=1`, `session/new:OPENED`, `session/prompt:end_turn:STREAMED`, `session/cancel:cancelled`, `session/close:CLOSED`, `client/close:CLOSED` and `mcp-session:REFUSED:code=-32602`. The `mcp-session` label was named `mcp-gating` in the native run; it was renamed afterwards because the old label implied a client-side gate. The observable is unchanged, and the final head of this PR is validated by its own CI run.
- No `vintelli-core/deployment` step beyond `indexAcpCore()` and `acpSchemaTypes(...)` was needed. Nothing was added to silence a build failure; the only fix during the spike was application-side (probe config wiring through `@ConfigMapping` after `@ConfigProperty` with an empty default failed Quarkus startup in the first CI run), not native.

Follow-up issue from this spike, covering its coverage gaps: #145 (see Coverage added by #145).

## Coverage added by #145

Measured natively on the `build-native` job of the #145 PR (`VintelliAcpClientIT`, `VintelliAcpVersionMismatchIT`, `ForeignAcpAgentContractIT`), with the stdio twin and with a foreign ACP agent installed from npm at a pinned version:

- Session updates: the client receives and types agent thought, agent message, tool call, tool call update and plan updates. `MCP server updates` are not a session update kind in the ACP schema of acp-core 0.80.0, so there is nothing to stream for them; unknown kinds are not asserted natively.
- Client handlers served to the agent: permission (`AcpProbePermissionPolicy`, allow-once first, reject-once fallback), `fs/read_text_file` and `fs/write_text_file` (`AcpWorkspace`, confined to the session root, symbolic links compared by real path), terminals (`AcpTerminalRegistry` on `ProcessBuilder`, bounded by a server-capped byte limit (1 MiB maximum)), elicitation in URL and form mode.
- SDK build contract: `AcpClient.sync(...).build()` throws when a capability is advertised without its handler (read, write, terminal, elicitation), and builds when none is advertised.
- Protocol version: the client refuses an `initialize` answer whose version is not 1 (`AcpAgentGate`) before any session opens. Unit tests cover missing, 0, 2 and 1; natively the twin answers 2 and the client refuses.
- MCP transports: the client refuses an HTTP or SSE MCP server that the agent does not advertise, before sending it (`AcpAgentGate`). Natively the twin advertises HTTP only, so SSE is refused locally and HTTP is accepted.
- Foreign agent: the handshake (initialize, session/new, session/close) and the MCP gate pass against `opencode acp` from `@opencode/cli@2.1.4` natively.

## What the probe does not cover

- StreamableHttp and WebSocket transports. The issue makes them conditional on #6 needing them; #6 spawns agents over stdio, so they are not tested.
- Prompt turns against the foreign agent: the CI job has no model credentials, so only the handshake and the MCP gate run there. The prompt flow is proven against the twin.
- Protocol versions other than 0, 1 and 2: the gate compares for equality with 1, so any other value takes the same path as the tested ones.
- Threading beyond the probe's own cancel: the cancel is issued from the caller thread only.

## Follow-ups

- #145 closed the coverage gaps above for #6's adoption of the SDK. The protocol version check and the MCP capability check that acp-core does not perform are implemented in #145 (`AcpAgentGate`), so #6 reuses them rather than writing its own.

## Transport lessons carried from the #129 review

The #129 rework was not merged, but its review lessons remain useful for
any future transport work (including the fallback transport). They are
recorded here so #19 and PR #129 can be closed once this note exists:

- Byte-exact framing: frame boundaries must be computed from byte counts,
  not character or line counts, so multibyte payloads cannot desynchronize
  the stream.
- Ordered dispatch: responses and notifications must be dispatched in the
  order they are read, with no reordering across threads.
- Process shutdown escalation: stop a child process with a graceful signal
  first, then escalate to a forced kill after a timeout, and never leave an
  orphaned process tree.
