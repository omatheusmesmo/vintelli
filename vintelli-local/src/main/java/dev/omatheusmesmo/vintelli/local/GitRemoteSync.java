package dev.omatheusmesmo.vintelli.local;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;

/**
 * Fetch, pull and push of the local provider, run against the repository that
 * {@link LocalVersionControlCapability} opened. Nothing is forced: a push that
 * the remote would not fast-forward is reported as rejected, and a pull never
 * rewrites history. Authentication is left to JGit's defaults, and every
 * message that can carry a transport error has its URL userinfo removed.
 *
 * <p>A pull is a merge with {@code pull.rebase} forced off, so the outcome
 * never depends on the user configuration. A conflicting merge stays in
 * progress, the way {@code git pull} leaves it, so the user can resolve the
 * listed paths and commit.
 */
final class GitRemoteSync {

    private static final String FETCH_FAILED = "The remote could not be fetched";
    private static final String PULL_FAILED = "The branch could not be pulled";
    private static final String PULL_CONFLICTS = "The pull stopped on merge conflicts";
    private static final String PUSH_FAILED = "The branch could not be pushed";
    private static final String PUSH_REJECTED = "The push was rejected";
    private static final String NO_REMOTE = "No remote configured";
    private static final String NO_UPSTREAM = "The current branch has no upstream";
    private static final String DETACHED_HEAD = "HEAD is detached, so no branch can be synchronized";
    private static final String REMOTE_NAME_REQUIRED = "A remote name is required";
    private static final String NO_REMOTES_DETAIL = "The repository has no remotes";
    private static final Pattern USERINFO = Pattern.compile("(?<=://)[^/\\s]*@");

    private GitRemoteSync() {
    }

    static CapabilityResult<String> fetch(Repository repository, String remote) {
        String name = remote == null ? "" : remote.strip();
        if (name.isEmpty()) {
            return failed(FETCH_FAILED, REMOTE_NAME_REQUIRED);
        }
        Optional<CapabilityFailure> missing = missingRemote(repository, name, FETCH_FAILED);
        if (missing.isPresent()) {
            return CapabilityResult.failed(missing.get());
        }
        try {
            FetchResult result = Git.wrap(repository).fetch().setRemote(name).call();
            long changed = result.getTrackingRefUpdates().stream()
                    .filter(update -> update.getResult() != RefUpdate.Result.NO_CHANGE)
                    .count();
            if (changed == 0) {
                return CapabilityResult.ok("Fetched " + name + ": already up to date");
            }
            return CapabilityResult.ok("Fetched " + name + ": " + changed
                    + (changed == 1 ? " ref updated" : " refs updated"));
        } catch (GitAPIException failure) {
            return failed(FETCH_FAILED, messageOf(failure));
        }
    }

    static CapabilityResult<String> pull(Repository repository) throws IOException {
        if (repository.getRemoteNames().isEmpty()) {
            return failed(NO_REMOTE, NO_REMOTES_DETAIL);
        }
        String branch = LocalVersionControlCapability.currentBranch(repository);
        if (branch == null) {
            return failed(PULL_FAILED, DETACHED_HEAD);
        }
        BranchConfig tracking = new BranchConfig(repository.getConfig(), branch);
        String remote = tracking.getRemote();
        String upstream = tracking.getMerge();
        if (remote == null || upstream == null) {
            return failed(NO_UPSTREAM, "Record an upstream first by pushing the branch with its upstream set");
        }
        Optional<CapabilityFailure> missing = missingRemote(repository, remote, PULL_FAILED);
        if (missing.isPresent()) {
            return CapabilityResult.failed(missing.get());
        }
        try {
            PullResult result = Git.wrap(repository).pull().setRebase(false).call();
            return pullOutcome(result, remote + "/" + Repository.shortenRefName(upstream));
        } catch (GitAPIException failure) {
            return failed(PULL_FAILED, messageOf(failure));
        }
    }

    static CapabilityResult<String> push(Repository repository) throws IOException {
        if (repository.getRemoteNames().isEmpty()) {
            return failed(NO_REMOTE, NO_REMOTES_DETAIL);
        }
        String branch = LocalVersionControlCapability.currentBranch(repository);
        if (branch == null) {
            return failed(PUSH_FAILED, DETACHED_HEAD);
        }
        BranchConfig tracking = new BranchConfig(repository.getConfig(), branch);
        String remote = tracking.getRemote();
        String upstream = tracking.getMerge();
        if (remote == null || upstream == null) {
            return failed(NO_UPSTREAM, "Record an upstream first by pushing the branch with its upstream set");
        }
        Optional<CapabilityFailure> missing = missingRemote(repository, remote, PUSH_FAILED);
        if (missing.isPresent()) {
            return CapabilityResult.failed(missing.get());
        }
        return pushBranch(repository, remote, branch, upstream, false);
    }

