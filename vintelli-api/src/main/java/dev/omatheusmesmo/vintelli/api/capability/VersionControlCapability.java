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
