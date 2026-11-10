# Native Java debugger experiments

Reproducible experiments for [#385](https://github.com/omatheusmesmo/vintelli/issues/385),
part of [#276](https://github.com/omatheusmesmo/vintelli/issues/276). These applications
are outside Vintelli's production reactor. Their loopback `/probe` endpoint is an
experiment, not the application's debugger API.

Use the SDKMAN JDK pinned at the repository root. Run from that root:

```sh
./mvnw -f docs/spikes/debugger-native/pom.xml -pl jdi verify
./mvnw -f docs/spikes/debugger-native/pom.xml -pl dap verify
./mvnw -f docs/spikes/debugger-native/pom.xml -pl jdi verify -Dnative -Dquarkus.native.container-build
./mvnw -f docs/spikes/debugger-native/pom.xml -pl jdi verify -Dnative -Dquarkus.native.container-build -Dquarkus.native.auto-service-loader-registration=true
./mvnw -f docs/spikes/debugger-native/pom.xml -pl dap verify -Dnative -Dquarkus.native.container-build
```

Each `verify` runs a `@QuarkusTest` and a `@QuarkusIntegrationTest` against the
packaged artifact. Run the modules separately: an expected negative result in one
experiment must not prevent the other experiment from being evaluated. Maven exit
status and failsafe results are part of the evidence; no tests are skipped or
converted to passing assertions when an architecture fails.

- JDI starts a real suspended JVM in the test process, attaches through the public
  JDI API, prepares a class, sets a breakpoint, reads a parameter, steps over its
  assignment, and reads the resulting local. The test resource terminates the JVM.
- DAP starts a JVM adapter twin and exchanges typed LSP4J requests, responses and a
  stopped event over a loopback socket. Its twin traces the fixed protocol traffic
  to help diagnose native binding failures. It is not Java Debug or jdtls.

The DAP probe declares its required proxy and binding types next to the experiment;
LSP4J uses dynamic proxies and Gson. This metadata is specific to the exercised
slice, not a claim of complete DAP schema coverage. JDI uses the public JDK API
without custom reflection metadata or imports of non-exported JDK implementation
classes. The services-enabled variant changes only Quarkus's documented native
service-loader setting.

The tests own bounded request/event waits and process cleanup. This is a happy-path
architecture comparison, not the production provider's complete failure matrix.
The [decision document](../spike-385-native-java-debugger.md) records results and links the remaining work.
