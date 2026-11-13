package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.tui.VcsActions.PromptKey;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VcsActionsTest {

    private static final String FRESH_STATUS = "VCS: fresh (+0/-0), 0 changed";

    private final FakeVcs vcs = new FakeVcs();
    private final AtomicReference<String> status = new AtomicReference<>("");
    private final VcsActions actions = new VcsActions(List.of(vcs), new ReentrantLock(), status::set,
            diff -> { }, new ProjectTreeController(Path.of(""), new ShellState(), null, null,
                    message -> { }, () -> { }, () -> { }),
            null);

    @Test
    void aVcsActionClosesTheOverlaysOnceAndOpensItsPrompt() {
        AtomicInteger closes = new AtomicInteger();

        assertTrue(actions.handle(KeyActions.VCS_COMMIT, closes::incrementAndGet));

        assertEquals(1, closes.get());
        assertTrue(actions.promptOpen());
    }

    @Test
    void anActionOutsideVersionControlIsNotHandledAndClosesNothing() {
        AtomicInteger closes = new AtomicInteger();

        assertFalse(actions.handle(KeyActions.SAVE, closes::incrementAndGet));

        assertEquals(0, closes.get());
        assertFalse(actions.promptOpen());
    }

    @Test
    void commitMessageIsConfirmedBeforeTheCommitRuns() throws InterruptedException {
        actions.requestCommit();
        typeText("fix");
        assertEquals("Commit message: fix [Shift+Enter newline, Tab amend=off, Enter continue, Esc cancel]",
                status.get());

        actions.handleVcsPromptKey(enter());
        assertEquals("Commit 1 staged file (+1/-1 lines, amend off)? [y/N]", status.get());
        assertEquals(List.of(), vcs.commits);

        actions.handleVcsPromptKey(character('y'));
        awaitCondition(() -> !actions.writeInProgress());
        assertEquals(List.of("fix"), vcs.commits);
        assertEquals("Committed: fix", status.get());
        assertFalse(actions.promptOpen());
    }

    @Test
    void commitMessageSupportsMultipleLinesAndAnAmendToggle() throws InterruptedException {
        actions.requestCommit();
        typeText("fix title");
        actions.handleVcsPromptKey(shiftEnter());
        typeText("detail");
        actions.handleVcsPromptKey(toggleAmend());
        assertEquals("Commit message: fix title\\ndetail [Shift+Enter newline, Tab amend=on, Enter continue, Esc cancel]",
                status.get());

        actions.handleVcsPromptKey(enter());
        assertEquals("Commit 1 staged file (+1/-1 lines, amend on)? [y/N]", status.get());
        actions.handleVcsPromptKey(character('y'));
        awaitCondition(() -> !actions.writeInProgress());

        assertEquals(List.of("fix title\ndetail"), vcs.commits);
        assertEquals(List.of(true), vcs.amendments);
        assertEquals("Amended commit: fix title detail", status.get());
    }

    @Test
    void rejectsAWhitespaceOnlyMultilineCommitMessage() {
        actions.requestCommit();
        typeText("  ");
        actions.handleVcsPromptKey(shiftEnter());
        typeText(" \t ");
        actions.handleVcsPromptKey(enter());

        assertEquals("Commit message cannot be empty", status.get());
        assertTrue(actions.promptOpen());
        assertEquals(List.of(), vcs.commits);
    }

    @Test
    void doesNotAskForAuthorWhenThereAreNoStagedChanges() {
        vcs.configuredAuthor = Optional.empty();
        vcs.stagedDiff = "";
        actions.requestCommit();
        typeText("fix");
        actions.handleVcsPromptKey(enter());

        assertEquals("There are no staged changes to commit", status.get());
        assertFalse(actions.promptOpen());
    }

    @Test
    void missingGitIdentityPromptsForNameAndEmailBeforeCommitConfirmation() throws InterruptedException {
        vcs.configuredAuthor = Optional.empty();
        actions.requestCommit();
        typeText("fix");
        actions.handleVcsPromptKey(enter());
        assertEquals("Commit author name: (Enter to continue, Esc cancels)", status.get());
        typeText("Matheus Oliveira");
        actions.handleVcsPromptKey(enter());
        assertEquals("Commit author email: (Enter to continue, Esc cancels)", status.get());
        typeText("matheus@example.invalid");
        actions.handleVcsPromptKey(enter());
        assertEquals("Commit 1 staged file (+1/-1 lines, amend off)? [y/N]", status.get());

        actions.handleVcsPromptKey(character('y'));
        awaitCondition(() -> !actions.writeInProgress());
        assertEquals(new VersionControlCapability.CommitAuthor("Matheus Oliveira", "matheus@example.invalid"),
                vcs.authors.getFirst());
    }

    @Test
    void branchNameIsRejectedWhenBlankAndSwitchesOnlyAfterConfirmation() throws InterruptedException {
        actions.requestBranchSwitch();
        actions.handleVcsPromptKey(enter());
        assertEquals("Branch name cannot be empty", status.get());

        typeText("dev");
        actions.handleVcsPromptKey(enter());
        assertEquals("Switch to branch 'dev'? [y/N]", status.get());
        assertEquals(List.of(), vcs.switches);

        actions.handleVcsPromptKey(character('Y'));
        awaitCondition(() -> !actions.writeInProgress());
        assertEquals(List.of("dev"), vcs.switches);
        assertEquals("Switched to dev; reopen tabs to load branch files", status.get());
    }

    @Test
    void escapeAndRefusalCancelThePromptWithoutWriting() {
        actions.requestCommit();
        typeText("wip");
        actions.handleVcsPromptKey(escape());
        assertEquals("Git operation cancelled", status.get());
        assertFalse(actions.promptOpen());

        actions.requestCommit();
        typeText("wip");
        actions.handleVcsPromptKey(enter());
        actions.handleVcsPromptKey(character('n'));
        assertEquals("Git operation cancelled", status.get());
        assertFalse(actions.promptOpen());
        assertFalse(actions.writeInProgress());
        assertEquals(List.of(), vcs.commits);
    }

    @Test
    void aRejectedWriteReportsTheProviderReason() throws InterruptedException {
        vcs.writeResult = CapabilityResult.failed(new CapabilityFailure("Git operation failed", "pre-commit hook rejected"));
        actions.requestCommit();
        typeText("fix");
        actions.handleVcsPromptKey(enter());
        actions.handleVcsPromptKey(character('y'));
        awaitCondition(() -> !actions.writeInProgress());
        assertEquals("Git operation failed: pre-commit hook rejected", status.get());
        assertEquals(List.of("fix"), vcs.commits);
    }

    @Test
    void aStaleAsyncRefreshDoesNotOverwriteANewerStatus() throws InterruptedException {
        vcs.branch = "fresh";
        vcs.holdFirstStatus = true;
        actions.refreshVcsAsync();
        assertTrue(vcs.firstStatusEntered.await(5, TimeUnit.SECONDS));

        actions.refreshVcsAsync();
        awaitCondition(() -> FRESH_STATUS.equals(status.get()));

        vcs.releaseFirstStatus.countDown();
        Thread stale = vcs.firstStatusThread.get();
        stale.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(stale.isAlive());
        assertEquals(FRESH_STATUS, status.get());
        assertEquals("fresh", actions.toolbarVcs().branch());
    }

    private void typeText(String text) {
        text.codePoints().forEach(codePoint -> actions.handleVcsPromptKey(character(codePoint)));
    }

    private static PromptKey character(int codePoint) {
        return new PromptKey(false, false, false, codePoint, true, false, false);
    }

    private static PromptKey enter() {
        return new PromptKey(false, true, false, -1, false, false, false);
    }

    private static PromptKey shiftEnter() {
        return new PromptKey(false, true, false, -1, false, true, false);
    }

    private static PromptKey toggleAmend() {
        return new PromptKey(false, false, false, -1, false, false, true);
    }

    private static PromptKey escape() {
        return new PromptKey(true, false, false, -1, false, false, false);
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    private static final class FakeVcs implements VersionControlCapability {

        final List<String> commits = new CopyOnWriteArrayList<>();
        final List<Boolean> amendments = new CopyOnWriteArrayList<>();
        final List<VersionControlCapability.CommitAuthor> authors = new CopyOnWriteArrayList<>();
        volatile Optional<VersionControlCapability.CommitAuthor> configuredAuthor = Optional.of(
                new VersionControlCapability.CommitAuthor("Vintelli Test", "test@vintelli.invalid"));
        volatile String stagedDiff = "diff --git a/file.txt b/file.txt\n-old\n+new\n";
        final List<String> switches = new CopyOnWriteArrayList<>();
        volatile CapabilityResult<Boolean> writeResult = CapabilityResult.ok(true);
        volatile String branch = "main";
        volatile boolean holdFirstStatus;
        final AtomicInteger statusCalls = new AtomicInteger();
        final CountDownLatch firstStatusEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirstStatus = new CountDownLatch(1);
        final AtomicReference<Thread> firstStatusThread = new AtomicReference<>();

        @Override
        public CapabilityResult<Status> status() {
            if (statusCalls.incrementAndGet() == 1 && holdFirstStatus) {
                firstStatusThread.set(Thread.currentThread());
                firstStatusEntered.countDown();
                await(releaseFirstStatus);
                return CapabilityResult.ok(new Status("stale", 0, 0, List.of()));
            }
            return CapabilityResult.ok(new Status(branch, 0, 0, List.of()));
        }

        @Override
        public CapabilityResult<String> diff(Path path) {
            return CapabilityResult.ok("");
        }

        @Override
        public CapabilityResult<String> diffWorkingTree() {
            return CapabilityResult.ok("");
        }

        @Override
        public CapabilityResult<String> diffStaged() {
            return CapabilityResult.ok(stagedDiff);
        }

        @Override
        public CapabilityResult<Optional<VersionControlCapability.CommitAuthor>> configuredCommitAuthor() {
            return CapabilityResult.ok(configuredAuthor);
        }

        @Override
        public CapabilityResult<Boolean> stage(Path path, boolean confirmed) {
            return CapabilityResult.ok(false);
        }

        @Override
        public CapabilityResult<Boolean> unstage(Path path, boolean confirmed) {
            return CapabilityResult.ok(false);
        }

        @Override
        public CapabilityResult<Boolean> commit(String message, boolean confirmed) {
            commits.add(message);
            return writeResult;
        }

        @Override
        public CapabilityResult<Boolean> commit(String message, VersionControlCapability.CommitAuthor author,
                boolean amend, boolean confirmed) {
            commits.add(message);
            if (author != null) { authors.add(author); }
            amendments.add(amend);
            return writeResult;
        }

        @Override
        public CapabilityResult<Boolean> switchBranch(String branchName, boolean confirmed) {
            switches.add(branchName);
            return writeResult;
        }

        @Override
        public String identifier() {
            return "test-vcs";
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.available();
        }

        @Override
        public String provenance() {
            return "test provider";
        }

        private static void await(CountDownLatch latch) {
            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
