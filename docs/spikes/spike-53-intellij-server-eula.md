# Spike 53: `intellij-server` EULA, third-party bundling and auto-download

Part of #15. Follow-up of `spike-22-jdtls-vs-intellij-lsp.md`; settles #53; feeds the profile rules in #20.

**Status:** Desk research only. No server was launched and no quality or performance measurement was made. The only executed action was reading the published bundle archive over HTTP (partial range downloads, described below) to extract the bundle's `EULA.txt`. The macOS bundle `EULA.txt` was read in full on 2026-10-29; the web pages were read on 2026-10-28.

**Disclaimer:** This document records our reading of the license text to support engineering decisions. It is not legal advice and has not been reviewed by a lawyer or by JetBrains.

## Decision

- **Bundling: no.** Vintelli may not ship the `intellij-server` binary with its installer or otherwise redistribute it. On our reading, the license forbids providing the Product to third parties outside an explicit JetBrains carve-out that the bundle does not appear to have.
- **Auto-download: yes, under conditions.** On our reading, Vintelli may download the bundle at first run of the `intellij-server` profile, directly from JetBrains' servers, when the user explicitly accepts the bundle EULA on a dedicated screen first. This mirrors the documented and technically enforced behavior of JetBrains' own Neovim client, which is evidence for this reading, not permission. The conditions are listed in "Verdict on auto-download" and turned into profile rules below.

The agentic-use licensing question is out of scope here: the same product's license is currently "designed for human use" and JetBrains has announced an update for purely agentic use, which remains tracked in #54.

## What the EULA is: sources read

1. **The bundle's own `EULA.txt`.** Inside the published macOS x86_64 bundle (build `263.7084.0`, product version 2026.3, archive `intellij-server-263.7084.0.sit`, https://download.jetbrains.com/language-server/intellij-server/263.7084.0/intellij-server-263.7084.0.sit) there is a file `intellij-server-2026.3/EULA.txt` with 16,384 bytes. It was read in full: it is the **JetBrains User Agreement, version 2.0, effective as of April 16, 2025**.
2. **The published User Agreement.** The identical text (same version, same effective date, clauses 1 through 10.9 matching) is published by JetBrains at https://www.jetbrains.com/legal/docs/toolbox/user/. Word and PDF copies are linked from that page.
3. **The extension's LICENSE.txt.** The extension package distributed on Open VSX (`JetBrains.intellij-server` 1.0.27) ships the same JetBrains User Agreement v2.0 as its `LICENSE.txt` file. Its `package.json` declares the license URL https://www.jetbrains.com/legal/docs/toolbox/user/ in the `license` field. Sources: https://open-vsx.org/api/JetBrains/intellij-server/darwin-arm64/1.0.27/file/package.json (`license` field) and https://open-vsx.org/api/JetBrains/intellij-server/darwin-arm64/1.0.27/file/LICENSE.txt.
4. **The Marketplace listing** for the same extension: https://marketplace.visualstudio.com/items?itemName=JetBrains.intellij-server.
5. **The 1.0 announcement**: https://blog.jetbrains.com/idea/2026/10/intellij-idea-s-java-and-kotlin-for-cursor-and-vs-code-hits-1-0/.
6. **JetBrains' own `intellij-lsp.nvim` client**, which downloads and enforces the bundle EULA: README (https://github.com/JetBrains/intellij-lsp.nvim/blob/main/README.md), `server_bundles.lua` (https://github.com/JetBrains/intellij-lsp.nvim/blob/main/lua/intellij-lsp/server_bundles.lua), `eula.lua` (https://github.com/JetBrains/intellij-lsp.nvim/blob/main/lua/intellij-lsp/eula.lua), `server_download.lua` (https://github.com/JetBrains/intellij-lsp.nvim/blob/main/lua/intellij-lsp/server_download.lua).

So there is one operative license: the JetBrains User Agreement v2.0 governs every copy of the bundle, whichever channel delivers it. (How the `EULA.txt` was read: the `.sit` is a zip archive; the last kilobytes of the file were fetched over HTTP and the zip central directory parsed to locate `EULA.txt`, whose compressed bytes were then fetched by range and decompressed. Anyone can repeat this on the public URL.)

## Clauses relevant to redistribution