    static CapabilityResult<String> pushSetUpstream(Repository repository, String remote) throws IOException {
        String name = remote == null ? "" : remote.strip();
        if (name.isEmpty()) {
            return failed(PUSH_FAILED, REMOTE_NAME_REQUIRED);
        }
        Optional<CapabilityFailure> missing = missingRemote(repository, name, PUSH_FAILED);
        if (missing.isPresent()) {
            return CapabilityResult.failed(missing.get());
        }
        String branch = LocalVersionControlCapability.currentBranch(repository);
        if (branch == null) {
            return failed(PUSH_FAILED, DETACHED_HEAD);
        }
        return pushBranch(repository, name, branch, Constants.R_HEADS + branch, true);
    }

    /**
     * Redacts the userinfo of every URL in a text, so a password or a token
     * embedded in a remote URL never reaches a message.
     */
    static String redact(String text) {
        return USERINFO.matcher(text).replaceAll("***@");
    }

    private static CapabilityResult<String> pullOutcome(PullResult result, String source) {
        MergeResult merge = result.getMergeResult();
        if (merge == null) {
            return failed(PULL_FAILED, "The pull did not reach the merge step");
        }
        return switch (merge.getMergeStatus()) {
            case ALREADY_UP_TO_DATE -> CapabilityResult.ok("Already up to date with " + source);
            case FAST_FORWARD, FAST_FORWARD_SQUASHED -> CapabilityResult.ok("Fast-forwarded to " + source);
            case MERGED, MERGED_SQUASHED, MERGED_NOT_COMMITTED, MERGED_SQUASHED_NOT_COMMITTED ->
                    CapabilityResult.ok("Merged " + source);
            case CONFLICTING -> failed(PULL_CONFLICTS, "Conflicted paths: " + conflictedPaths(merge)
                    + ". The merge is still in progress: resolve them and commit to finish it");
            default -> failed(PULL_FAILED, "The merge ended with status " + merge.getMergeStatus());
        };
    }

    private static CapabilityResult<String> pushBranch(Repository repository, String remote, String branch,
            String remoteRef, boolean recordUpstream) throws IOException {
        String remoteBranch = Repository.shortenRefName(remoteRef);
        List<RemoteRefUpdate> updates = new ArrayList<>();
        try {
            RefSpec spec = new RefSpec(Constants.R_HEADS + branch + ":" + remoteRef);
            for (PushResult result : Git.wrap(repository).push().setRemote(remote).setRefSpecs(spec).call()) {
                updates.addAll(result.getRemoteUpdates());
            }
        } catch (GitAPIException failure) {
            return failed(PUSH_FAILED, messageOf(failure));
        }
        boolean pushed = false;
        for (RemoteRefUpdate update : updates) {
            switch (update.getStatus()) {
                case OK -> pushed = true;
                case UP_TO_DATE -> {
                }
                case REJECTED_NONFASTFORWARD -> {
                    return failed(PUSH_REJECTED, remote + "/" + remoteBranch
                            + " has commits that are not in the local branch: pull first");
                }
                case REJECTED_REMOTE_CHANGED -> {
                    return failed(PUSH_REJECTED, remote + "/" + remoteBranch
                            + " changed while pushing: fetch and push again");
                }
                default -> {
                    return failed(PUSH_FAILED, describe(update));
                }
            }
        }
        if (recordUpstream) {
            recordUpstream(repository, branch, remote);
        }
        String target = remote + "/" + remoteBranch;
        if (pushed) {
            return CapabilityResult.ok("Pushed " + branch + " to " + target
                    + (recordUpstream ? " and set it as upstream" : ""));
        }
        return CapabilityResult.ok("Nothing to push: " + target + " is up to date");
    }

    private static String describe(RemoteRefUpdate update) {
        String message = update.getMessage();
        return redact(message == null ? update.getStatus().toString() : update.getStatus() + ": " + message);
    }

    private static void recordUpstream(Repository repository, String branch, String remote) throws IOException {
        StoredConfig config = repository.getConfig();
        config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_REMOTE, remote);
        config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_MERGE,
                Constants.R_HEADS + branch);
        config.save();
    }

    private static Optional<CapabilityFailure> missingRemote(Repository repository, String name, String reason) {
        if (repository.getRemoteNames().isEmpty()) {
            return Optional.of(new CapabilityFailure(NO_REMOTE, NO_REMOTES_DETAIL));
        }
        if (!repository.getRemoteNames().contains(name)) {
            return Optional.of(new CapabilityFailure(reason, "Remote not found: " + name));
        }
        return Optional.empty();
    }

    private static String conflictedPaths(MergeResult merge) {
        return String.join(", ", new TreeSet<>(merge.getConflicts().keySet()));
    }

    private static String messageOf(Exception failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return redact(message);
    }

    private static <T> CapabilityResult<T> failed(String reason, String detail) {
        return CapabilityResult.failed(new CapabilityFailure(reason, detail));
    }
}
