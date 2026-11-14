# Run configurations

Vintelli lists application start commands before build phases, so Run can start
the application without typing its command in a terminal. Detection reads the
project's build descriptor once when the local provider is created. It does not
evaluate build scripts, resolve dependencies, run Maven or start a Gradle daemon.

| Build declaration | Configuration | Command appended to the build tool |
|---|---|---|
| Maven `quarkus-maven-plugin` in `build/plugins` | `maven:quarkus:dev` | `quarkus:dev` |
| Maven `spring-boot-maven-plugin` in `build/plugins` | `maven:spring-boot:run` | `spring-boot:run` |
| Maven `exec-maven-plugin` with `mainClass` or `exec.mainClass` | `maven:exec:java` | `exec:java` |
| Gradle `io.quarkus` plugin | `gradle:quarkusDev` | `quarkusDev` |
| Gradle `org.springframework.boot` plugin | `gradle:bootRun` | `bootRun` |
| Gradle `application` plugin | `gradle:run` | `run` |

The first detected application command is the default. Plain libraries keep
their build phases without acquiring an application start command. Maven
aggregators (`packaging=pom`) and plugins present only in `pluginManagement`
do not imply an executable application. The optional IDEA provider continues
to supply its own configurations through the same `RunCapability` contract.

An executable `mvnw` or `gradlew` in the project root takes precedence over
the installed build tool. On Windows, the corresponding `.cmd` or `.bat`
wrapper is used. Otherwise Vintelli invokes `mvn` or `gradle` from `PATH`.

## Select and run

Click the configuration name in the main toolbar to open the chooser. Use
Up/Down and Enter or click a configuration. Escape or a click outside closes
the chooser without changing the choice. Keyboard users can open the same
chooser through **Choose Run Configuration** in the command palette (`Ctrl+P`).

Selecting an entry does not run it. The toolbar and the Run tool window show
the same choice; press Run to start it and Stop to terminate its process tree.
The selected id is saved per canonical project root under
`<config-root>/vintelli/run-configurations/`, outside the checkout. A removed
configuration falls back to the first available entry.

The CLI uses the same provider:

```bash
vintelli run
vintelli run maven:quarkus:dev
vintelli run maven:spring-boot:run
vintelli run gradle:bootRun
```

For a provider that reports completion (including the local provider), the CLI
streams output until the process finishes and returns a failure when the build
fails, times out or is cancelled. Snapshot providers keep their existing
request/response behavior.

## Discovery limits

Maven descriptors are read with Apache Maven's model parser, at the version
managed by the Quarkus BOM. Discovery reads only the selected project root;
it does not construct an effective model, resolve remote parents, activate
profiles or guess an application module inside an aggregator. Open the
application module when the repository root is an aggregator.

Gradle discovery recognizes literal plugin declarations in `plugins` blocks
and `apply plugin` statements in Groovy and Kotlin build files. Version
catalog aliases, convention plugins, computed plugin ids and included scripts
are not evaluated. The Gradle Tooling API was considered: it always uses a
daemon and evaluates build configuration, which would make opening a project
start external tooling. See the [Gradle Tooling API](https://docs.gradle.org/current/userguide/tooling_api.html).

Unreadable, oversized or invalid descriptors keep the build phases and omit
application start commands. Maven DOCTYPE declarations are refused. Restart
Vintelli after changing plugin declarations.

## Validation

The Maven tool window separately reads root and child `pom.xml` files for
modules, profiles and declared plugin execution goals without running Maven.
It refreshes its tree when a descriptor changes; `r` also requests an
immediate refresh. Invalid descriptors show their parse reason in the window.

Unit tests cover Maven and Gradle detection, wrapper and installed-command
selection, plain libraries, plugin management, malformed input and descriptor
limits. Pilot tests drive toolbar and palette selection, Run window
synchronization, persistence, scrolling, resize and clicks outside the chooser.
Packaged-artifact tests run the discovered application goals through a wrapper
twin and inspect saved selection through the gated headless surface.

Quarkus guides: [Maven tooling](https://quarkus.io/guides/maven-tooling),
[Gradle tooling](https://quarkus.io/guides/gradle-tooling), and
[integration testing](https://quarkus.io/guides/getting-started-testing#quarkus-integration-test).
