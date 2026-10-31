package dev.omatheusmesmo.vintelli.local;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidget;
import io.quarkus.arc.Unremovable;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Git state of the project through JGit, which needs no {@code git}
 * executable for status and diff (it may run one only to discover system
 * wide configuration when one exists). The repository is discovered upwards
 * from the {@link LocalProjectRoot}, the same way {@code git} itself walks up
 * from the working directory, and opened for each call so the status never
 * goes stale.
 *
 * <p>Diffs compare the tracked content against {@code HEAD}, mirroring
 * {@code git diff HEAD}: untracked files are left out, staged and unstaged
 * changes are both shown, and the {@code a/} and {@code b/} prefixes are always
 * set explicitly so no user configuration can change the format. The project
 * root may sit below the repository root: every path, and every status entry,
 * is confined to the project root after symlink resolution, so nothing outside
 * the project is read or described even when the enclosing repository holds
 * it.
 */
@Unremovable
@Singleton
public final class LocalVersionControlCapability implements VersionControlCapability, AutoCloseable {

    private static final String NOT_A_REPOSITORY = "Not a Git repository";
    private static final String REPOSITORY_NOT_READABLE = "The Git repository cannot be read";
    private static final String PROJECT_OUTSIDE_REPOSITORY = "The project root is outside the repository";
    private static final String PATH_OUTSIDE_PROJECT = "The path is outside the project root";
    private static final String CONFIRMATION_REQUIRED = "Confirmation is required for this Git operation";
    private static final String STAGE_FAILED = "The file could not be staged";
    private static final String UNSTAGE_FAILED = "The file could not be unstaged";
    private static final String COMMIT_FAILED = "The staged changes could not be committed";
    private static final String BRANCH_SWITCH_FAILED = "The local branch could not be checked out";
    private static final int MAX_COMMIT_MESSAGE_LENGTH = 1000;
    private static final int MAX_DIFF_BYTES = 256 * 1024;
    static final int MAX_STATUS_ENTRIES = 1000;
    private static final String TRUNCATION_MARKER = "[The diff is truncated at the 256 KiB cap]\n";
    private static final String OLD_PREFIX = "a/";
    private static final String NEW_PREFIX = "b/";
    private static final long BRANCH_REFRESH_NANOS = Duration.ofSeconds(30).toNanos();

