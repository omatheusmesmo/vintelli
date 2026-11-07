# Native image

Building the native image, the native integration tests and the debug endpoints they use.

The project is kept reflection-free (final beans, no client proxies) so it is
native-ready. Verify with the `native` profile and Mandrel 25 from SDKMAN:

```bash
./mvnw package -Dnative
```

The native runner is written to `vintelli-app/target/`.

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
