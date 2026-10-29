# Vintelli

A terminal-native IDE experience for Java: IntelliJ intelligence, in your terminal, with zero IDE UI.

Vintelli is a Quarkus application that shows an IntelliJ IDEA-like TUI (built with TamboUI) and hosts AI coding agents (OpenCode, Claude Code, pi) through the Agent Client Protocol (ACP). It works standalone with open tooling only. IntelliJ IDEA, driven headlessly through its MCP server, is an optional provider and is never required.

## Architecture

```
+-----------------------------------------------------------+
|                    Vintelli (Quarkus TUI)                 |
|  menu bar | project tree | editor | tool windows | status |
+------------------+--------------------+-------------------+
| MCP Java SDK     | ACP client         | AEsh CLI commands |
| (client -> IDEA) | (opencode/claude/pi)| (REPL capabilities)|
+------------------+--------------------+-------------------+
        |                     |                    |
        v                     v                    v
  IntelliJ IDEA         agent processes      Vintelli REPL
  MCP Server (SSE/HTTP streamable)        + browser surface (websockets-next)
```

## Stack

- Java 25 (Mandrel 25.0.2, the Red Hat build of GraalVM for native images, pinned by `.sdkmanrc`)
- Quarkus 3.40.1.1 (build-time-first, no proxies/reflection unless required)
- TamboUI 0.6.0 (`tamboui-tui`, `tamboui-toolkit`, Markdown support, JLine backend)
- Quarkiverse MCP Server 2.0.4 (Vintelli as an MCP server for external agents)
- MCP Java SDK 2.1.0 (client: IntelliJ IDEA MCP server)
- AEsh 3.18.6 (plain library; CLI/REPL capabilities) and WebSockets Next (browser surface)

## Run

Backend (headless, dev mode with live reload):

```bash
sdk env            # honors .sdkmanrc (Java 25)
./mvnw -pl vintelli-app -am quarkus:dev
```

TUI (real terminal required):

```bash
./mvnw package
java -jar vintelli-app/target/quarkus-app/quarkus-run.jar --tui
```

The application lives in the `vintelli-app` module; the other modules are
plain libraries built along with it.

`vintelli-core` is a Quarkus extension (parent `vintelli-core-parent` with
`vintelli-core/runtime` and `vintelli-core/deployment`) and the home of
Vintelli's build-time glue. Its first content is
the MCP client support: CDI producers for the MCP SDK JSON mapper and schema
validator (the SDK's `ServiceLoader` lookup does not work in a native image)
and a build step that registers the `McpSchema` types for reflection. Only
`vintelli-idea` and `vintelli-app` depend on it; `vintelli-api` and
`vintelli-jsonrpc` stay Quarkus-free.

Keys: `1-4` switch tool windows (Run, Terminal, Problems, ACP Agents),
`j/k` navigate the project tree, `q` quits.

## Native image

The project is kept reflection-free (final beans, no client proxies) so it is
native-ready. Verify with the `native` profile and Mandrel 25 from SDKMAN:

```bash
./mvnw package -Dnative
```

The native runner is written to `vintelli-app/target/`.

Integration tests (`*IT`) run against the native runner in CI with
`./mvnw verify -Dnative -Dquarkus.native.container-build`. `GET /tui/render`
renders one offscreen frame of the shell and returns the tool window tab row,
the main area top row and the status row as JSON; it exists so the render
pipeline can be validated on the native image and is read-only.

## Status

See the GitHub issues for the implementation plan (IDEA MCP integration, ACP
agents, offline inspections, WebSocket surface, native smoke test).

## License

Vintelli is licensed under the [Apache License, Version 2.0](LICENSE).
