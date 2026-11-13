# VCS integration decision (issue #122)

## Integration choice

Vintelli uses the `VERSION_CONTROL` capability port
(`VersionControlCapability`) backed by the **JGit library** inside the local
provider (`LocalVersionControlCapability`), not a `git` executable invoked
through `ProcessRunner`.

Reasons:

- JGit performs status, branch, ahead/behind and diffs in-process, with no
  dependency on a `git` binary being installed or on its version.
- It avoids forking a process per call and avoids parsing porcelain output,
  which keeps the behaviour deterministic across platforms.
- It is native-image friendly: the provider was validated under
  `./mvnw verify -Dnative -Dquarkus.native.container-build` (see
  `VintelliVcsRepositoryIT` and `VintelliVcsPlainRootIT`), with the
  repository and worktree access exercised inside the image.

The same provider stages and unstages project files, commits the staged index,
amends the current commit, and checks out an existing local branch. Each write
capability call requires an explicit confirmation flag. The TUI asks for
confirmation before invoking it; commits show a summary of staged files and
added/removed lines. Commit messages can span lines with `Shift+Enter`; `Tab`
toggles amend before confirmation. Git's configured `user.name` and
`user.email` are used when available. If either is missing, the TUI asks for
both values for that commit without persisting them. Headless probe endpoints are disabled unless
`vintelli.vcs.debug-endpoint.enabled=true` and also require `confirm=true`.
Branch switching does not force checkout, and paths are resolved under the
configured project root before JGit sees them.

## Headless write probe

The opt-in `POST /vcs/commit` endpoint accepts `{"message":"..."}` for a new
commit or `{"message":"...","amend":true}` to replace the current commit.
It is a test/debug surface, not a public Git API; it remains disabled unless
`vintelli.vcs.debug-endpoint.enabled=true`, and every write requires
`confirm=true`.

## Missing Git

When the project is not inside a Git worktree (or the repository cannot be
read), the provider reports `ProviderHealth.unavailable(...)` and every read
returns a failed `CapabilityResult`. The shell therefore surfaces an explicit
"Version control unavailable" state and the rest of the shell keeps working.

## Native image impact

JGit reflection and resource loading required for repository discovery are
covered by the integration tests that run against the native runner. No
reflection or resource configuration was added only to silence a failure: any
remaining need is kept next to the feature that requires it.
