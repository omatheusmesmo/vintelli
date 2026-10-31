package dev.omatheusmesmo.vintelli.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability.Entry;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability.State;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability.Status;

/**
 * Git provider behaviour on real repositories that each test creates through
 * JGit itself: branch and tracking state, every worktree state, diffs against
 * {@code HEAD} and the confinement of requested paths to the repository.
 */
@ExtendWith(IsolatedGitConfiguration.class)
class LocalVersionControlCapabilityTest {

    private static final String AUTHOR = "Vintelli Test";
    private static final String EMAIL = "test@vintelli.invalid";

    @TempDir
    Path temp;

    private LocalVersionControlCapability capabilityAt(Path projectRoot) {
        return new LocalVersionControlCapability(new LocalProjectRoot(projectRoot));
    }

    @Test
    void reportsTheBranchUntrackedFilesAndNoUpstreamWhenNoRemoteIsConfigured() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve("a.txt"), "one\n");
            commitAll(git, "initial");
            Files.writeString(repo.resolve("notes.txt"), "not tracked\n");

            Status status = value(capabilityAt(repo).status());

            assertEquals("main", status.branch());
            assertFalse(status.isDetached());
            assertNull(status.ahead());
            assertNull(status.behind());
            assertEquals(List.of(new Entry(State.UNTRACKED, "notes.txt")), status.entries());
        }
    }

    @Test
    void statusBarBranchIsCachedAsynchronouslyAndRefreshesAfterTheBranchChanges()
            throws IOException, GitAPIException {
        Path repo = temp.resolve("status-bar-repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo);
                LocalVersionControlCapability capability = capabilityAt(repo)) {
            Files.writeString(repo.resolve("a.txt"), "one\n");
            commitAll(git, "initial");

            TestProcesses.awaitUntil(Duration.ofSeconds(5),
                    () -> capability.statusBarWidgets(CapabilityType.VERSION_CONTROL).getFirst().text()
                            .equals("Branch: main"));

            git.checkout().setCreateBranch(true).setName("feature/status-bar").call();
            value(capability.status());

            assertEquals("Branch: feature/status-bar",
                    capability.statusBarWidgets(CapabilityType.VERSION_CONTROL).getFirst().text());
        }
    }

    @Test
    void classifiesModifiedDeletedAndStagedAddedPaths() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve("a.txt"), "one\n");
            Files.writeString(repo.resolve("b.txt"), "two\n");
            Files.writeString(repo.resolve("c.txt"), "three\n");
            commitAll(git, "initial");

            Files.writeString(repo.resolve("a.txt"), "changed\n");
            Files.delete(repo.resolve("b.txt"));
            Files.writeString(repo.resolve("d.txt"), "staged\n");
            git.add().addFilepattern("d.txt").call();

            Status status = value(capabilityAt(repo).status());

            assertEquals(List.of(
                    new Entry(State.MODIFIED, "a.txt"),
                    new Entry(State.DELETED, "b.txt"),
                    new Entry(State.ADDED, "d.txt")),
                    status.entries());
        }
    }

    @Test
    void reportsADetachedHeadWithoutABranchName() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve("a.txt"), "one\n");
            commitAll(git, "initial");
            Files.writeString(repo.resolve("a.txt"), "two\n");
            commitAll(git, "second");
            git.checkout().setName(git.getRepository().resolve(Constants.HEAD).getName()).call();

            Status status = value(capabilityAt(repo).status());

            assertTrue(status.isDetached());
            assertNull(status.branch());
        }
    }

    @Test
    void countsCommitsAheadAndBehindTheUpstreamBranch() throws IOException, GitAPIException {
        Path origin = temp.resolve("origin");
        Path work = temp.resolve("work");
        Files.createDirectories(origin);
        try (Git originGit = initRepository(origin)) {
            Files.writeString(origin.resolve("shared.txt"), "base\n");
            commitAll(originGit, "base");

            try (Git workGit = Git.cloneRepository()
                    .setURI(origin.toAbsolutePath().toString())
                    .setDirectory(work.toFile())
                    .call()) {
                Files.writeString(work.resolve("local.txt"), "local work\n");
                commitAll(workGit, "local work");

                Files.writeString(origin.resolve("remote.txt"), "remote work\n");
                commitAll(originGit, "remote work");
                workGit.fetch().call();

                Status status = value(capabilityAt(work).status());

                assertEquals("main", status.branch());
                assertEquals(1, status.ahead());
                assertEquals(1, status.behind());
            }
        }
    }

    @Test
    void failsWithANotARepositoryReasonAndReportsUnavailableHealthOutsideARepository() {
        Path outside = temp.resolve("plain");

        var capability = capabilityAt(outside);

        CapabilityFailure failure = failure(capability.status());
        assertEquals("Not a Git repository", failure.reason());
        ProviderHealth health = capability.health();
        assertEquals(ProviderHealth.State.UNAVAILABLE, health.state());
        assertTrue(health.reason().startsWith("Not a Git repository"));
    }

    @Test
    void reportsAvailableHealthInsideARepository() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve("a.txt"), "one\n");
            commitAll(git, "initial");

            assertTrue(capabilityAt(repo).health().isAvailable());
        }
    }

    @Test
    void diffOfAModifiedFileShowsTheChangeAgainstHeadWithGitPrefixes() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve("a.txt"), "one\n");
            commitAll(git, "initial");
            Files.writeString(repo.resolve("a.txt"), "two\n");

            String diff = value(capabilityAt(repo).diff(Path.of("a.txt")));

            assertTrue(diff.contains("--- a/a.txt"), diff);
            assertTrue(diff.contains("+++ b/a.txt"), diff);
            assertTrue(diff.contains("-one"), diff);
            assertTrue(diff.contains("+two"), diff);
        }
    }

    @Test
    void diffComparesAgainstHeadNotAgainstTheIndex() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve("a.txt"), "one\n");
            commitAll(git, "initial");
            Files.writeString(repo.resolve("a.txt"), "two\n");
            git.add().addFilepattern("a.txt").call();
            Files.writeString(repo.resolve("a.txt"), "three\n");

            String diff = value(capabilityAt(repo).diff(Path.of("a.txt")));

            assertTrue(diff.contains("-one"), diff);
            assertTrue(diff.contains("+three"), diff);
            assertFalse(diff.contains("+two"), diff);
        }
    }

    @Test
    void diffOfAnUntrackedFileIsEmptyBecauseGitDoesNotTrackIt() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve("a.txt"), "one\n");
            commitAll(git, "initial");
            Files.writeString(repo.resolve("fresh.txt"), "new\n");

            assertEquals("", value(capabilityAt(repo).diff(Path.of("fresh.txt"))));
        }
    }

    @Test
    void diffOfADeletedFileShowsEveryLineAsRemoved() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve("gone.txt"), "last line\n");
            commitAll(git, "initial");
            Files.delete(repo.resolve("gone.txt"));

            String diff = value(capabilityAt(repo).diff(Path.of("gone.txt")));

            assertTrue(diff.contains("-last line"), diff);
            assertTrue(diff.contains("+++ /dev/null"), diff);
        }
    }

    @Test
    void relativePathsResolveAgainstTheProjectRootInsideTheRepository() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Path project = repo.resolve("src");
        Files.createDirectories(project);
        try (Git git = initRepository(repo)) {
            Files.writeString(project.resolve("Main.java"), "class Main {}\n");
            commitAll(git, "initial");
            Files.writeString(project.resolve("Main.java"), "class Changed {}\n");

            String diff = value(capabilityAt(project).diff(Path.of("Main.java")));

            assertTrue(diff.contains("--- a/src/Main.java"), diff);
            assertTrue(diff.contains("+++ b/src/Main.java"), diff);
        }
    }

    @Test
    void workingTreeDiffCoversTrackedChangesButNotUntrackedFiles() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve("a.txt"), "one\n");
            commitAll(git, "initial");
            Files.writeString(repo.resolve("a.txt"), "two\n");
            Files.writeString(repo.resolve("untracked.txt"), "ignored by diff\n");

            String diff = value(capabilityAt(repo).diffWorkingTree());

            assertTrue(diff.contains("--- a/a.txt"), diff);
            assertFalse(diff.contains("untracked.txt"), diff);
        }
    }

    @Test
    void diffOfAnUnbornRepositoryShowsStagedFilesAsNewFiles() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve("first.txt"), "hello\n");
            git.add().addFilepattern("first.txt").call();

            Status status = value(capabilityAt(repo).status());
            String diff = value(capabilityAt(repo).diffWorkingTree());

            assertEquals(List.of(new Entry(State.ADDED, "first.txt")), status.entries());
            assertTrue(diff.contains("+++ b/first.txt"), diff);
        }
    }

    @Test
    void pathsOutsideTheRepositoryFailWithAnExplicitReason() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Path outside = temp.resolve("outside.txt");
        Files.createDirectories(repo);
        Files.writeString(outside, "secret\n");
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve("a.txt"), "one\n");
            commitAll(git, "initial");
            Files.writeString(repo.resolve("a.txt"), "two\n");
            var capability = capabilityAt(repo);

            assertPathOutside(capability.diff(outside));
            assertPathOutside(capability.diff(Path.of("../outside.txt")));
        }
    }

    @Test
    void symlinkInsideTheRepositoryPointingOutsideFailsWithAnExplicitReason() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Path outside = temp.resolve("outside.txt");
        Files.createDirectories(repo);
        Files.writeString(outside, "secret\n");
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve("a.txt"), "one\n");
            commitAll(git, "initial");
            Files.createSymbolicLink(repo.resolve("link.txt"), outside);

            assertPathOutside(capabilityAt(repo).diff(Path.of("link.txt")));
        }
    }

    @Test
    void reportsAMergeConflictAsUnmerged() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            var config = git.getRepository().getConfig();
            config.setString("merge", null, "conflictStyle", "merge");
            config.save();
            Files.writeString(repo.resolve("a.txt"), "base\n");
            commitAll(git, "initial");
            git.checkout().setCreateBranch(true).setName("topic").call();
            Files.writeString(repo.resolve("a.txt"), "topic side\n");
            commitAll(git, "topic change");
            git.checkout().setName("main").call();
            Files.writeString(repo.resolve("a.txt"), "main side\n");
            commitAll(git, "main change");
            git.merge().include(git.getRepository().resolve("topic")).call();

            Status status = value(capabilityAt(repo).status());

            assertEquals(List.of(new Entry(State.UNMERGED, "a.txt")), status.entries());
        }
    }

    @Test
    void ignoredFilesAreNeitherReportedNorDiffed() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo.resolve("build"));
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve(".gitignore"), "build/\n*.log\n");
            Files.writeString(repo.resolve("a.txt"), "one\n");
            commitAll(git, "initial");
            Files.writeString(repo.resolve("build/out.txt"), "artifact\n");
            Files.writeString(repo.resolve("debug.log"), "noise\n");
            Files.writeString(repo.resolve("a.txt"), "two\n");
            var capability = capabilityAt(repo);

            Status status = value(capability.status());
            String diff = value(capability.diffWorkingTree());

            assertEquals(List.of(new Entry(State.MODIFIED, "a.txt")), status.entries());
            assertFalse(diff.contains("out.txt"), diff);
            assertFalse(diff.contains("debug.log"), diff);
        }
    }

    @Test
    void untrackedFilesInsideAnUntrackedDirectoryAreListedByTheirOwnPath() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo.resolve("fresh/deep"));
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve("a.txt"), "one\n");
            commitAll(git, "initial");
            Files.writeString(repo.resolve("fresh/deep/new.txt"), "new\n");

            Status status = value(capabilityAt(repo).status());

            assertEquals(List.of(new Entry(State.UNTRACKED, "fresh/deep/new.txt")), status.entries());
        }
    }

    @Test
    void anUnbornBranchReportsItsNameAndUntrackedFilesWithoutAnUpstream() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git ignored = initRepository(repo)) {
            Files.writeString(repo.resolve("first.txt"), "hello\n");

            var capability = capabilityAt(repo);
            Status status = value(capability.status());

            assertEquals("main", status.branch());
            assertNull(status.ahead());
            assertEquals(List.of(new Entry(State.UNTRACKED, "first.txt")), status.entries());
            assertEquals("", value(capability.diffWorkingTree()));
            assertTrue(capability.health().isAvailable());
        }
    }

    @Test
    void aFileRemovedFromTheIndexShowsAsDeletedInStatusAndDiff() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve("a.txt"), "one\n");
            Files.writeString(repo.resolve("b.txt"), "two\n");
            commitAll(git, "initial");
            git.rm().addFilepattern("b.txt").call();

            var capability = capabilityAt(repo);

            assertEquals(List.of(new Entry(State.DELETED, "b.txt")), value(capability.status()).entries());
            String diff = value(capability.diff(Path.of("b.txt")));
            assertTrue(diff.contains("-two"), diff);
            assertTrue(diff.contains("+++ /dev/null"), diff);
        }
    }

    @Test
    void aBinaryFileDiffReportsAMarkerInsteadOfRawBytes() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            Files.write(repo.resolve("image.bin"), new byte[] {0, 1, 2, 3, 0, 5});
            commitAll(git, "initial");
            Files.write(repo.resolve("image.bin"), new byte[] {0, 9, 8, 7, 0, 6, 0});

            String diff = value(capabilityAt(repo).diff(Path.of("image.bin")));

            assertTrue(diff.contains("Binary files differ"), diff);
            assertFalse(diff.indexOf('\0') >= 0, "a binary diff never carries raw bytes");
        }
    }

    @Test
    void aDirectoryDiffCoversItsOwnFilesAndNotASiblingSharingTheNamePrefix() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo.resolve("src"));
        Files.createDirectories(repo.resolve("src2"));
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve("src/A.java"), "class A {}\n");
            Files.writeString(repo.resolve("src2/B.java"), "class B {}\n");
            commitAll(git, "initial");
            Files.writeString(repo.resolve("src/A.java"), "class A2 {}\n");
            Files.writeString(repo.resolve("src2/B.java"), "class B2 {}\n");
            var capability = capabilityAt(repo);

            String diff = value(capability.diff(Path.of("src")));

            assertTrue(diff.contains("a/src/A.java"), diff);
            assertFalse(diff.contains("src2/B.java"), diff);
            String rootDiff = value(capability.diff(repo));
            assertTrue(rootDiff.contains("a/src/A.java") && rootDiff.contains("a/src2/B.java"), rootDiff);
        }
    }

    @Test
    void repeatedCallsDoNotLeakFileDescriptors() throws IOException, GitAPIException {
        Path descriptors = Path.of("/proc/self/fd");
        assumeTrue(Files.isDirectory(descriptors), "needs /proc to count open file descriptors");
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            for (int index = 0; index < 20; index++) {
                Files.writeString(repo.resolve("file" + index + ".txt"), "content " + index + "\n");
            }
            commitAll(git, "initial");
            git.gc().call();
            Files.writeString(repo.resolve("file0.txt"), "changed\n");
            var capability = capabilityAt(repo);
            capability.status();
            capability.diffWorkingTree();
            long before = openDescriptors(descriptors);

            for (int call = 0; call < 100; call++) {
                capability.status();
                capability.diffWorkingTree();
                capability.diff(Path.of("file0.txt"));
                capability.health();
            }

            long growth = openDescriptors(descriptors) - before;
            assertTrue(growth < 20, "open file descriptors grew by " + growth);
        }
    }

    @Test
    void recordsRejectBlankNamesAndCopyTheirEntries() {
        assertThrows(IllegalArgumentException.class, () -> new Status(" ", null, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Entry(State.MODIFIED, " "));
        assertThrows(NullPointerException.class, () -> new Entry(null, "a.txt"));
        assertTrue(new Status("main", null, null, null).entries().isEmpty());
        var mutable = new ArrayList<>(List.of(new Entry(State.MODIFIED, "a.txt")));
        Status status = new Status("main", 1, 2, mutable);
        mutable.clear();
        assertEquals(1, status.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> status.entries().clear());
    }

    @Test
    void aDiffLargerThanTheCapIsTruncatedWithAnExplicitMarker() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve("big.txt"), "first\n");
            commitAll(git, "initial");
            String line = "x".repeat(512) + "\n";
            Files.writeString(repo.resolve("big.txt"), line.repeat(800));

            String diff = value(capabilityAt(repo).diffWorkingTree());

            assertTrue(diff.startsWith("diff --git a/big.txt b/big.txt"), diff);
            assertTrue(diff.contains("@@"), diff);
            assertTrue(diff.endsWith("[The diff is truncated at the 256 KiB cap]\n"), diff);
            assertTrue(diff.length() < 300_000, "the truncated diff stays near the 256 KiB cap, got "
                    + diff.length() + " characters");
        }
    }

    @Test
    void aSmallDiffCarriesNoTruncationMarker() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve("a.txt"), "one\n");
            commitAll(git, "initial");
            Files.writeString(repo.resolve("a.txt"), "two\n");

            String diff = value(capabilityAt(repo).diff(Path.of("a.txt")));

            assertFalse(diff.contains("256 KiB"), diff);
        }
    }

    @Test
    void requiresConfirmationAndConfinesIndexMutationsToTheProject() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve("tracked.txt"), "first\n");
            commitAll(git, "initial");
            Files.writeString(repo.resolve("tracked.txt"), "second\n");
            var capability = capabilityAt(repo);

            assertEquals("Confirmation is required for this Git operation",
                    failure(capability.stage(Path.of("tracked.txt"), false)).reason());
            assertEquals("The path is outside the project root",
                    failure(capability.stage(temp.resolve("outside.txt"), true)).reason());

            assertEquals(State.MODIFIED, value(capability.status()).entries().getFirst().state());
            assertTrue(value(capability.stage(Path.of("tracked.txt"), true)));
            assertTrue(git.status().call().getChanged().contains("tracked.txt"));
            assertTrue(value(capability.unstage(Path.of("tracked.txt"), true)));
            assertEquals(State.MODIFIED, value(capability.status()).entries().getFirst().state());
        }
    }

    @Test
    void commitsOnlyStagedChangesAndSwitchesOnlyToAnExistingLocalBranch()
            throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            git.getRepository().getConfig().setString("user", null, "name", AUTHOR);
            git.getRepository().getConfig().setString("user", null, "email", EMAIL);
            git.getRepository().getConfig().save();
            Files.writeString(repo.resolve("tracked.txt"), "first\n");
            commitAll(git, "initial");
            git.branchCreate().setName("feature").call();
            Files.writeString(repo.resolve("tracked.txt"), "second\n");
            var capability = capabilityAt(repo);

            assertEquals("Confirmation is required for this Git operation",
                    failure(capability.commit("no confirmation", false)).reason());
            assertEquals("The staged changes could not be committed",
                    failure(capability.commit("nothing staged", true)).reason());
            assertEquals("The local branch could not be checked out",
                    failure(capability.switchBranch("missing", true)).reason());
            assertEquals("main", value(capability.status()).branch());

            assertTrue(value(capability.stage(Path.of("tracked.txt"), true)));
            assertTrue(value(capability.commit("update tracked file", true)));
            assertTrue(value(capability.status()).entries().isEmpty());
            assertTrue(value(capability.switchBranch("feature", true)));
            assertEquals("feature", value(capability.status()).branch());
        }
    }

    @Test
    void stagesDeletedAndSpecialCharacterPathsAndCanUnstageInAnUnbornRepository()
            throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            Path special = repo.resolve("space and ü.txt");
            Files.writeString(special, "first\n");
            Files.writeString(repo.resolve("deleted.txt"), "tracked\n");
            commitAll(git, "initial");
            Files.writeString(special, "second\n");
            Files.delete(repo.resolve("deleted.txt"));
            var capability = capabilityAt(repo);

            assertTrue(value(capability.stage(Path.of("space and ü.txt"), true)));
            assertTrue(git.status().call().getChanged().contains("space and ü.txt"));
            assertTrue(value(capability.stage(Path.of("deleted.txt"), true)));
            assertTrue(git.status().call().getRemoved().contains("deleted.txt"));
            assertTrue(value(capability.unstage(Path.of("deleted.txt"), true)));
            assertEquals(State.DELETED, value(capability.status()).entries().stream()
                    .filter(entry -> entry.path().equals("deleted.txt")).findFirst().orElseThrow().state());
        }

        Path unborn = temp.resolve("unborn");
        Files.createDirectories(unborn);
        try (Git ignored = initRepository(unborn)) {
            Files.writeString(unborn.resolve("initial.txt"), "uncommitted\n");
            var capability = capabilityAt(unborn);

            assertTrue(value(capability.stage(Path.of("initial.txt"), true)));
            assertTrue(value(capability.unstage(Path.of("initial.txt"), true)));
            assertEquals(List.of(new Entry(State.UNTRACKED, "initial.txt")), value(capability.status()).entries());
        }
    }

    @Test
    void aProjectRootBelowTheRepositoryRootSeesOnlyItsOwnPaths() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Path project = repo.resolve("app");
        Files.createDirectories(project);
        try (Git git = initRepository(repo)) {
            Files.writeString(project.resolve("inside.txt"), "one\n");
            Files.writeString(repo.resolve("outside.txt"), "one\n");
            commitAll(git, "initial");
            Files.writeString(project.resolve("inside.txt"), "two\n");
            Files.writeString(repo.resolve("outside.txt"), "two\n");
            Files.writeString(project.resolve("new.txt"), "not tracked\n");
            Files.writeString(repo.resolve("other-new.txt"), "not tracked\n");
            var capability = capabilityAt(project);

            Status status = value(capability.status());

            assertEquals("main", status.branch());
            assertFalse(status.truncated());
            assertEquals(List.of(
                    new Entry(State.MODIFIED, "inside.txt"),
                    new Entry(State.UNTRACKED, "new.txt")),
                    status.entries());
            String diff = value(capability.diffWorkingTree());
            assertTrue(diff.contains("diff --git a/app/inside.txt b/app/inside.txt"), diff);
            assertFalse(diff.contains("outside.txt"), diff);
            assertTrue(value(capability.diff(Path.of("inside.txt")))
                    .contains("diff --git a/app/inside.txt b/app/inside.txt"));
            assertPathOutside(capability.diff(Path.of("../outside.txt")));
            assertPathOutside(capability.diff(repo.resolve("outside.txt")));
        }
    }

    @Test
    void aSymlinkInsideANestedProjectPointingToASiblingIsRefused() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Path project = repo.resolve("app");
        Files.createDirectories(project);
        try (Git git = initRepository(repo)) {
            Files.writeString(project.resolve("inside.txt"), "one\n");
            Files.writeString(repo.resolve("sibling.txt"), "one\n");
            commitAll(git, "initial");
            Files.createSymbolicLink(project.resolve("escape.txt"), Path.of("..", "sibling.txt"));

            assertPathOutside(capabilityAt(project).diff(Path.of("escape.txt")));
        }
    }

    @Test
    void theStatusListsAtMostTheBoundAndSaysSoExplicitly() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = initRepository(repo)) {
            Files.writeString(repo.resolve("a.txt"), "one\n");
            commitAll(git, "initial");
            for (int index = 0; index <= LocalVersionControlCapability.MAX_STATUS_ENTRIES; index++) {
                Files.writeString(repo.resolve("untracked-" + index + ".txt"), "not tracked\n");
            }

            Status status = value(capabilityAt(repo).status());

            assertEquals(LocalVersionControlCapability.MAX_STATUS_ENTRIES, status.entries().size());
            assertTrue(status.truncated());
        }
    }

    @Test
    void aGlobalConfigurationOfTheUserHomeNeverChangesTheStatusOrTheDiff()
            throws IOException, GitAPIException, ConfigInvalidException {
        Path home = temp.resolve("home");
        Path ignores = home.resolve("ignore");
        Path hooks = home.resolve("hooks");
        Path marker = home.resolve("hook-ran");
        Files.createDirectories(hooks);
        Files.writeString(ignores, "*.txt\n");
        Path hook = hooks.resolve("pre-commit");
        Files.writeString(hook, "#!/bin/sh\ntouch '" + slashed(marker) + "'\n");
        assertTrue(hook.toFile().setExecutable(true));
        Files.writeString(home.resolve(".gitconfig"), """
                [diff]
                    noprefix = true
                [core]
                    excludesFile = %s
                    hooksPath = %s
                """.formatted(slashed(ignores), slashed(hooks)));
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            FileBasedConfig global = new FileBasedConfig(null, home.resolve(".gitconfig").toFile(), FS.DETECTED);
            global.load();
            assertTrue(global.getBoolean("diff", "noprefix", false), "the trap configuration is in place");

            Path repo = temp.resolve("repo");
            Files.createDirectories(repo);
            try (Git git = initRepository(repo)) {
                Files.writeString(repo.resolve("a.txt"), "one\n");
                commitAll(git, "initial");
                assertFalse(Files.exists(marker), "the global hook must not run");
                Files.writeString(repo.resolve("a.txt"), "two\n");
                Files.writeString(repo.resolve("note.txt"), "not tracked\n");
                var capability = capabilityAt(repo);

                assertTrue(value(capability.status()).entries().contains(new Entry(State.UNTRACKED, "note.txt")));
                assertTrue(value(capability.diff(Path.of("a.txt"))).startsWith("diff --git a/a.txt b/a.txt"));
            }
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    private static long openDescriptors(Path descriptors) throws IOException {
        try (var stream = Files.list(descriptors)) {
            return stream.count();
        }
    }

    private static Git initRepository(Path directory) throws GitAPIException {
        return Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call();
    }

    private static void commitAll(Git git, String message) throws GitAPIException {
        git.add().addFilepattern(".").call();
        git.commit().setMessage(message).setAuthor(AUTHOR, EMAIL).setCommitter(AUTHOR, EMAIL).setSign(false).call();
    }

    private static void assertPathOutside(CapabilityResult<String> result) {
        assertEquals("The path is outside the project root",
                assertInstanceOf(CapabilityResult.Failed.class, result).failure().reason());
    }

    private static String slashed(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static CapabilityFailure failure(CapabilityResult<?> result) {
        return assertInstanceOf(CapabilityResult.Failed.class, result).failure();
    }

    private static <T> T value(CapabilityResult<T> result) {
        if (result instanceof CapabilityResult.Ok<T> ok) {
            return ok.value();
        }
        throw new AssertionError("Expected a value but got " + failure(result));
    }
}
