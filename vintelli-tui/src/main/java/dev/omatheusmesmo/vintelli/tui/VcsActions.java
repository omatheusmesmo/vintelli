package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.ShellUnifiedDiff;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Owns the version control actions: the status refresh that feeds the project
 * tree, the editor markers and the branch widget, the diffs, and the confirmed
 * write prompts (stage, unstage, commit, branch switch) with their background
 * write. It holds no TamboUI type: the shell routes the keys and the menus call
 * its commands.
 */
final class VcsActions {

    enum VcsWriteAction { STAGE, UNSTAGE, COMMIT, SWITCH_BRANCH }

    enum VcsPrompt { NONE, CONFIRM, COMMIT_MESSAGE, BRANCH_NAME }

    /** The branch widget state: hidden without a healthy VCS provider or a repository. */
    record ToolbarVcs(boolean visible, String branch) {

        static final ToolbarVcs HIDDEN = new ToolbarVcs(false, null);

        static ToolbarVcs of(VersionControlCapability.Status status) {
            return new ToolbarVcs(true, status.isDetached() ? "detached HEAD" : status.branch());
        }
    }

    /**
     * One key read by the shell. {@code character} is set for every character
     * key, including Ctrl or Alt ones, because the confirmation answers to the
     * character alone; {@code textInput} is the typed-text rule (no Ctrl, no Alt,
     * no control character).
     */
    record PromptKey(boolean escape, boolean enter, boolean backspace, int character, boolean textInput) {

        boolean isCharIgnoreCase(char expected) {
            return Character.toLowerCase(character) == Character.toLowerCase(expected);
        }
    }

    private final List<VersionControlCapability> providers;
    private final ReentrantLock frameLock;
    private final Consumer<String> statusSink;
    private final Consumer<ShellUnifiedDiff> diffSink;
    private final ProjectTreeController projects;
    private final BuiltinEditor editor;
    private final AtomicLong vcsRefreshSequence = new AtomicLong();
    private final AtomicLong toolbarVcsSequence = new AtomicLong();
    private volatile ToolbarVcs toolbarVcs;
    private VcsWriteAction pendingAction;
    private VcsPrompt prompt = VcsPrompt.NONE;
    private Path pendingFile;
    private String pendingValue = "";
    private volatile boolean writeInProgress;

    VcsActions(List<VersionControlCapability> providers, ReentrantLock frameLock, Consumer<String> statusSink,
            Consumer<ShellUnifiedDiff> diffSink, ProjectTreeController projects, BuiltinEditor editor) {
        this.providers = List.copyOf(providers);
        this.frameLock = frameLock;
        this.statusSink = statusSink;
        this.diffSink = diffSink;
        this.projects = projects;
        this.editor = editor;
        this.toolbarVcs = this.providers.isEmpty() ? ToolbarVcs.HIDDEN : new ToolbarVcs(true, "VCS");
    }

    ToolbarVcs toolbarVcs() {
        return toolbarVcs;
    }

    boolean promptOpen() {
        return prompt != VcsPrompt.NONE;
    }

    boolean writeInProgress() {
        return writeInProgress;
    }

    /** @return the reason the VCS actions are unavailable, or an empty string when a provider is healthy */
    String unavailableReason() {
        if (availableVcsProvider() != null) {
            return "";
        }
        return providers.isEmpty() ? "Version control provider unavailable"
                : providers.stream().map(provider -> provider.health().reason())
                        .filter(Objects::nonNull).findFirst().orElse("Version control provider unavailable");
    }

    private VersionControlCapability availableVcsProvider() {
        return providers.stream().filter(candidate -> candidate.health().isAvailable())
                .findFirst().orElse(null);
    }

    /**
     * Reads the branch widget state on a virtual thread at startup, because the
     * provider health check and the Git status both may block. A VCS refresh
     * that publishes first wins over this read through the sequence.
     */
    void refreshToolbarVcs() {
        if (providers.isEmpty()) {
            return;
        }
        long sequence = toolbarVcsSequence.incrementAndGet();
        Thread.ofVirtual().name("vintelli-toolbar-vcs").start(() -> {
            ToolbarVcs next = ToolbarVcs.HIDDEN;
            try {
                VersionControlCapability provider = availableVcsProvider();
                if (provider != null && provider.status() instanceof CapabilityResult.Ok<VersionControlCapability.Status> ok) {
                    next = ToolbarVcs.of(ok.value());
                }
            } catch (RuntimeException unavailable) {
                next = ToolbarVcs.HIDDEN;
            }
            if (sequence == toolbarVcsSequence.get()) {
                toolbarVcs = next;
            }
        });
    }

