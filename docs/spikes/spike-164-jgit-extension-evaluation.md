# JGit dependency decision

## Decision

Use upstream `org.eclipse.jgit:org.eclipse.jgit:7.8.1.202611041320-r` directly and remove `quarkus-jgit` from the local provider. JGit 7.8.1 is the newest stable upstream release available from Maven Central as of November 5, 2026. The latest stable `quarkus-jgit` release is 3.7.0 from August 4, 2026; it manages JGit 7.7.1. The extension's 4.0.0.Beta1 release is newer but is not stable.

The extension does not wrap or replace JGit's repository APIs. Its current local-provider usage is provided by upstream JGit: opening a repository, status and diffs, staging and unstaging, commits, local branch checkout, tracking status, and `.gitignore` matching. The application does not use remote transport, SSH, LFS, or the extension's Gitea Dev Services.

## Native-image support

`quarkus-jgit` contributes native SSL support, reflection registrations for a focused set of JGit enums and message types, runtime initialization for `WindowCache` and HTTP digest authentication, and the `JGitText` resource bundle. The local provider does not use remote HTTP or SSH transport, so this module omits SSL transport support but preserves the native registrations reached by the local APIs: the same focused enum set, `JGitText`, runtime initialization of `WindowCache`, `HttpAuthMethod$Digest` and `FileUtils`, and the message bundle. Mandrel confirmed that JGit's type graph reaches the digest authenticator even on the local path; initializing it at image build time captures a `SecureRandom` and fails image generation. It also reaches `FileUtils`, whose static `Random` must be initialized at runtime to avoid embedding its seed in the image. The processor keeps the focused enum list in one place, following the upstream extension's native configuration; the extra `FileUtils` registration is specific to this newer JGit release and is awaiting native CI verification.

The native Mandrel CI job and the existing VCS integration tests are the verification gate for this decision. The local Quarkus Dev MCP could not start the Maven reactor during this spike, so no local native run is claimed.

## Version evidence

- Eclipse lists JGit 7.8.1 as released on November 5, 2026: [JGit releases](https://projects.eclipse.org/projects/technology.jgit/governance).
- Maven Central metadata exposes `7.8.1.202611041320-r`: [JGit artifact](https://repo.maven.apache.org/maven2/org/eclipse/jgit/org.eclipse.jgit/7.8.1.202611041320-r/).
- Quarkus JGit 3.7.0 declares JGit 7.7.1 and Quarkus 3.33.3: [3.7.0 artifact metadata](https://central.sonatype.com/artifact/io.quarkiverse.jgit/quarkus-jgit/3.7.0).
- The upstream extension's native contributions are in [`JGitProcessor`](https://github.com/quarkiverse/quarkus-jgit/blob/3.7.0/deployment/src/main/java/io/quarkus/jgit/deployment/JGitProcessor.java).

## Maintenance trade-off

Direct JGit gives Vintelli control over the upstream release cadence and removes the extension's Quarkus-version alignment and unused SSH/Dev Services dependencies. Vintelli now owns the small native registration layer, which must be kept aligned with upstream JGit and covered by the native CI job.