    private final LocalProjectRoot projectRoot;
    private final AtomicBoolean repositoryKnown = new AtomicBoolean();
    private final AtomicBoolean branchRefreshInFlight = new AtomicBoolean();
    private final ExecutorService branchRefreshExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("vintelli-vcs-status-").factory());
    private volatile long branchCacheUpdatedAt;
    private volatile String cachedBranch;

    /**
     * Writes at most {@link #MAX_DIFF_BYTES} bytes and remembers that anything
     * beyond the limit was dropped, so a large worktree never turns into a
     * multi megabyte string on every call.
     */
    private static final class CappedOutputStream extends OutputStream {

        private final int limit;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean truncated;

        CappedOutputStream(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(int b) {
            if (truncated || buffer.size() >= limit) {
                truncated = true;
                return;
            }
            buffer.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (truncated) {
                return;
            }
            int room = limit - buffer.size();
            if (len <= room) {
                buffer.write(b, off, len);
                return;
            }
            if (room > 0) {
                buffer.write(b, off, room);
            }
            truncated = true;
        }

        boolean isTruncated() {
            return truncated;
        }

        String asString(Charset charset) {
            return buffer.toString(charset);
        }
    }

    @Inject
    LocalVersionControlCapability(LocalProjectRoot projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String identifier() {
        return "local";
    }

    /**
     * Once a repository has been found it stays known for the life of the bean,
     * so the registry pays nothing on later selections. A project that is not
     * inside a repository is probed again on every call, which costs a few
     * directory checks, so a {@code git init} after the first read is noticed.
     * {@link #status()} and the diffs still open the repository per call and
     * report its absence if it disappears.
     */
    @Override
    public ProviderHealth health() {
        if (repositoryKnown.get()) {
            return ProviderHealth.available();
        }
        ProviderHealth health = discoverHealth();
        if (health.isAvailable()) {
            repositoryKnown.set(true);
        }
        return health;
    }

    @Override
    public String provenance() {
        return "JGit, repository discovered from the project root";
    }

    @Override
    public List<StatusBarWidget> statusBarWidgets(CapabilityType capability) {
        if (capability != CapabilityType.VERSION_CONTROL) {
            return List.of();
        }
        refreshBranchAsyncIfStale();
        String branch = cachedBranch;
        String value = branch == null ? "loading" : branch;
        return List.of(new StatusBarWidget("vcs-branch", 7, "Branch: " + value, "Git:" + value));
    }

    @Override
    @PreDestroy
    public void close() {
        branchRefreshExecutor.shutdownNow();
        try {
            branchRefreshExecutor.awaitTermination(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public CapabilityResult<VersionControlCapability.Status> status() {
        return withRepository(this::readStatus);
    }

    @Override
    public CapabilityResult<String> diff(Path path) {
        return withRepository((repository, projectPrefix) -> {
            Optional<String> relative = repositoryRelative(repository, path);
            if (relative.isEmpty()) {
                return CapabilityResult.failed(new CapabilityFailure(PATH_OUTSIDE_PROJECT, path.toString()));
            }
            return diffAgainstHead(repository, relative.get());
        });
    }

    @Override
    public CapabilityResult<String> diffWorkingTree() {
        return withRepository((repository, projectPrefix) -> diffAgainstHead(repository, projectPrefix));
    }

    @Override
    public CapabilityResult<Boolean> stage(Path path, boolean confirmed) {
        if (!confirmed) {
            return confirmationRequired();
        }
        if (path == null) {
            return pathOutsideProject(null);
        }
        return withRepository((repository, projectPrefix) -> {
            Optional<String> relative = repositoryRelative(repository, path);
            if (relative.isEmpty() || relative.get().isBlank()) {
                return pathOutsideProject(path);
            }
            try {
                var add = Git.wrap(repository).add().addFilepattern(relative.get());
                Path worktreePath = repository.getWorkTree().toPath().resolve(relative.get());
                if (!Files.exists(worktreePath) && repository.readDirCache().getEntry(relative.get()) != null) {
                    add.setUpdate(true);
                }
                add.call();
                return CapabilityResult.ok(true);
            } catch (GitAPIException failure) {
                return CapabilityResult.failed(new CapabilityFailure(STAGE_FAILED, messageOf(failure)));
            }
        });
    }

    @Override
    public CapabilityResult<Boolean> unstage(Path path, boolean confirmed) {
        if (!confirmed) {
            return confirmationRequired();
        }
        if (path == null) {
            return pathOutsideProject(null);
        }
        return withRepository((repository, projectPrefix) -> {
            Optional<String> relative = repositoryRelative(repository, path);
            if (relative.isEmpty() || relative.get().isBlank()) {
                return pathOutsideProject(path);
            }
            try {
                Git.wrap(repository).reset().addPath(relative.get()).call();
                return CapabilityResult.ok(true);
            } catch (GitAPIException failure) {
                return CapabilityResult.failed(new CapabilityFailure(UNSTAGE_FAILED, messageOf(failure)));
            }
        });
    }

    @Override
    public CapabilityResult<Boolean> commit(String message, boolean confirmed) {
        if (!confirmed) {
            return confirmationRequired();
        }
        if (message == null || message.isBlank() || message.length() > MAX_COMMIT_MESSAGE_LENGTH) {
            return CapabilityResult.failed(new CapabilityFailure(COMMIT_FAILED,
                    "The commit message must contain 1 to " + MAX_COMMIT_MESSAGE_LENGTH + " characters"));
        }
        return withRepository((repository, projectPrefix) -> {
            try {
                var status = Git.wrap(repository).status().call();
                if (status.getAdded().isEmpty() && status.getChanged().isEmpty() && status.getRemoved().isEmpty()) {
                    return CapabilityResult.failed(new CapabilityFailure(COMMIT_FAILED,
                            "There are no staged changes to commit"));
                }
                Git.wrap(repository).commit().setMessage(message.strip()).call();
                return CapabilityResult.ok(true);
            } catch (GitAPIException failure) {
                return CapabilityResult.failed(new CapabilityFailure(COMMIT_FAILED, messageOf(failure)));
            }
        });
    }

    @Override
    public CapabilityResult<Boolean> switchBranch(String branch, boolean confirmed) {
        if (!confirmed) {
            return confirmationRequired();
        }
        String name = branch == null ? "" : branch.strip();
        if (!Repository.isValidRefName(Constants.R_HEADS + name)) {
            return CapabilityResult.failed(new CapabilityFailure(BRANCH_SWITCH_FAILED, "Invalid local branch name"));
        }
        return withRepository((repository, projectPrefix) -> {
            if (repository.exactRef(Constants.R_HEADS + name) == null) {
                return CapabilityResult.failed(new CapabilityFailure(BRANCH_SWITCH_FAILED,
                        "Local branch not found: " + name));
            }
            try {
                Git.wrap(repository).checkout().setName(name).call();
                return CapabilityResult.ok(true);
            } catch (GitAPIException failure) {
                return CapabilityResult.failed(new CapabilityFailure(BRANCH_SWITCH_FAILED, messageOf(failure)));
            }
        });
    }

    private static CapabilityResult<Boolean> confirmationRequired() {
        return CapabilityResult.failed(new CapabilityFailure(CONFIRMATION_REQUIRED, "Confirm the action first"));
    }

    private static CapabilityResult<Boolean> pathOutsideProject(Path path) {
        return CapabilityResult.failed(new CapabilityFailure(PATH_OUTSIDE_PROJECT,
                path == null ? "A file path is required" : path.toString()));
    }

    /**
     * Status walks the whole repository before the {@link #MAX_STATUS_ENTRIES}
     * bound is applied: the bound limits the reported entries, not the work
     * performed, so a very large worktree is still traversed in full.
     */
    private CapabilityResult<VersionControlCapability.Status> readStatus(Repository repository, String projectPrefix)
            throws IOException, GitAPIException {
        var jgitStatus = Git.wrap(repository).status().call();
        Map<String, VersionControlCapability.State> states = new TreeMap<>();
        jgitStatus.getConflicting().forEach(path -> record(states, path, VersionControlCapability.State.UNMERGED));
        jgitStatus.getAdded().forEach(path -> record(states, path, VersionControlCapability.State.ADDED));
        jgitStatus.getRemoved().forEach(path -> record(states, path, VersionControlCapability.State.DELETED));
        jgitStatus.getMissing().forEach(path -> record(states, path, VersionControlCapability.State.DELETED));
        jgitStatus.getChanged().forEach(path -> record(states, path, VersionControlCapability.State.MODIFIED));
        jgitStatus.getModified().forEach(path -> record(states, path, VersionControlCapability.State.MODIFIED));
        jgitStatus.getUntracked().forEach(path -> record(states, path, VersionControlCapability.State.UNTRACKED));

        var entries = new ArrayList<VersionControlCapability.Entry>();
        boolean truncated = false;
        for (Map.Entry<String, VersionControlCapability.State> change : states.entrySet()) {
            Optional<String> relative = relativeToProject(change.getKey(), projectPrefix);
            if (relative.isEmpty()) {
                continue;
            }
            if (entries.size() == MAX_STATUS_ENTRIES) {
                truncated = true;
                break;
            }
            entries.add(new VersionControlCapability.Entry(change.getValue(), relative.get()));
        }

        String branch = currentBranch(repository);
        cacheBranch(branch);
        Integer ahead = null;
        Integer behind = null;
        if (branch != null) {
            BranchTrackingStatus tracking = BranchTrackingStatus.of(repository, branch);
            if (tracking != null) {
                ahead = tracking.getAheadCount();
                behind = tracking.getBehindCount();
            }
        }
        return CapabilityResult.ok(new VersionControlCapability.Status(branch, ahead, behind, entries, truncated));
    }

    /**
     * @return the path relative to the project root, or empty when the
     *         repository path lies outside the project root
     */
    private static Optional<String> relativeToProject(String repositoryPath, String projectPrefix) {
        if (projectPrefix.isEmpty()) {
            return Optional.of(repositoryPath);
        }
        String prefix = projectPrefix + "/";
        return repositoryPath.startsWith(prefix)
                ? Optional.of(repositoryPath.substring(prefix.length()))
                : Optional.empty();
    }

    /**
     * Keeps the most urgent state of a path: the enum declaration order is the
     * priority order, so the lower ordinal wins when index and worktree disagree.
     */
    private static void record(Map<String, VersionControlCapability.State> states, String path,
            VersionControlCapability.State state) {
        states.merge(path, state, (current, incoming) -> current.compareTo(incoming) <= 0 ? current : incoming);
    }

    private static String currentBranch(Repository repository) throws IOException {
        String fullName = repository.getFullBranch();
        return fullName != null && fullName.startsWith(Constants.R_HEADS) ? repository.getBranch() : null;
    }

    /**
     * Diffs the tracked paths of the repository against {@code HEAD}, limited to
     * the given repository relative prefix, where the empty prefix means the
     * whole worktree. Paths that Git does not track never reach the formatter.
     */
    private static CapabilityResult<String> diffAgainstHead(Repository repository, String prefix)
            throws IOException {
        Set<String> tracked = trackedPaths(repository);
        if (tracked.isEmpty()) {
            return CapabilityResult.ok("");
        }
        TreeFilter trackedOnly = PathFilterGroup.createFromStrings(tracked);
        TreeFilter filter = prefix.isEmpty()
                ? trackedOnly
                : AndTreeFilter.create(PathFilter.create(prefix), trackedOnly);
        var output = new CappedOutputStream(MAX_DIFF_BYTES);
        try (ObjectReader reader = repository.newObjectReader();
                DiffFormatter formatter = new DiffFormatter(output)) {
            formatter.setRepository(repository);
            formatter.setOldPrefix(OLD_PREFIX);
            formatter.setNewPrefix(NEW_PREFIX);
            formatter.setPathFilter(filter);
            formatter.format(headTree(repository, reader), new FileTreeIterator(repository));
        }
        String diff = output.asString(StandardCharsets.UTF_8);
        return CapabilityResult.ok(output.isTruncated() ? diff + "\n" + TRUNCATION_MARKER : diff);
    }

    private static AbstractTreeIterator headTree(Repository repository, ObjectReader reader) throws IOException {
        ObjectId head = repository.resolve(Constants.HEAD + "^{tree}");
        if (head == null) {
            return new EmptyTreeIterator();
        }
        var parser = new CanonicalTreeParser();
        parser.reset(reader, head);
        return parser;
    }

    /**
     * Union of the index paths and the {@code HEAD} paths, so a staged removal
     * still shows as a deletion while an untracked file never does.
     */
    private static Set<String> trackedPaths(Repository repository) throws IOException {
        Set<String> paths = new TreeSet<>();
        DirCache index = repository.readDirCache();
        for (int position = 0; position < index.getEntryCount(); position++) {
            paths.add(index.getEntry(position).getPathString());
        }
        ObjectId head = repository.resolve(Constants.HEAD + "^{tree}");
        if (head != null) {
            try (TreeWalk walk = new TreeWalk(repository)) {
                walk.addTree(head);
                walk.setRecursive(true);
                while (walk.next()) {
                    paths.add(walk.getPathString());
                }
            }
        }
        return paths;
    }

    /**
     * Turns a requested path into a repository relative one. Relative paths
     * resolve against the project root, every symlink is followed through its
     * real location, and anything that does not land inside the project root
     * answers empty.
     */
    private Optional<String> repositoryRelative(Repository repository, Path requested) throws IOException {
        Path project = projectRoot.canonical();
        Path absolute = requested.isAbsolute() ? requested : project.resolve(requested);
        Path real = realPath(absolute.normalize());
        if (!real.startsWith(project)) {
            return Optional.empty();
        }
        Path worktree = repository.getWorkTree().toPath().toRealPath();
        return Optional.of(worktree.relativize(real).toString().replace(File.separatorChar, '/'));
    }

    /**
     * @return the project root relative to the worktree, where the empty string
     *         means the project is the repository root, or empty when the
     *         project root resolves outside the worktree
     */
    private Optional<String> projectPrefix(Repository repository) throws IOException {
        Path worktree = repository.getWorkTree().toPath().toRealPath();
        Path project = projectRoot.canonical();
        if (!project.startsWith(worktree)) {
            return Optional.empty();
        }
        return Optional.of(worktree.relativize(project).toString().replace(File.separatorChar, '/'));
    }

    /**
     * Resolves the real location of a path that may not exist anymore, such as a
     * deleted file that still has a diff: the nearest existing ancestor is
     * resolved and the missing names are appended to it.
     */
    private static Path realPath(Path path) throws IOException {
        if (Files.exists(path)) {
            return path.toRealPath();
        }
        Path parent = path.getParent();
        return parent == null ? path : realPath(parent).resolve(path.getFileName());
    }

    /**
     * Opens the worktree repository that contains the project root, or empty
     * when the directory is not inside one or the repository has no worktree.
     */
    private Optional<Repository> openWorktree() throws IOException {
        try {
            var builder = new FileRepositoryBuilder()
                    .findGitDir(projectRoot.raw().toFile())
                    .setMustExist(true);
            if (builder.getGitDir() == null) {
                return Optional.empty();
            }
            Repository repository = builder.build();
            if (repository.isBare()) {
                repository.close();
                return Optional.empty();
            }
            return Optional.of(repository);
        } catch (RepositoryNotFoundException absent) {
            return Optional.empty();
        }
    }

    private <T> CapabilityResult<T> withRepository(RepositoryAction<T> action) {
        try {
            Optional<Repository> opened = openWorktree();
            if (opened.isEmpty()) {
                return CapabilityResult.failed(new CapabilityFailure(NOT_A_REPOSITORY,
                        projectRoot.raw().toString()));
            }
            try (Repository repository = opened.get()) {
                Optional<String> projectPrefix = projectPrefix(repository);
                if (projectPrefix.isEmpty()) {
                    return CapabilityResult.failed(new CapabilityFailure(PROJECT_OUTSIDE_REPOSITORY,
                            projectRoot.raw().toString()));
                }
                return action.apply(repository, projectPrefix.get());
            }
        } catch (IOException | GitAPIException | UncheckedIOException failure) {
            return CapabilityResult.failed(new CapabilityFailure(REPOSITORY_NOT_READABLE,
                    messageOf(failure)));
        }
    }

    private ProviderHealth discoverHealth() {
        try {
            Optional<Repository> opened = openWorktree();
            if (opened.isEmpty()) {
                return ProviderHealth.unavailable(NOT_A_REPOSITORY + ": " + projectRoot.raw());
            }
            opened.get().close();
            return ProviderHealth.available();
        } catch (IOException | UncheckedIOException failure) {
            return ProviderHealth.unavailable(REPOSITORY_NOT_READABLE + ": " + messageOf(failure));
        }
    }

    private void refreshBranchAsyncIfStale() {
        if (!branchRefreshInFlight.compareAndSet(false, true)) {
            return;
        }
        long now = System.nanoTime();
        long lastUpdated = branchCacheUpdatedAt;
        if (lastUpdated != 0 && now - lastUpdated < BRANCH_REFRESH_NANOS) {
            branchRefreshInFlight.set(false);
            return;
        }
        try {
            branchRefreshExecutor.execute(() -> {
                try {
                    Optional<Repository> opened = openWorktree();
                    if (opened.isEmpty()) {
                        cacheBranch("unavailable");
                        return;
                    }
                    try (Repository repository = opened.get()) {
                        String branch = currentBranch(repository);
                        cacheBranch(branch);
                    }
                } catch (IOException | UncheckedIOException failure) {
                    cacheBranch("unavailable");
                } finally {
                    branchRefreshInFlight.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            branchRefreshInFlight.set(false);
            cacheBranch("unavailable");
        }
    }

    private void cacheBranch(String branch) {
        cachedBranch = branch == null ? "detached" : branch;
        branchCacheUpdatedAt = System.nanoTime();
    }

    private static String messageOf(Exception failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    @FunctionalInterface
    private interface RepositoryAction<T> {

        CapabilityResult<T> apply(Repository repository, String projectPrefix) throws IOException, GitAPIException;
    }
}
