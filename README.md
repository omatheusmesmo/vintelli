# Vintelli

A terminal-native IDE experience for Java: IntelliJ intelligence, in your terminal, with zero IDE UI.

Vintelli is a Quarkus application that shows an IntelliJ IDEA-like TUI (built with TamboUI), drives IntelliJ IDEA headlessly through its MCP server, and hosts AI coding agents (OpenCode, Claude Code, pi) through the Agent Client Protocol (ACP).

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

- Java 25 (Eclipse Mandrel 25.0.2, pinned by `.sdkmanrc`)
- Quarkus 3.40.1.1 (build-time-first, no proxies/reflection unless required)
- TamboUI 0.6.0 (`tamboui-tui`, `tamboui-toolkit`, Markdown support, JLine backend)
- Quarkiverse MCP Server 2.0.4 (Vintelli as an MCP server for external agents)
- MCP Java SDK 2.1.0 (client: IntelliJ IDEA MCP server)
- AEsh (CLI/REPL capabilities) and WebSockets Next (browser surface)

## Run

Backend (headless, dev mode with live reload):

```bash
sdk env            # honors .sdkmanrc (Java 25)
./mvnw quarkus:dev
```

TUI (real terminal required):

```bash
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar --tui
```

Keys: `1-4` switch tool windows (Run, Terminal, Problems, ACP Agents),
`j/k` navigate the project tree, `q` quits.

## Native image

The project is kept reflection-free (final beans, no client proxies) so it is
native-ready. Verify with the `native` profile and Mandrel 25 from SDKMAN:

```bash
./mvnw package -Dnative
```

## Status

See the GitHub issues for the implementation plan (IDEA MCP integration, ACP
agents, offline inspections, WebSocket surface, native smoke test).
