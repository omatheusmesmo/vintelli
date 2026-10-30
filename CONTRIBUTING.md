# Contributing to Vintelli

Vintelli is a standalone-first terminal IDE: a native image that runs with open
tooling only. IntelliJ IDEA and other external servers are optional providers and
are never required. Contributions are welcome, and this guide explains how to
get started, how to propose a change, and what the review bar is.

## Where to start

- Look for issues labelled `good first issue` or `help wanted`. The current
  milestone is `0.1.0`.
- Read the issue body and comments before coding. If an issue already has an
  open pull request, do not start a second one.
- To claim an issue, comment on it and wait for a maintainer reply before
  opening a pull request.
- Spikes live under `docs/spikes/`. Their deliverable is the recorded decision
  or findings document, not exhaustive experiments.

## Setting up

The JDK is pinned by `.sdkmanrc` (SDKMAN manages Java; `mise` does not).

```bash
sdk env            # honors .sdkmanrc (Java 25)
```

Build and run:

```bash
./mvnw -pl vintelli-app -am quarkus:dev          # backend, dev mode with live reload
./mvnw package
java -jar vintelli-app/target/quarkus-app/quarkus-run.jar --tui   # TUI (real terminal)
```

Run one module's tests:

```bash
./mvnw -pl <module> -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false
```

Native verification (run by CI on every pull request):

```bash
./mvnw verify -Dnative -Dquarkus.native.container-build
```

CI runs the full matrix: JVM tests on three operating systems and the native
image. Locally, a targeted test is enough.

## Workflow

- Work on a branch or a fork. Rebase only: no merge commits in a branch.
- One commit per pull request. Squash intermediate commits before pushing.
- Use conventional commits in commit messages only (`feat(tui): ...`). Pull
  request titles must be plain and descriptive with no `type(scope):` prefix;
  CI rejects prefixed titles.
- Reference issues with the exact keywords: `Closes #N`, `Fixes #N` or
  `Resolves #N` in the commit footer and the pull request description. Use
  `Refs #N` for partial progress. Never close an issue manually with a comment.
- Spike pull requests always use `Closes #N`, even when some experiments were
  not run. Anything not verified must be listed under a "Not verified" or
  "Follow-ups" section, and each item gets its own follow-up issue linked from
  that section.
- Do not bypass git hooks.

## Quality bar

Every change is expected to meet all of the following.

(a) Regression test. Every change ships a test that fails without the change.
    No trivial getter or setter tests.

(b) Native integration test. Any change that adds or alters runtime behaviour
    needs a `*IT` class annotated with `@QuarkusIntegrationTest` that runs in
    the native job and is observable through the headless surface (HTTP, the
    MCP endpoint, or a CLI command). Do not drive `--tui` from integration
    tests. External collaborators (IntelliJ MCP server, jdtls, agents) are
    replaced by twins: fake servers started by a
    `QuarkusTestResourceLifecycleManager` in the test JVM. A twin must cover
    failure modes, not only the happy path: denied consent, missing or invalid
    auth, closed port, timeout, malformed payload and a dropped stream.

(c) Library first. Before writing infrastructure (process execution, git,
    JSON-RPC, protocol clients, command-line parsing, file watching, parsing
    of tool output), search Maven Central and the Quarkiverse for a maintained
    library or Quarkus extension and use it. Hand-written code is allowed only
    when no suitable library exists, and the pull request body must list the
    libraries considered and why they were rejected.

(d) Native image. No hand-written reflection or resource configuration JSON
    and no scattered `@RegisterForReflection`. Derive native needs from build
    steps in `vintelli-core/deployment` using the Jandex index. `ServiceLoader`
    needs explicit wiring because Quarkus disables the ServiceLoader feature.

(e) Module boundaries. `vintelli-api` and `vintelli-jsonrpc` stay plain Java:
    no Quarkus, no `jakarta`, no TamboUI. See `AGENTS.md` and `docs/` for the
    module map. `vintelli-core` is the Quarkus extension (runtime plus
    deployment) that holds the build-time glue.

(f) Security. Anything network, process or file facing is off by default and
    loopback only. The Host and Origin guard covers every route, including path
    spelling variants. File access is confined to the project root. Apply size
    and rate limits. Never put secrets in configuration or documentation.

## Code style

Reviewers enforce the following conventions.

- No inline fully qualified class names; use normal imports.
- Final, unproxied beans (`@Singleton`, `final` classes) with constructor
  injection. Resolve work at build time; avoid reflection and runtime proxies
  unless the feature strictly requires them.
- No `//` inline comments. Use Javadoc only when it explains something
  non-obvious. Prefer self-documenting code.
- Wrap code names in backticks in commit messages and pull request text.
- No em or en dashes in prose. Use plain hyphens, colons or parentheses.

## Pull request description

A good pull request description contains:

- Summary
- What changes
- How it was validated (commands run, platforms)
- Native proof (which `*IT` classes ran and on which runner)
- Libraries considered (and why rejected, if hand-written code was used)
- What is not in this pull request (follow-ups)

Template:

```
## Summary

<one or two sentences>

## What changes

- <bullet per logical change>

## Validation

- `./mvnw -pl <module> -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`
- Native integration tests: <list of *IT classes>

## Libraries considered

- <library>: <used / rejected, reason>

## Not in this pull request

- <follow-up issues, if any>

Closes #N
```

## Review process

Expect an independent review that checks issue coverage, native proof, library
first, security, and that the tests cannot pass without the change. If changes
are requested, amend the single commit and force-push with
`--force-with-lease`.

## Reporting bugs and security issues

Open an issue with reproduction steps. For vulnerabilities, use the
repository's private security advisory (the Security tab) instead of a public
issue.

## License

Vintelli is licensed under the Apache License, Version 2.0. Contributions are
accepted under the same license.
