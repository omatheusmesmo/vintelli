package dev.omatheusmesmo.vintelli.api.capability;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads the state of the Git repository that contains the project: worktree
 * statuses, the current branch, ahead and behind counts and unified diffs of
 * single paths or of the whole working tree against {@code HEAD}.
 *
 * <p>Writing operations require an explicit confirmation flag; callers must
 * obtain a separate user confirmation before passing {@code true}. The project root may sit
 * below the repository root: the entries of a status and every diff stay
 * inside the project root, while the branch and the tracking counts describe
 * the whole repository. Ports never declare checked exceptions: an expected
 * failure (not a repository, a path outside the project root, an unreadable
 * index) comes back as a {@link CapabilityResult#failed(CapabilityFailure)}.
 */
public interface VersionControlCapability extends ProviderDescriptor {

    /**
     * @return the worktree status: the current branch, the ahead and behind
     *         counts against the upstream branch and one entry per changed
     *         path under the project root, or a failed result when the project
     *         is not inside a Git repository
     */
    CapabilityResult<Status> status();

    /**
     * @param path the file or directory to diff, absolute or relative to the
     *             project root
     * @return a unified diff of the path against {@code HEAD}, empty when
     *         nothing changed, or a failed result when the path resolves
     *         outside the project root. Untracked paths produce no diff
     *         because Git does not track them yet
     */
    CapabilityResult<String> diff(Path path);

    /**
     * @return a unified diff of the project tree against {@code HEAD}, which is
     *         the whole repository worktree when the project root is the
     *         repository root, empty when nothing is changed
     */
    CapabilityResult<String> diffWorkingTree();

    /**
     * @return a unified diff of the index against {@code HEAD}. Providers
     *         without a separate staged-diff implementation report a failure
     *         rather than mixing unstaged changes into a commit summary
     */
    default CapabilityResult<String> diffStaged() {
        return CapabilityResult.failed(new CapabilityFailure(
                "Staged diff unavailable", "The provider does not support staged diffs"));
    }

    /**
     * @return the author configured by Git for this repository, or an empty
     *         value when the caller should ask the user for an identity
     */
    default CapabilityResult<Optional<CommitAuthor>> configuredCommitAuthor() {
        return CapabilityResult.ok(Optional.empty());
    }

    /** Adds one project file to the Git index after the caller confirms. */
    CapabilityResult<Boolean> stage(Path path, boolean confirmed);

    /** Removes one project file from the Git index after the caller confirms. */
    CapabilityResult<Boolean> unstage(Path path, boolean confirmed);

    /** Commits the staged index with the supplied message after confirmation. */
    CapabilityResult<Boolean> commit(String message, boolean confirmed);

    /** Replaces the current commit with the staged index and supplied message after confirmation. */
    default CapabilityResult<Boolean> commitAmend(String message, boolean confirmed) {
        return CapabilityResult.failed(new CapabilityFailure(
                "Amend unavailable", "The provider does not support amending commits"));
    }

    /** Commits or amends, supplying an author only when Git has no configured identity. */
    default CapabilityResult<Boolean> commit(String message, CommitAuthor author, boolean amend, boolean confirmed) {
        if (author != null) {
            return CapabilityResult.failed(new CapabilityFailure(
                    "Commit author unavailable", "The provider does not support an author override"));
        }
        return amend ? commitAmend(message, confirmed) : commit(message, confirmed);
    }

    /** Checks out an existing local branch without forcing worktree changes. */
    CapabilityResult<Boolean> switchBranch(String branch, boolean confirmed);

    /**
     * @return the local branches sorted by name, with the checked out one
     *         marked as current. No branch is marked while HEAD is detached
     */
    default CapabilityResult<List<Branch>> branches() {
        return CapabilityResult.failed(new CapabilityFailure(
                "Branches unavailable", "The provider does not list branches"));
    }

    /**
     * Creates a local branch at {@code HEAD} after the caller confirms. The
     * new branch is not checked out.
     */
    default CapabilityResult<Boolean> createBranch(String name, boolean confirmed) {
        return CapabilityResult.failed(new CapabilityFailure(
                "Branch creation unavailable", "The provider does not support creating branches"));
    }

    /**
     * Deletes a local branch after the caller confirms. The checked out branch
     * is never deleted, and a branch with commits that are not merged into
     * {@code HEAD} is deleted only when {@code force} is true.
     */
    default CapabilityResult<Boolean> deleteBranch(String name, boolean force, boolean confirmed) {
        return CapabilityResult.failed(new CapabilityFailure(
                "Branch deletion unavailable", "The provider does not support deleting branches"));
    }

    /**
     * Renames a local branch after the caller confirms. Renaming the checked
     * out branch keeps it checked out under the new name.
     */
    default CapabilityResult<Boolean> renameBranch(String oldName, String newName, boolean confirmed) {
        return CapabilityResult.failed(new CapabilityFailure(
                "Branch rename unavailable", "The provider does not support renaming branches"));
    }

    /**
     * Fetches one configured remote after the caller confirms. Only the
     * remote-tracking refs move; the worktree and the checked out branch do not.
     *
     * @return a short summary of the refs that changed on the remote, or a
     *         failed result when the remote is missing or cannot be reached
     */
    default CapabilityResult<String> fetch(String remote, boolean confirmed) {
        return CapabilityResult.failed(new CapabilityFailure(
                "Fetch unavailable", "The provider does not fetch from remotes"));
    }

    /**
     * Fetches the upstream of the checked out branch and integrates it, as a
     * fast-forward when possible and as a merge otherwise, after the caller
     * confirms. A merge that conflicts fails with the conflicted paths as the
     * detail and leaves the merge in progress for the user to resolve.
     *
     * @return a short summary of the integration, or a failed result when the
     *         branch has no upstream, the remote cannot be reached or the merge
     *         stops on conflicts
     */
    default CapabilityResult<String> pull(boolean confirmed) {
        return CapabilityResult.failed(new CapabilityFailure(
                "Pull unavailable", "The provider does not pull from remotes"));
    }

    /**
     * Pushes the checked out branch to its upstream after the caller confirms.
     * Nothing is forced: a remote branch that holds commits missing locally
     * rejects the push.
     *
     * @return a short summary of the push, or a failed result when the branch
     *         has no upstream, the remote cannot be reached or the push is rejected
     */
    default CapabilityResult<String> push(boolean confirmed) {
        return CapabilityResult.failed(new CapabilityFailure(
                "Push unavailable", "The provider does not push to remotes"));
    }

    /**
     * Pushes the checked out branch to the named remote under the same name
     * and records that remote branch as its upstream, after the caller confirms.
     * The upstream is recorded only when the push succeeds.
     *
     * @return a short summary of the push, or a failed result when the remote
     *         is missing, the remote cannot be reached or the push is rejected
     */
    default CapabilityResult<String> pushSetUpstream(String remote, boolean confirmed) {
        return CapabilityResult.failed(new CapabilityFailure(
                "Push with upstream unavailable", "The provider does not push to remotes"));
    }

    /**
     * One local branch of the repository. {@code current} is true for the
     * branch that HEAD points at.
     */
    record Branch(String name, boolean current) {
    }

    /**
     * Snapshot of the worktree status. {@code branch} names the checked out
     * branch and is {@code null} when HEAD is detached. {@code ahead} and
     * {@code behind} count commits against the upstream branch and are
     * {@code null} when no upstream is configured. {@code truncated} is true
     * when the provider stopped listing entries at its bound, so the list is a
     * prefix of the changed paths and not the whole of them.
     */
    record Status(String branch, Integer ahead, Integer behind, List<Entry> entries, boolean truncated) {

        public Status {
            if (branch != null && branch.isBlank()) {
                throw new IllegalArgumentException("A status branch name is never blank");
            }
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        public Status(String branch, Integer ahead, Integer behind, List<Entry> entries) {
            this(branch, ahead, behind, entries, false);
        }

        /**
         * @return true when HEAD does not point at a branch, so no branch name
         *         is reported
         */
        public boolean isDetached() {
            return branch == null;
        }
    }

    /**
     * One changed path of the worktree status, relative to the project root.
     */
    record Entry(State state, String path) {

        public Entry {
            Objects.requireNonNull(state, "state");
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("A worktree entry always has a project relative path");
            }
        }
    }

    /** Git author identity requested from the user when the repository has no configured identity. */
    record CommitAuthor(String name, String email) {

        public CommitAuthor {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(email, "email");
            name = name.strip();
            email = email.strip();
            if (name.isEmpty() || email.isEmpty()) {
                throw new IllegalArgumentException("A commit author needs a name and email");
            }
        }
    }

    /**
     * Flattened state of one {@link Entry} for the project tree and the tabs.
     * When the index and the worktree disagree about a path, the state that
     * wins is the first one in declaration order: unmerged beats added, added
     * beats deleted, and every other change reads as modified. Untracked files
     * come last.
     */
    enum State {
        UNMERGED,
        ADDED,
        DELETED,
        MODIFIED,
        UNTRACKED
    }
}
