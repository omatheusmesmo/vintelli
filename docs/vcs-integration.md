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

## Branch operations

The provider lists, creates, deletes and renames local branches. The TUI does
not expose these operations yet. The port declares them as default methods
that fail with an "unavailable" reason, so providers without branch support
keep compiling and answer clearly.

- `branches()` returns the local branches sorted by name. The checked out
  branch is marked current. While HEAD is detached no branch is marked, and
  `status()` reports the detached state.
- `createBranch(name, confirmed)` creates the branch at `HEAD` and leaves the
  current branch checked out. It fails for an existing name, an invalid name
  (the reserved `HEAD` included) and a repository without any commit.
- `deleteBranch(name, force, confirmed)` never deletes the checked out branch,
  even with `force`. A branch with commits that are not merged into `HEAD` is
  kept unless `force` is true.
- `renameBranch(oldName, newName, confirmed)` fails for a missing source and
  for an existing or invalid target. Renaming the checked out branch keeps it
  checked out under the new name.

Every write needs `confirmed=true`, and the check runs before the repository
is opened. Names are stripped of surrounding whitespace and then checked with
JGit's ref name rules.

## Remote operations

The provider fetches, pulls and pushes through JGit (`GitRemoteSync`). The TUI
does not expose these operations yet, and no progress is reported. The
methods return a short summary on success and a failed result otherwise.

- `fetch(remote, confirmed)` fetches one configured remote. Only the
  remote-tracking refs move; the worktree and the checked out branch do not.
- `pull(confirmed)` fetches the upstream of the checked out branch and
  integrates it: a fast-forward when possible, a merge otherwise. Rebasing is
  never chosen, whatever `pull.rebase` says. A conflicting merge fails with
  reason "The pull stopped on merge conflicts" and the conflicted paths in the
  detail. The merge stays in progress, as `git pull` leaves it, so the user
  resolves those paths and commits.
- `push(confirmed)` pushes the checked out branch to its upstream and never
  forces. A remote branch that holds commits the local branch lacks rejects the
  push, and the caller pulls first.
- `pushSetUpstream(remote, confirmed)` pushes the branch to the remote branch of
  the same name and records it as the upstream. The upstream is written only
  after the push succeeds.

Every call needs `confirmed=true`, checked before the repository is opened. A
missing remote, a detached HEAD and a branch without an upstream each fail with
an explicit reason. Messages never carry the userinfo of a remote URL. Vintelli
stores no credential: authentication is whatever JGit does by default, with no
credentials provider set. SSH remotes need the `org.eclipse.jgit.ssh.apache`
module, which the local provider does not declare yet, and private HTTPS
remotes have not been exercised.

Not covered: TUI controls, progress and cancellation, force push, pull with
rebase, tag management, submodules and the native image run. The tests use a
local bare repository on disk, so no network is involved.

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
