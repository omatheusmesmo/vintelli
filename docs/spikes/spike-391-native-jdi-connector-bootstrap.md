# Spike 391: native JDI connector bootstrap cause

Investigates [#391](https://github.com/omatheusmesmo/vintelli/issues/391), an alternative
to the JDI rejection recorded in [spike 385](spike-385-native-java-debugger.md) and
[#276](https://github.com/omatheusmesmo/vintelli/issues/276). The reproducible
applications are in [debugger-native](debugger-native/README.md), outside Vintelli's
production reactor. No production code changed.

## Result

The `no Connectors loaded` error is a symptom. The JDK prints each connector
provider's construction failure and throws only when none survive. Two causes were
measured in the native executable, both build-time metadata gaps:

1. `ConnectorImpl.getString` and `SocketTransportService` call
   `ResourceBundle.getBundle("com.sun.tools.jdi.resources.jdi")`. The bundle is not
   in the image, so every provider constructor fails with `MissingResourceException`.
2. `EventRequestManagerImpl` sizes its request lists from
   `JDWP.EventKind.class.getDeclaredFields()` read reflectively. Without field
   metadata the highest event kind is 0, so attach fails with
   `ArrayIndexOutOfBoundsException` on `CLASS_PREPARE`.

With both entries, plus a classpath service registration for the five providers,
the full session passes in the native executable with Quarkus's default service
loader setting. The recorded negative configurations remain unchanged.

This is not a public-API-only solution in the strict sense. Vintelli code imports
only `com.sun.jdi` public types. The metadata names JDK-internal classes and a
non-exported resource bundle, so it depends on the JDK implementation layout.

## Environment

| Item | Value |
| --- | --- |
| Quarkus | 3.40.1.1 |
| Native builder | `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25`, digest `sha256:38f943afcae3218771546a99b41156dd3efa8315483eaf74f88f33d232bb3c8d`, Mandrel 25.0.5.0-Final, JDK 25.0.5+8-LTS (reported by the Quarkus build log) |
| Builder digest in spike 385 | `sha256:51015eb067f6dd03090473eab26cac1750cbae6dcc813e37179ad03eb7218e36` (the image has since changed) |
| Maven JVM runs | `JAVA_HOME` `25.0.2.r25-mandrel` (its `release` file reports Temurin 25.0.2+10) |
| Standalone probe JVM and builder | Mandrel 25.0.5.0 container; local `native-image` 25.0.2 (2026-01-20) as a second build |
| JDK source inspected | `jdk.jdi` from `lib/src.zip` of Temurin 25.0.5+8 |

## Cause chain

Measured on 2026-11-15 with the standalone probe in `jdi-connector`, built with the
25.0.5 container builder unless marked otherwise.

| Step | Configuration | Result |
| --- | --- | --- |
| A | Default service loader feature, no metadata | Five providers found. Each constructor fails: `Can't find bundle for base name com.sun.tools.jdi.resources.jdi`. Bootstrap: `no Connectors loaded` |
| B | Step A plus bundle included with `-H:IncludeResourceBundles` (experimental) | Bootstrap passes. Attach fails: `ArrayIndexOutOfBoundsException: Index 8 out of bounds for length 1` in `EventRequestManagerImpl.requestList` |
| C | Step B replaced by `resource-config.json` `bundles` and `reflect-config.json` `JDWP$EventKind` `allDeclaredFields`, auto-discovered from `META-INF/native-image` | Session passes: attach, breakpoint in `checkpoint`, `value=21`, step over, `doubled=42`, threads including `main` |
| D | Quarkus default feature setting (`-H:-UseServiceLoaderFeature`) plus the bundle and EventKind metadata | Five providers `not found`. Bootstrap: `no Connectors loaded` |
| E | Step D plus a classpath `META-INF/services/com.sun.jdi.connect.Connector` listing the five providers, and `allPublicConstructors` for them | Session passes. Also passes with local `native-image` 25.0.2 |

Step C corresponds to Quarkus with `quarkus.native.auto-service-loader-registration=true`.
Step D corresponds to the Quarkus default, which passes `-H:-UseServiceLoaderFeature`
(checked in `quarkus-core-deployment` 3.40.1.1 `NativeImageBuildStep`). The issue's
"not found" and "could not be instantiated" symptoms match D and A/C.

## Quarkus spike results

| Module | Mode | Result |
| --- | --- | --- |
| `jdi` (unchanged) | JVM | Pass (`JdiProbeTest`) |
| `jdi` (unchanged) | Native, default | Fail: IT logs `Provider ... not found`, then `no Connectors loaded` |
| `jdi-registered` | JVM | Pass (`JdiProbeTest` and packaged `JdiProbeIT`) |
| `jdi-registered` | Native, default | Pass: `BUILD SUCCESS`; `JdiProbeIT` executed the `-runner` executable (log: `native (powered by Quarkus 3.40.1.1) started`) and passed the attach, breakpoint, step and state-read session |

## Metadata and cost

Files, all next to the probe in `jdi-registered`:

- `META-INF/native-image/dev.vintelli/debugger-jdi-spike/reflect-config.json`:
  6 entries (`JDWP$EventKind` with all declared fields, five provider classes with
  all public constructors).
- `META-INF/native-image/dev.vintelli/debugger-jdi-spike/resource-config.json`:
  one bundle (`com.sun.tools.jdi.resources.jdi`) and one resource pattern (the
  services file).
- `META-INF/services/com.sun.jdi.connect.Connector`: five provider names.

Costs, measured:

- Native runner size: 53,451,880 bytes (`jdi-registered`) against 52,456,552 bytes
  (`jdi` control), about 0.99 MB (+1.9%).
- Native build peak RSS: 3.30 GB against 3.21 GB.
- Build time: 1m39s against 2m11s. The control overlapped a standalone build, so the
  times are not comparable.
- Global service loader feature: not enabled. The metadata is feature-owned, not
  global. The classpath services file was measured on the JVM: the probe lists
  five providers and five connectors with no duplicates, and the Quarkus JVM tests
  pass.
- The registered Quarkus build printed one deprecation warning about
  `DynamicProxyConfigurationResources`. The spike has no `proxy-config.json`, so this
  file set does not explain it, but the cause was not investigated.

## Reproduction

Commands are in [the experiment README](debugger-native/README.md#connector-bootstrap-probe-391).
The standalone commands were run as written against the local `native-image` 25.0.2
and passed. The container builds were run with the Quarkus command in the README.

## Not verified and follow-ups

- Other builder images such as the `ubi10` `jdk-25` image, other platforms (Windows
  shared-memory connectors are not part of this metadata), and JDK patch versions beyond
  25.0.2 (standalone) and 25.0.5 (Quarkus), including a JDK-version canary: #466.
- Per-entry minimality. The complete set was tested as a unit and only the services file
  was ablated (step D): #467.
- Failure matrix and real session semantics (timeouts, denied attach, dropped
  connections, a real Java Debug or jdtls session), the classpath services file in
  Quarkus dev mode and with other JDK `ServiceLoader` consumers: #468.
- The DAP route chosen in spike 385 was not changed or re-measured: #468.

## Recommendation

Keep the spike 385 decision: the DAP provider through jdtls and Java Debug keeps JDI
out of the native executable and needs no JDK-internal metadata. Adopt this metadata
only if the team later chooses in-process JDI. In that case, put the metadata next to
the debugger feature, add a native `*IT` that asserts bootstrap and attach, and add a
JDK-version canary that runs the probe on each supported JDK. Those follow-ups are
#466, #467 and #468.
