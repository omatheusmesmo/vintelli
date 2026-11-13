# Native image

Building the native image, the native integration tests and the debug endpoints they use.

The project is kept reflection-free (final beans, no client proxies) so it is
native-ready. Verify with the `native` profile and Mandrel 25 from SDKMAN:

```bash
./mvnw package -Dnative
```

The native runner is written to `vintelli-app/target/`.

## Reflection registrations

Vintelli ships no `reflect-config.json` and no `native-image.properties` of its
own. Every registration is a build step of `vintelli-core/deployment`, selected
from the Jandex index, so a library upgrade is picked up by the next build:

| Build step | What it registers | Why |
|---|---|---|
| `AcpClientProcessor` | the `AcpSchema` types of `acp-core` | the SDK (de)serializes its wire types through reflection |
| `McpClientProcessor` | the `McpSchema` wire types | same, for the MCP Java SDK |
| `LspClientProcessor` | the LSP4J protocol types, and its service interfaces as proxy definitions | LSP4J builds its JSON-RPC proxies and messages reflectively |
| `AeshCommandProcessor` | the AEsh metadata registries, terminal providers and `AeshConsoleRunner$ExitCommand` (constructors), plus the AEsh discovery resources | AEsh discovers them through the service loader |
| `JGitProcessor` | the JGit types it needs, the JGit message bundle, and `WindowCache`, the digest auth class and `FileUtils` initialized at run time | JGit loads them reflectively and reads its messages from a bundle |
| `ShellThemeProcessor` | `ShellInspectResource$ThemeRejected`, and the theme stylesheets as resources (`dev/omatheusmesmo/vintelli/tui/themes/*.tcss`) | the payload is an untyped `Response` entity the build cannot see |

Everything except `ThemeRejected` is a type of an upstream library. `ThemeRejected`
is the only Vintelli-owned type registered for reflection.

Integration tests (`*IT`) run against the native runner in CI with
`./mvnw verify -Dnative -Dquarkus.native.container-build`. The test launchers
start their HTTP fixture with `serve`; the TUI startup test separately checks
both the no-argument default and the `--tui` alias through tape playback, and
`VintelliTuiResizeIT` resizes a real pseudo terminal to prove that `SIGWINCH`
handling is safe in the native image.
`GET /tui/render`
renders one offscreen frame of the live shell through the CSS style engine
and returns the tool window tab row, the editor area, the status row and the
active theme as JSON; it exists so the render pipeline can be validated on
the native image and is gated behind `vintelli.inspect.enabled` (read-only
and offscreen, never driving the TUI event loop). The theme stylesheets are
resources of the image, included by the `ShellThemeProcessor` build step of
`vintelli-core/deployment`.

`/idea/reconnect`, `/idea/tools` and `/idea/tool?name=get_...` expose the IDEA
MCP client over HTTP so the native ITs can drive it against an in-process twin
of the IDEA server. They answer 404 unless
`vintelli.idea.debug-endpoint.enabled=true` and only call read-only tools whose
name matches `get_[a-z_]+`.

`/acp/probe/session` and `/acp/probe/mcp` expose the ACP SDK client probe in
the same way (issue #132): they drive a full client session over stdio against
a tiny echo agent configured with
`vintelli.acp.probe.agent-command` and `vintelli.acp.probe.agent-args`, and
answer 404 unless `vintelli.acp.probe.enabled=true`.