    void refresh() {
        refreshVcs(vcsRefreshSequence.incrementAndGet());
    }

    void refreshVcsAsync() {
        long request = vcsRefreshSequence.incrementAndGet();
        frameLock.lock();
        try {
            statusSink.accept("Refreshing Git status...");
        } finally {
            frameLock.unlock();
        }
        Thread.ofVirtual().start(() -> refreshVcs(request));
    }

    private void refreshVcs(long request) {
        VersionControlCapability provider = availableVcsProvider();
        if (provider == null) {
            publishVcsRefresh(request, Map.of(), Map.of(), ToolbarVcs.HIDDEN, providers.isEmpty() ? "Version control unavailable"
                    : "Version control unavailable: "
                            + providers.stream().map(p -> p.health().reason()).filter(Objects::nonNull)
                                    .findFirst().orElse("no healthy provider"));
            return;
        }
        CapabilityResult<VersionControlCapability.Status> result = provider.status();
        if (result instanceof CapabilityResult.Failed<VersionControlCapability.Status> failed) {
            publishVcsRefresh(request, Map.of(), Map.of(), ToolbarVcs.HIDDEN,
                    "VCS refresh failed: " + failed.failure().reason());
            return;
        }
        VersionControlCapability.Status statusValue =
                ((CapabilityResult.Ok<VersionControlCapability.Status>) result).value();
        Map<String, VersionControlCapability.State> states = new HashMap<>();
        statusValue.entries().forEach(entry -> states.put(entry.path().replace('\\', '/'), entry.state()));
        Map<String, Set<Integer>> changedLines = new HashMap<>();
        String activeFile = null;
        Path activePath = null;
        frameLock.lock();
        try {
            if (editor != null) {
                activeFile = editor.currentFile().map(file -> editor.project().relativize(file).toString()
                        .replace('\\', '/')).orElse(null);
                if (activeFile != null) { activePath = editor.project().resolve(activeFile); }
            }
        } finally {
            frameLock.unlock();
        }
        if (activeFile != null && activePath != null
                && states.get(activeFile) == VersionControlCapability.State.MODIFIED) {
            CapabilityResult<String> fileDiff = provider.diff(activePath);
            if (fileDiff instanceof CapabilityResult.Ok<String> ok && ok.value() != null) {
                changedLines.put(activeFile, changedLines(ok.value()));
            }
        }
        String branch = statusValue.isDetached() ? "detached HEAD" : statusValue.branch();
        publishVcsRefresh(request, states, changedLines, ToolbarVcs.of(statusValue),
                "VCS: " + branch + " (+" + (statusValue.ahead() == null ? 0 : statusValue.ahead())
                        + "/-" + (statusValue.behind() == null ? 0 : statusValue.behind()) + "), "
                        + statusValue.entries().size() + (statusValue.truncated() ? "+ changed (partial)" : " changed"));
    }

    private void publishVcsRefresh(long request, Map<String, VersionControlCapability.State> states,
            Map<String, Set<Integer>> changedLines, ToolbarVcs toolbar, String newStatus) {
        frameLock.lock();
        try {
            if (request != vcsRefreshSequence.get()) { return; }
            toolbarVcsSequence.incrementAndGet();
            toolbarVcs = toolbar;
            projects.publishVcsStates(states);
            if (editor != null) { editor.vcsStatus(projects.vcsStates(), changedLines); }
            statusSink.accept(newStatus);
        } finally {
            frameLock.unlock();
        }
    }

    static Set<Integer> changedLines(String diffText) {
        Set<Integer> rows = new HashSet<>();
        int newLine = 0;
        boolean inHunk = false;
        for (String line : diffText.split("\\R")) {
            if (line.startsWith("@@")) {
                inHunk = true;
                int plus = line.indexOf('+');
                int end = plus < 0 ? -1 : line.indexOf(' ', plus);
                if (end > plus) {
                    String range = line.substring(plus + 1, end);
                    int comma = range.indexOf(',');
                    try { newLine = Integer.parseInt(comma < 0 ? range : range.substring(0, comma)) - 1; }
                    catch (NumberFormatException ignored) { newLine = 0; }
                }
            } else if (inHunk && line.startsWith("+")) {
                rows.add(Math.max(0, newLine++));
            } else if (inHunk && line.startsWith("-")) {
                rows.add(Math.max(0, newLine - 1));
            } else if (inHunk && line.startsWith(" ")) {
                newLine++;
            }
        }
        return Set.copyOf(rows);
    }

