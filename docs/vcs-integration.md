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
and checks out an existing local branch. Each capability call requires an
explicit confirmation flag. The TUI asks for confirmation before invoking it;
headless probe endpoints are disabled unless
`vintelli.vcs.debug-endpoint.enabled=true` and also require `confirm=true`.
Branch switching does not force checkout, and paths are resolved under the
configured project root before JGit sees them.

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
