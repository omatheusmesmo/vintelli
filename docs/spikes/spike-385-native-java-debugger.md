# Spike 385: native Java debugger architecture

Part of [#276](https://github.com/omatheusmesmo/vintelli/issues/276), following the
JDWP launch workflow in [#275](https://github.com/omatheusmesmo/vintelli/issues/275).
The reproducible applications are in [debugger-native](debugger-native/README.md),
outside Vintelli's production reactor.

## Decision

Use a typed DAP provider with Java Debug hosted by the existing jdtls profile.
Keep JDI outside Vintelli's native executable. The corrected LSP4J binding passed
an actual native session against a JVM adapter twin; the direct JDI bootstrap
failed in both measured native configurations. This selects the integration
architecture, not a completed debugger. The real Java Debug contract and the
production provider remain in #386.

## Environment and scope

Measured locally on 2026-11-10, Linux x86_64, Quarkus 3.40.1.1 and LSP4J 1.0.0.
The configured SDKMAN directory is named `25.0.2.r25-mandrel`, but its `release`
file and `java -version` identify Temurin HotSpot 25.0.2+10. That JVM ran Maven,
test resources and external debuggees. The container native builder reported
Mandrel 25.0.5.0 / JDK 25.0.5+8-LTS, using
`quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25`, digest
`sha256:51015eb067f6dd03090473eab26cac1750cbae6dcc813e37179ad03eb7218e36`.

Each module runs both `@QuarkusTest` and `@QuarkusIntegrationTest`; native ITs
launch the produced executable. No test was skipped or changed to accept a failed
session. These are bounded architecture probes, not throughput or startup
benchmarks. They do not exercise a TUI.

## Measured results

| Experiment | Packaged JVM | Native executable |
| --- | --- | --- |
| Public JDI bootstrap, default configuration | Passed | Compiled; failed before attach: `no Connectors loaded` |
| Public JDI bootstrap, automatic native service loading | Same Java code | Compiled; connector instantiation failed before attach |
| Typed LSP4J DAP, initially declared binding slice | Passed | Compiled; reader failed while decoding `initialize` response |
| Typed LSP4J DAP, recursive DTO binding | Passed | Passed: initialization, attach, stopped event, threads, disconnect |

The JDI JVM probe attached to a real suspended Java process, prepared its class,
set a breakpoint in `checkpoint`, read parameter `value=21`, stepped over the
assignment, and read local `doubled=42`. It also read the frame method/location
and listed threads including `main`.

With Quarkus's default native service loading, JDI reported five connector
providers as not found. Enabling
`quarkus.native.auto-service-loader-registration=true` made those providers
visible but unable to instantiate. Both executions ended in
`Bootstrap.virtualMachineManager()` with `no Connectors loaded`. The experiment
uses public JDI APIs and no non-exported JDK implementation classes. This is a
failure of the measured configurations, not proof that JDI cannot work in native
image.

The DAP twin runs in the test JVM. The typed client completed `initialize`,
`attach`, an unsolicited `stopped` event, `threads` and `disconnect` on the JVM.
It does not implement Java Debug semantics: its stopped event and thread list
are fixed responses.

The first native DAP result appeared as a 3-second `initialize` timeout. Capturing
the wire showed a valid request and response. Inspecting the completed reader
future exposed the cause: `DebugEnumTypeAdapter` called `Class.getField("MD5")`
and failed with `NoSuchFieldException` while Gson built the `Capabilities`
adapter. The initially registered top-level DTOs did not cover the nested enum
graph. An apparently unused nested enum is still visited during adapter creation.

The correction uses Quarkus `@RegisterForReflection(registerFullHierarchy=true)`
for the binding graph rooted at the DAP service interfaces and the exercised DTOs.
It does not special-case `MD5` or increase a deadline. A Luna agent implemented
the correction and a separate Luna agent ran the validation. The final DAP runs
both ended in `BUILD SUCCESS`: one JVM test and one packaged JVM IT, then one JVM
test and one IT against the native executable, with no failures, errors or skips.
No hierarchy-registration warnings were observed. This proves the exercised
protocol slice, not every DAP operation or enum variant.

## Native binding requirements

LSP4J uses dynamic service proxies and Gson reflection by design. Vintelli already
supports that library through `LspClientProcessor`, which derives registrations
from the Jandex index and registers the required service proxy interfaces.
A DAP provider must extend that mechanism to the debug/jsonrpc-debug artifacts,
`IDebugProtocolServer` plus `Endpoint`, and the client callback implementors.
A handful of top-level DTO names is not sufficient evidence of full binding
coverage. Any unavoidable native binding belongs beside this feature and must be
covered by native protocol tests.

The experiment uses a dedicated virtual-thread executor, closes its socket,
cancels its reader and shuts down the executor. This avoids relying on the
default LSP4J cached pool's lifetime. Reader failures are attached to request
failures so a missing binding is not recorded merely as a timeout.

## Integration route considered

[Java Debug](https://github.com/microsoft/java-debug) is a DAP adapter based on
JDI, running as a jdtls extension. Its documented bootstrap supplies the plugin
jar in `initializationOptions.bundles`, then executes
`vscode.java.startDebugSession` to obtain the adapter's listening port.
Vintelli's `LspProfileConfig` and `LspServerSession` already accept and forward the
raw initialization options. The command/bootstrap bridge and adapter session
lifecycle still need implementation.

Keeping Java Debug in the external JVM would keep its JDI implementation outside
Vintelli's native executable. This is an architectural inference, not a native
contract test against Java Debug. A new optional `DebugCapability` should expose
Vintelli-owned records and session operations, keeping SDK types inside the
provider, and report why it is unavailable when the bundle/profile is missing.

References: [JDI Java25](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jdi/module-summary.html),
[LSP4J typed DAP bootstrap](https://github.com/eclipse-lsp4j/lsp4j/blob/main/documentation/README.md),
[Quarkus native configuration](https://quarkus.io/guides/building-native-image),
and the existing [LSP4J decision](spike-23-lsp4j-vs-own-jsonrpc-client.md).

## Not verified and follow-ups

Every remaining item is tracked under the same parent epic, which currently has
no milestone:

- Production `DebugCapability`, jdtls/Java Debug bootstrap, a real-service opt-in
  contract, complete session cleanup, the complete DTO surface and failure-mode
  twins: [#386](https://github.com/omatheusmesmo/vintelli/issues/386).
- Gutter breakpoint interaction, verification/rejection and project persistence:
  [#387](https://github.com/omatheusmesmo/vintelli/issues/387).
- Step into/out and other execution controls, source navigation and the editor's
  current-line marker: [#388](https://github.com/omatheusmesmo/vintelli/issues/388).
- The Debug tool window, interactive threads, stacks and expandable variables:
  [#389](https://github.com/omatheusmesmo/vintelli/issues/389).
- Expression evaluation semantics and conditional breakpoints:
  [#390](https://github.com/omatheusmesmo/vintelli/issues/390).
- Underlying native JDI connector-constructor causes and a possible public-API
  native solution: [#391](https://github.com/omatheusmesmo/vintelli/issues/391).
- Other supported platforms, architectures, JDKs and library upgrade stability:
  [#392](https://github.com/omatheusmesmo/vintelli/issues/392).

The wrapper twin and real `jdb` tests in #275 prove the preceding debug-launch
workflow. They do not substitute for any of these full debugger checks. No issue
in the epic is closed merely by this document; the spike's deliverable closes
only #385 when merged through its closing keyword.

## Validation record

Commands are in [the experiment README](debugger-native/README.md). The local
logs named below are not required to reproduce the experiments and are not
versioned; the commands, failure text and results above are the durable record.

| Run | Log | Result |
| --- | --- | --- |
| JDI JVM after the event-resume fix | `/tmp/vintelli-276-jdi-jvm-retry.log` | Success |
| JDI native defaults | `/tmp/vintelli-276-jdi-native.log` | Negative: connectors not found |
| JDI native automatic services | `/tmp/vintelli-276-jdi-native-services.log` | Negative: connector instantiation |
| DAP JVM initial slice | `/tmp/vintelli-276-dap-jvm.log` | Success |
| DAP native initial slice | `/tmp/vintelli-276-dap-native.log` | Negative: request timeout |
| DAP native wire/reader diagnosis | `/tmp/vintelli-276-dap-native-diagnostic.log` | Negative: missing enum field metadata |
| DAP recursive binding JVM | `/tmp/vintelli-276-dap-hierarchy-jvm.log` | Success |
| DAP recursive binding native | `/tmp/vintelli-276-dap-hierarchy-native.log` | Success |