    void showVcsDiff() {
        VersionControlCapability provider = availableVcsProvider();
        if (provider == null) {
            statusSink.accept("Version control unavailable");
            return;
        }
        if (editor == null || editor.activeDocument().isEmpty()) {
            statusSink.accept("Open a project file first");
            return;
        }
        Path file = Path.of(editor.activeDocument().get().file());
        CapabilityResult<String> result = provider.diff(file);
        if (result instanceof CapabilityResult.Failed<String> failed) {
            statusSink.accept("VCS diff failed: " + failed.failure().reason());
            return;
        }
        String text = ((CapabilityResult.Ok<String>) result).value();
        diffSink.accept(new ShellUnifiedDiff(file.toString(), text == null ? "" : text));
        statusSink.accept("VCS diff: " + file);
    }

    void showVcsWorkingTreeDiff() {
        VersionControlCapability provider = availableVcsProvider();
        if (provider == null) {
            statusSink.accept("Version control unavailable");
            return;
        }
        CapabilityResult<String> result = provider.diffWorkingTree();
        if (result instanceof CapabilityResult.Failed<String> failed) {
            statusSink.accept("VCS diff failed: " + failed.failure().reason());
            return;
        }
        String text = ((CapabilityResult.Ok<String>) result).value();
        diffSink.accept(new ShellUnifiedDiff("working tree", text == null ? "" : text));
        statusSink.accept("VCS diff: working tree");
    }

    void requestStage(boolean unstage) {
        if (availableVcsProvider() == null) {
            statusSink.accept("Version control unavailable");
            return;
        }
        if (editor == null || editor.currentFile().isEmpty()) {
            statusSink.accept("Open a project file first");
            return;
        }
        pendingAction = unstage ? VcsWriteAction.UNSTAGE : VcsWriteAction.STAGE;
        pendingFile = editor.currentFile().orElseThrow();
        pendingValue = "";
        prompt = VcsPrompt.CONFIRM;
        statusSink.accept((unstage ? "Unstage " : "Stage ") + projectRelative(pendingFile) + "? [y/N]");
    }

    void requestCommit() {
        if (availableVcsProvider() == null) {
            statusSink.accept("Version control unavailable");
            return;
        }
        pendingAction = VcsWriteAction.COMMIT;
        pendingFile = null;
        pendingValue = "";
        prompt = VcsPrompt.COMMIT_MESSAGE;
        statusSink.accept("Commit message: " + pendingValue + " (Enter to continue, Esc cancels)");
    }

    void requestBranchSwitch() {
        if (availableVcsProvider() == null) {
            statusSink.accept("Version control unavailable");
            return;
        }
        if (editor != null && editor.tabs().stream().anyMatch(BuiltinEditor.TabView::dirty)) {
            statusSink.accept("Save or close dirty editor tabs before switching branches");
            return;
        }
        pendingAction = VcsWriteAction.SWITCH_BRANCH;
        pendingFile = null;
        pendingValue = "";
        prompt = VcsPrompt.BRANCH_NAME;
        statusSink.accept("Local branch name: " + pendingValue + " (Enter to continue, Esc cancels)");
    }

    /**
     * Runs a version control action of the global keymap. The overlays close
     * before the action, as every shell command does. Returns false for every
     * other action.
     */
    boolean handle(String actionId, Runnable closeOverlays) {
        return switch (actionId) {
            case KeyActions.VCS_REFRESH -> {
                closeOverlays.run();
                refreshVcsAsync();
                yield true;
            }
            case KeyActions.VCS_SHOW_DIFF -> {
                closeOverlays.run();
                showVcsDiff();
                yield true;
            }
            case KeyActions.VCS_DIFF_WORKING_TREE -> {
                closeOverlays.run();
                showVcsWorkingTreeDiff();
                yield true;
            }
            case KeyActions.VCS_STAGE -> {
                closeOverlays.run();
                requestStage(false);
                yield true;
            }
            case KeyActions.VCS_UNSTAGE -> {
                closeOverlays.run();
                requestStage(true);
                yield true;
            }
            case KeyActions.VCS_COMMIT -> {
                closeOverlays.run();
                requestCommit();
                yield true;
            }
            case KeyActions.VCS_SWITCH_BRANCH -> {
                closeOverlays.run();
                requestBranchSwitch();
                yield true;
            }
            default -> false;
        };
    }