Quotes below are verbatim from the User Agreement (bundle `EULA.txt` and https://www.jetbrains.com/legal/docs/toolbox/user/).

**Acceptance (preamble).** The Agreement binds the user by "DOWNLOADING, INSTALLING, COPYING, SAVING ON YOUR DEVICE, OR OTHERWISE USING THE JETBRAINS PRODUCT". The party is "You", "an individual or a legal entity that you represent". In our reading, a third-party client is not a party to the Agreement; the person whose machine hosts the server is.

**Section 2, "Redistributable Product" definition.**

> "Redistributable Product" means an independent module of a Product (or a standalone JetBrains development tool distributed together with the Product) designated by Us as 'Redistributable' in its name or in its official description, such as a software development kit (SDK), application programming interface (API), or command-line tool application (CLT).

The only redistribution escape hatch in the license is therefore conditional on an explicit JetBrains designation of the artifact. The bundle carries no such designation: its archive and directory name is `intellij-server-2026.3` with no "Redistributable" word, we found no redistribution grant in the Marketplace listing's Licensing section or FAQ, and the `intellij-lsp.nvim` README states rather the opposite, that the bundle "is a separate JetBrains product with its own license (`EULA.txt` inside the bundle)" which "the plugin asks you to accept on first start". Sources: https://www.jetbrains.com/legal/docs/toolbox/user/ (Section 2); https://download.jetbrains.com/language-server/intellij-server/263.7084.0/intellij-server-263.7084.0.sit (archive entry name); https://marketplace.visualstudio.com/items?itemName=JetBrains.intellij-server (Licensing section, FAQ); https://github.com/JetBrains/intellij-lsp.nvim/blob/main/README.md.

**Section 3.1, license grant.**

> "You may install the Product on Your Machine free of charge and use it as described in Section 3 as long as You comply with this Agreement. This license is provided as worldwide, non-exclusive, non-transferable, non-sublicensable, and royalty-free for the term of this Agreement."

**Section 3.5, restrictions (the core clause).**

> "Without Our express written permission (or unless this right is granted by applicable law), You may not: [...] (b) reverse-engineer, disassemble, or decompile the Product, or parts thereof, or try to derive the source code of the Product in any way; [...] (d) sell, rent, lease, sublease, license, sublicense, lend, time-share, transfer, assign, provide the use of, or otherwise provide the Product or access to the Product to any third party, whether in whole or in part; (e) use, or try to use the Product in a way that avoids incurring fees, including overcoming technical restrictions, security protection, or authentication methods applicable after the Trial Period;"

**Section 3.6.**

> "Redistributable Products. If the Product is distributed together with a Redistributable Product, according to the Documentation, We license the Redistributable Product to You under a separate license agreement published at https://www.jetbrains.com/legal/."

This clause confers nothing by itself: it only says that redistributable pieces carry a *separate* license, which must exist and be published. No separate published license for the `intellij-server` bundle was found anywhere. Searched and not found: the Marketplace listing (links only documentation and pricing), the extension help pages (https://jetbrains.com/help/intellij-vscode/About-instance.html), and the `intellij-lsp.nvim` repository.

**Subscription requirement.** The Marketplace listing states: "The extension requires IntelliJ IDEA Ultimate or All Products Pack subscription. To try it, you can start a free 30-day IntelliJ IDEA Ultimate trial." (https://marketplace.visualstudio.com/items?itemName=JetBrains.intellij-server). The 1.0 blog post states: "Using it now requires an IntelliJ IDEA Ultimate subscription after a 30-day trial period during which you can try out its full feature set for free," and adds: "The license is explicitly designed for human use; we'll release an update regarding licensing models for purely agentic use soon." (https://blog.jetbrains.com/idea/2026/10/intellij-idea-s-java-and-kotlin-for-cursor-and-vs-code-hits-1-0/).

## Verdict on bundling

**No.** Vintelli must not bundle, embed, repackage or ship the `intellij-server` binary with any Vintelli artifact (installer, container image, repository release assets, or any other channel).

Reasons, clause by clause:

1. **Section 3.5(d) forbids it, in our reading.** Bundling means providing the Product ("whether in whole or in part") to Vintelli's users, and requires "Our express written permission", which does not exist on any page covered above.
2. **Section 2 plus Section 3.6 do not help.** Redistribution is only possible for artifacts JetBrains designated as "Redistributable"; no such designation exists for this bundle, and no separate published redistributable license for it exists.
3. **Section 3.1 reinforces it.** The grant is "non-transferable, non-sublicensable" and tied to installation "on Your Machine" by the accepted user.

Nothing in what JetBrains ships contradicts this, as far as we checked: their own client is Apache 2.0 as code (the plugin), but the README is explicit that "The `intellij-server` bundle it downloads is a separate JetBrains product with its own license" (https://github.com/JetBrains/intellij-lsp.nvim/blob/main/README.md). The plugin repository does not contain the bundle; every client that wants it must fetch it from `download.jetbrains.com`.

**UNVERIFIED:** whether JetBrains would grant "express written permission" (Section 3.5) if asked, and under what terms. The only published contact is `legal@jetbrains.com` (final line of the User Agreement). Until such permission exists, the no-go stands and Vintelli must not disable it on the strength of any assumption.

## Verdict on auto-download

**Yes, conditionally:** Vintelli may fetch the bundle at first run of the `intellij-server` profile, provided every condition below holds. This is our interpretation, not an express permission (see UNVERIFIED items), but it rests on the documented behavior of JetBrains' own client for the exact same bundle.

What JetBrains' own client does, with sources:

- It auto-downloads by default: "With nothing configured the plugin downloads the published `intellij-server` bundle for your platform on first start" (https://github.com/JetBrains/intellij-lsp.nvim/blob/main/README.md).
- It pins an official URL and verifies integrity: `server_bundles.lua` constructs
  `https://download.jetbrains.com/language-server/intellij-server/{version}/intellij-server-{version}{arch_suffix}.{ext}`
  and hardcodes per-platform SHA-256 hashes; `server_download.lua` verifies the archive checksum before extraction (https://github.com/JetBrains/intellij-lsp.nvim/blob/main/lua/intellij-lsp/server_bundles.lua, https://github.com/JetBrains/intellij-lsp.nvim/blob/main/lua/intellij-lsp/server_download.lua).
- It gates the first server start on an explicit EULA acceptance by the user, and offers an escape hatch to point at the user's own copy: `server_path` ("use your own bundle instead of downloading") and `accept_eula` ("after reading EULA.txt in the downloaded bundle"; headless Neovim "starts no server without `accept_eula = true`") (https://github.com/JetBrains/intellij-lsp.nvim/blob/main/README.md).
- The server itself enforces acceptance. The header comment of `eula.lua` says "The server refuses to start unless `--eula <hash>` matches the hash baked into the bundle. The expected value is the first 16 characters of the lowercase hex SHA-256 of the bundle's own `EULA.txt`" (https://github.com/JetBrains/intellij-lsp.nvim/blob/main/lua/intellij-lsp/eula.lua).

Why we read this pattern as compatible with the license for a third-party client:

1. **In our reading, the Product flows from JetBrains to the user, not from Vintelli to the user.** Section 3.5(d) forbids You "provid[ing] the Product or access to the Product to any third party". In the auto-download flow, the bytes travel from `download.jetbrains.com` directly to the user's machine, and the user is bound by the Agreement by "DOWNLOADING ... OR OTHERWISE USING" the Product (preamble). A rights holder could still view Vintelli's code initiating the download and passing `--eula` on the user's behalf as Vintelli "providing" the Product; this is not resolved by the text.
2. **JetBrains documents this flow for a third-party editor.** They publish a Neovim client (a third-party editor from JetBrains' point of view) whose documented, default behavior is download on first start with user EULA acceptance, and the Marketplace listing advertises the extension for "Visual Studio Code, Cursor, Google Antigravity, and other compatible editors" (https://marketplace.visualstudio.com/items?itemName=JetBrains.intellij-server). The Agreement text does not tie the user's right to download to JetBrains' own client, but that is our inference; no JetBrains statement says so (see UNVERIFIED below).
3. **Fee integrity stays intact, in our reading.** The server enforces licensing after the trial (Marketplace listing licensing section; README licensing section), so fetching the bundle does not by itself let a client avoid fees (Section 3.5(e)).

**Conditions Vintelli must meet for auto-download to stay consistent with our reading of the license:**

1. **Never bundle and never mirror.** The archive always comes from `download.jetbrains.com` (pinned version URL with a per-platform SHA-256 check, as in `server_bundles.lua`). No Vintelli-hosted copy, no third-party mirror, no copy embedded in a container image.
2. **The user's acceptance is a hard gate.** Before the first server start, Vintelli shows the bundle's `EULA.txt` content (which can be read from the downloaded bundle's root, as the JetBrains client does) and gets a separate, explicit acceptance. No pre-checked default, no bundling this consent with other settings, no silent acceptance for headless runs unless the user explicitly configured it after having seen the EULA text. The server refuses unaccepted copies technically anyway (`--eula` hash).
3. **The user's own license activation stays theirs.** Vintelli starts the server only after the trial/subscription question is resolved by the user (their JetBrains Account, activation code, or declared acceptance of the trial). Vintelli does nothing to sidestep licensing checks (Section 3.5(e)).
4. **Escape hatch preserved.** Like the JetBrains client's `server_path`, Vintelli must allow a user-pointed existing bundle and skip the download entirely; EULA acceptance is then still required before passing `--eula` for that bundle root.
5. **Clear status on refusal or failure.** If the user declines the EULA or the download fails, no server starts and the profile shows a clear status message (matches #20's criterion for a missing binary).

**Residual uncertainty, UNVERIFIED:**

- No JetBrains document says "third-party clients may auto-download". The conditional go is our reasoning from the Agreement's text plus the evidence that JetBrains ships the identical flow for their own third-party-editor client. If Vintelli wants certainty, it should ask JetBrains (#66).
- The Marketplace listing and its hosting terms (Microsoft/VS Marketplace ToU, Open VSX terms) were not reviewed as instruments binding Vintelli, because Vintelli does not distribute through those registries; Vintelli's users install nothing from them (Vintelli fetches the bundle directly). Their effect on Vintelli is presumed nil but is UNVERIFIED.
- The License Agreement sections beyond the User Agreement that a paying customer accepts (Section 3.3 of the User Agreement says further use "will be governed by separate legal terms published on the JetBrains Website") do not bear on redistribution and were not reviewed.
- Other Section 3.5 clauses besides (b), (d) and (e) were not analyzed for the auto-download flow.

## Resulting rules for the `intellij-server` profile (recorded in #20)

- The profile stays **opt-in and default off**.
- **No bundling, ever**: Vintelli ships zero JetBrains bytes. No installer inclusion, no repository artifacts, no images.
- **Auto-download is permitted** on first profile activation, pinned to an official `download.jetbrains.com` version URL with a SHA-256 integrity check, exactly mirroring the pattern in `server_bundles.lua` and `server_download.lua`.
- **EULA acceptance screen before the first start**: show the bundle's `EULA.txt`, require an explicit user-only consent step, store the acceptance, then pass `--eula <hash>` (SHA-256 of the bundle EULA, first 16 hex chars) on launch as the JetBrains client does.
- **License activation is the user's step**: surface the trial/activation flow or the environment variables the server reads; Vintelli never automates or bypasses it.
- **`server_path` override always wins**: if the user has their own bundle, no download occurs; the EULA gate still applies to that bundle root.
- **Failure states are visible**: declined EULA, failed download, checksum mismatch, missing or expired license all produce a clear profile status and no running server.
- **Agentic use remains an open licensing question** (see #54): these rules cover a human-driven TUI that exposes the server; a separate rule set must be re-evaluated once JetBrains publishes purely-agentic licensing. Until then, agent-driven use is documented as a risk in the profile documentation, including exposing `intellij-server` results through Vintelli's own MCP endpoint to external agents, which may count as providing access to the Product to a third party (Section 3.5(d)).

## Not verified

- Whether the `EULA.txt` inside Linux and Windows bundles differs from the macOS one read here (the macOS `.sit` for 263.7084.0 was read because its zip layout allows surgical range extraction; the Linux `.tar.gz` is gzip streamed and does not). The published User Agreement at the license URL is the same text and is what the extension ships on every platform, so a divergence is UNVERIFIED but unlikely; the acceptance mechanism reads the bundle's own `EULA.txt`, so if one ever differed, the gate would still be correct. Tracked in #65.
- Whether JetBrains would answer a `legal@jetbrains.com` request affirmatively (Section 3.5 written permission) and what it would cost. UNVERIFIED, tracked in #66.
- The pending purely-agentic-use licensing update referenced by the 1.0 post. UNVERIFIED, tracked in #54.
- Whether exposing `intellij-server` results through Vintelli's own MCP endpoint to external agents counts as providing access to the Product to a third party under Section 3.5(d). Not analyzed here, tracked in #54.
- Presence of `EULA.txt` in future bundle versions. The client code treats a missing `EULA.txt` as "dev build, skip the check" (https://github.com/JetBrains/intellij-lsp.nvim/blob/main/lua/intellij-lsp/eula.lua); Vintelli must not treat a missing `EULA.txt` as an acceptance shortcut and must require the user to read the published User Agreement at https://www.jetbrains.com/legal/docs/toolbox/user/ instead (inference, not a quote; flagged as UNVERIFIED behavior of future builds). Tracked in #65.

## Follow-up issues

- #20: implement the profile rules above for the `intellij-server` profile (opt-in, auto-download with EULA gate, `server_path` escape hatch, license activation surfaced to the user).
- #54: reassess when JetBrains publishes purely-agentic-use licensing terms for this product, including the MCP exposure question above.
- #65: verify the `EULA.txt` of the Linux and Windows bundles and define the behavior for a future bundle without `EULA.txt`.
- #66: ask JetBrains (legal@jetbrains.com) for written permission to redistribute or auto-download the bundle under Section 3.5, before any change of the no-bundling decision.
