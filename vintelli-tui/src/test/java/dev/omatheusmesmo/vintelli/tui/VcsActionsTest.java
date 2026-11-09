package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.tui.VcsActions.PromptKey;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
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
    void commitMessageIsConfirmedBeforeTheCommitRuns() throws InterruptedException {
        actions.requestCommit();
        typeText("fix");
        assertEquals("Commit message: fix (Enter to continue, Esc cancels)", status.get());

        actions.handleVcsPromptKey(enter());
        assertEquals("Commit 'fix'? [y/N]", status.get());
        assertEquals(List.of(), vcs.commits);

        actions.handleVcsPromptKey(character('y'));
        awaitCondition(() -> !actions.writeInProgress());
        assertEquals(List.of("fix"), vcs.commits);
        assertEquals("Committed: fix", status.get());
        assertFalse(actions.promptOpen());
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
        return new PromptKey(false, false, false, codePoint, true);
    }

    private static PromptKey enter() {
        return new PromptKey(false, true, false, -1, false);
    }

    private static PromptKey escape() {
        return new PromptKey(true, false, false, -1, false);
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    private static final class FakeVcs implements VersionControlCapability {

        final List<String> commits = new CopyOnWriteArrayList<>();
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