    void handleVcsPromptKey(PromptKey key) {
        if (key.escape()) {
            cancelVcsPrompt("Git operation cancelled");
            return;
        }
        if (prompt == VcsPrompt.CONFIRM) {
            if (key.isCharIgnoreCase('y')) {
                prompt = VcsPrompt.NONE;
                performVcsWrite();
            } else if (key.isCharIgnoreCase('n') || key.enter()) {
                cancelVcsPrompt("Git operation cancelled");
            }
            return;
        }
        if (key.backspace()) {
            if (!pendingValue.isEmpty()) {
                int codePoint = pendingValue.codePointBefore(pendingValue.length());
                pendingValue = pendingValue.substring(0, pendingValue.length() - Character.charCount(codePoint));
            }
        } else if (key.enter()) {
            if (pendingValue.isBlank()) {
                statusSink.accept(prompt == VcsPrompt.COMMIT_MESSAGE ? "Commit message cannot be empty"
                        : "Branch name cannot be empty");
                return;
            }
            prompt = VcsPrompt.CONFIRM;
            statusSink.accept((pendingAction == VcsWriteAction.COMMIT ? "Commit '" : "Switch to branch '")
                    + pendingValue + "'? [y/N]");
        } else if (key.textInput()) {
            int limit = prompt == VcsPrompt.COMMIT_MESSAGE ? 1000 : 255;
            if (pendingValue.codePointCount(0, pendingValue.length()) < limit) {
                pendingValue += new String(Character.toChars(key.character()));
            }
        }
        if (prompt == VcsPrompt.COMMIT_MESSAGE) {
            statusSink.accept("Commit message: " + pendingValue + " (Enter to continue, Esc cancels)");
        } else if (prompt == VcsPrompt.BRANCH_NAME) {
            statusSink.accept("Local branch name: " + pendingValue + " (Enter to continue, Esc cancels)");
        }
    }

    void cancelVcsPrompt(String message) {
        prompt = VcsPrompt.NONE;
        pendingAction = null;
        pendingFile = null;
        pendingValue = "";
        statusSink.accept(message);
    }

    private void performVcsWrite() {
        VcsWriteAction action = pendingAction;
        Path file = pendingFile;
        String value = pendingValue;
        pendingAction = null;
        pendingFile = null;
        pendingValue = "";
        VersionControlCapability provider = availableVcsProvider();
        if (provider == null) {
            statusSink.accept("Version control unavailable");
            return;
        }
        writeInProgress = true;
        statusSink.accept(switch (action) {
            case STAGE -> "Staging " + projectRelative(file) + "...";
            case UNSTAGE -> "Unstaging " + projectRelative(file) + "...";
            case COMMIT -> "Committing staged changes...";
            case SWITCH_BRANCH -> "Switching branch...";
        });
        Thread.ofVirtual().start(() -> {
            CapabilityResult<Boolean> result;
            try {
                result = switch (action) {
                    case STAGE -> provider.stage(file, true);
                    case UNSTAGE -> provider.unstage(file, true);
                    case COMMIT -> provider.commit(value, true);
                    case SWITCH_BRANCH -> provider.switchBranch(value, true);
                };
            } catch (RuntimeException failure) {
                String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                result = CapabilityResult.failed(new CapabilityFailure("Git operation failed", detail));
            }
            long refresh = vcsRefreshSequence.incrementAndGet();
            refreshVcs(refresh);
            frameLock.lock();
            try {
                if (refresh == vcsRefreshSequence.get()) {
                    statusSink.accept(result instanceof CapabilityResult.Ok<Boolean> ok && ok.value()
                            ? vcsWriteSuccess(action, file, value)
                            : "Git operation failed: " + result.failureOf().map(CapabilityFailure::detail)
                                    .filter(detail -> !detail.isBlank()).orElse("the provider rejected the action"));
                }
                writeInProgress = false;
            } finally {
                frameLock.unlock();
            }
        });
    }

    private String vcsWriteSuccess(VcsWriteAction action, Path file, String value) {
        return switch (action) {
            case STAGE -> "Staged " + projectRelative(file);
            case UNSTAGE -> "Unstaged " + projectRelative(file);
            case COMMIT -> "Committed: " + value;
            case SWITCH_BRANCH -> "Switched to " + value + "; reopen tabs to load branch files";
        };
    }

    private String projectRelative(Path file) {
        if (file == null || editor == null) { return ""; }
        try { return editor.project().relativize(file).toString(); }
        catch (IllegalArgumentException outsideProject) { return file.toString(); }
    }
}
