# Spike 54: reassess the intellij-server profile when JetBrains publishes agentic licensing

Part of #15; follow-up of the spike #22 document; answers issue #54 (desk recheck, no terms published yet).

**Status:** Desk research only. No server was launched and no code was changed. The goal was to check whether JetBrains has published anything about licensing `intellij-server` (the `Java and Kotlin by IntelliJ IDEA` extension and its underlying LSP server) for purely agentic or third-party programmatic use. As of 2026-10-28, **no publication was found**: the promise made in the 1.0 announcement was repeated, and these checks found no fulfilment of it. Checks were made on 2026-10-28, except the Marketplace listing (fetched 2026-10-26).

## Answer to the issue

**Decision:** no change; the `intellij-server` profile stays opt-in per the spike 22 decision until JetBrains publishes terms.

**No publication found yet.** The `Java and Kotlin by IntelliJ IDEA` 1.0 announcement (the newest statement on the topic, published 2026-10-15, author Marco Behler, https://blog.jetbrains.com/idea/2026/10/intellij-idea-s-java-and-kotlin-for-cursor-and-vs-code-hits-1-0/, publication date confirmed against the blog RSS feed, https://blog.jetbrains.com/idea/feed/, last build 2026-10-16) still carries the exact same licensing note that spike 22 recorded:

> **Note:** The license is explicitly designed for human use; we'll release an update regarding licensing models for purely agentic use soon.

The same post adds a teaser for the agentic workflows themselves, again without terms: "Coming next: Agentic and terminal-based workflows ... In one of the next releases, agents that you orchestrate in the Agents view in Cursor or VS Code will also be able to call the LSP directly ... News on this will also be announced soon." The RC announcement (2026-10-06, https://blog.jetbrains.com/idea/2026/10/java-and-kotlin-by-intellij-idea-the-lsp-based-extension-reaches-release-candidate/) says the same: "Over the coming weeks, we'll ... share more about where we're taking the technology next, including fully agentic, terminal-based workflows."

No new terms surfaced on any other JetBrains surface either (details under "Searches performed"). The Marketplace "Licensing" section for the extension (fetched 2026-10-26, https://marketplace.visualstudio.com/items?itemName=JetBrains.intellij-server, version 1.0.27) mentions only the Ultimate or All Products Pack subscription and the free 30-day trial, with no agentic or per-agent terms. Based on search snippets, no URL retained: the JetBrains sales FAQ and the YouTrack/IDEA release-note articles contain no agent- or embedding-specific licensing entry for the server; those pages were searched, not read in full. The `intellij-lsp.nvim` README (fetched 2026-10-28, https://github.com/JetBrains/intellij-lsp.nvim) still documents the bundle EULA and seat licensing only.

### One related signal, not a term

The JetBrains blog post about Netflix (published 2026-10-15, https://blog.jetbrains.com/ai/2026/10/how-netflix-made-ai-coding-agents-more-efficient/) is the closest thing to movement on the topic. It describes a headless IntelliJ IDEA code-intelligence engine, which the post presents as the engine exposed through the LSP (that this is the `intellij-server` bundle is inferred), called by agents (Claude Code, Codex) through a daemon and CLI. Two statements matter for Vintelli:

- "Netflix's role: Built the daemon and CLI needed to make the LSP callable by an agent - infrastructure that JetBrains hadn't shipped yet", and "JetBrains is working on delivering a daemon, but no daemon was available by the time of evaluation."
- "JetBrains plans to continue the collaboration and support this evaluation further."

This is partner-level work, not a published licensing term. Nothing in the post says what license covers that headless, agent-facing usage. Inference, not stated by JetBrains: the daemon JetBrains says it is working on would serve the same use case as Vintelli's agent-facing LSP calls. This changes nothing about the standing decision until terms are published.

## Searches performed (2026-10-28 unless noted)

- Full-text fetch of the 1.0 announcement, https://blog.jetbrains.com/idea/2026/10/intellij-idea-s-java-and-kotlin-for-cursor-and-vs-code-hits-1-0/: licensing note unchanged, agentic update promised "soon".
- Fetch of the IntelliJ IDEA blog RSS feed, https://blog.jetbrains.com/idea/feed/ (last build 2026-10-16): the newest LSP-related posts are the Netflix post and the 1.0 post, both 2026-10-15; no newer post about agentic licensing.
- Web search: `JetBrains intellij-server licensing agentic use announcement`.
- Web search: `JetBrains blog "purely agentic use" Java Kotlin language server license` (surfaced only the 1.0, RC and preview posts).
- Web search: `"intellij-server" OR "Java and Kotlin by IntelliJ IDEA" agentic licensing update "purely agentic"`.
- Web search: `"Java and Kotlin by IntelliJ IDEA" licensing agents news 2026-10-2` (looking for content from the last week of October; based on search snippets, no URL retained, nothing newer than the 2026-10-15 posts surfaced).
- Fetch of the VS Code Marketplace listing, https://marketplace.visualstudio.com/items?itemName=JetBrains.intellij-server (fetched 2026-10-26, version 1.0.27): Licensing section says only "requires IntelliJ IDEA Ultimate or All Products Pack subscription" plus the 30-day trial; no agentic terms.
- Web search of the sales FAQ (based on search snippets, no URL retained): `JetBrains sales FAQ "agentic" license LSP language server agents`. The snippets showed nothing agent-specific for the Java and Kotlin LSP; the only "agent" licenses found were TeamCity build agents and YouTrack support agents, both unrelated.
- Web search (based on search snippets, no URL retained): `youtrack JetBrains LSP project agentic license intellij-server release notes`. The IDEA and IJPL release-note articles surfaced no licensing item for agentic use of `intellij-server`.
- Fetch of the `JetBrains/intellij-lsp.nvim` README, https://raw.githubusercontent.com/JetBrains/intellij-lsp.nvim/master/README.md (fetched 2026-10-28): bundle EULA, trial/subscription and headless EULA acceptance only; no mention of agentic licensing.
- Web search: `JetBrains agentic license intellij-server marketplace announcement` (nothing relevant surfaced). Based on search snippets, no URL retained: an Air Governance post dated 2026-10-22 covers JetBrains' own agentic platform and governance, not terms for embedding `intellij-server`.

## Not verified

- **UNVERIFIED:** The full `EULA.txt` inside the `intellij-server` bundle and the JetBrains User Agreement 2.0 shown as the Marketplace license (https://marketplace.visualstudio.com/items/JetBrains.intellij-server/license, version 2.0 effective 2025-04-16) were not read in full; the Marketplace de-duplicated search snippets did not surface an agentic-use or third-party-client clause. How those documents treat purely agentic use is unknown. Reading them in full is still issue #53's work and remains required before any auto-download or bundling of the bundle.
- **UNVERIFIED:** Whether the Netflix/JetBrains collaboration will result in a published agentic licensing model or a JetBrains-shipped daemon/CLI; the blog post plans collaboration but names no date and no terms.
- **UNVERIFIED:** The absence of agentic licensing text on the sales FAQ and the YouTrack/IDEA release notes was inferred from web search snippets, not from reading those pages. Covered by #67.

## Trigger condition for the reassessment

Reassess the `intellij-server` profile when **any one** of these is published:

1. The promised update on "licensing models for purely agentic use" for `intellij-server` / `Java and Kotlin by IntelliJ IDEA`, wherever it lands (JetBrains blog, help pages, sales FAQ, the extension's help pages under https://www.jetbrains.com/help/intellij-vscode/, or the bundle `EULA.txt`).
2. The announced agentic and terminal-based workflows (agents such as Claude Code or Codex calling the LSP directly, or a JetBrains-shipped daemon/CLI for headless agent use), if that announcement includes the licensing terms.
3. Any change to the bundle `EULA.txt` or the Marketplace license text that adds an agentic, automation or third-party-client clause.

On any of these, read and summarize the new terms (acceptance criterion 1 of issue #54), then re-decide: profile kept, restricted (for example agents allowed only when the user holds a specific license tier) or dropped, with the reason written into #20's provider documentation and this document's successor.

## Standing recommendation (unchanged until then)

Per the spike 22 decision, which remains correct:

- `jdtls` stays the default language server for Vintelli, provisioned by issue #20 (JDK 21+ detection, EPL-2.0 archives with SHA-256 verification).
- `intellij-server` stays an opt-in profile. The user licenses it themselves (IntelliJ IDEA Ultimate or All Products Pack subscription). Vintelli must not auto-download the bundle and must not accept the bundle EULA on the user's behalf until #53 settles what the EULA permits.
- Agents calling the default `jdtls` provider are unaffected by the JetBrains question and remain the recommended path for Vintelli's agent workflows.

## Follow-up issues

- #53: read the bundle `EULA.txt` and the JetBrains User Agreement in full and settle third-party bundling and auto-download before the opt-in `intellij-server` profile ever downloads the bundle.
- #67: when the trigger condition fires, read and summarize the new terms, then keep, restrict or drop the profile with the reason (fulfils the remaining acceptance criterion of #54).
