package dev.omatheusmesmo.vintelli.vcs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.stream.Collectors.joining;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;

/**
 * Creates a real temporary Git repository before the application under test
 * boots (the packaged native runner included), commits one file into it, then
 * edits the file and drops an untracked file, so the worktree carries a
 * modified and an untracked entry. The returned config properties point
 * {@code vintelli.local.project-root} at the repository and switch the VCS
 * debug endpoint on, which reach the native runner too.
 *
 * <p>The machine Git configuration stays out of the repository: the JGit
 * {@link SystemReader} reads no user or system configuration while the resource
 * is active, and the repository pins its own {@code core.autocrlf} and an empty
 * {@code core.excludesFile}, which the packaged application reads too because it
 * runs in its own process.
 */
public final class TemporaryGitRepositoryResource implements QuarkusTestResourceLifecycleManager {

    private static final String AUTHOR = "Vintelli Test";
    private static final String EMAIL = "test@vintelli.invalid";
    private static final Logger LOG = System.getLogger(TemporaryGitRepositoryResource.class.getName());
    private static final Duration DELETE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration FIRST_RETRY_PAUSE = Duration.ofMillis(10);
    private static final Duration MAX_RETRY_PAUSE = Duration.ofMillis(250);
    private static final int MAX_LISTED_LEFTOVERS = 50;

    private static volatile Path repositoryRoot;
    private static volatile SystemReader previousReader;

    /**
     * @return the absolute worktree root prepared by {@link #start()}, for the
     *         tests to build paths against
     */
    public static Path repositoryRoot() {
        return repositoryRoot;
    }

    @Override
    public Map<String, String> start() {
        isolateGitConfiguration();
        try {
            Path root = Files.createTempDirectory("vintelli-vcs-it").toRealPath();
            Path emptyExcludes = root.resolve(".git").resolve("vintelli-empty-excludes");
            Files.writeString(root.resolve("tracked.txt"), "first line\n");
            try (Git git = Git.init().setDirectory(root.toFile()).setInitialBranch("main").call()) {
                pinRepositoryConfiguration(git, emptyExcludes);
                git.add().addFilepattern("tracked.txt").call();
                git.commit().setMessage("initial")
                        .setAuthor(AUTHOR, EMAIL).setCommitter(AUTHOR, EMAIL).setSign(false).call();
                git.branchCreate().setName("feature").call();
            }
            Files.writeString(root.resolve("tracked.txt"), "first line\nsecond line\n");
            Files.writeString(root.resolve("untracked.txt"), "not tracked\n");
            repositoryRoot = root;
            return Map.of(
                    "vintelli.local.project-root", root.toString(),
                    "vintelli.vcs.debug-endpoint.enabled", "true");
        } catch (IOException | GitAPIException | UncheckedIOException e) {
            throw new IllegalStateException("Cannot prepare the temporary Git repository for the VCS tests", e);
        }
    }

    /**
     * Never throws: Quarkus closes the test resources before it stops the
     * application under test, so a failure here would leave that process alive
     * and holding its port for every later test. A repository that cannot be
     * emptied in time is logged with its leftovers instead.
     */
    @Override
    public void stop() {
        restoreGitConfiguration();
        Path root = repositoryRoot;
        repositoryRoot = null;
        if (root == null) {
            return;
        }
        deleteWithRetry(root);
    }

    /**
     * Each attempt walks the tree again, so entries that a process still writes
     * while the application stops are removed by a later attempt. The attempts
     * back off between failures and end at the deadline, which bounds the wait
     * without a fixed sleep.
     */
    private static void deleteWithRetry(Path root) {
        DeleteRetry.withRealClock(DELETE_TIMEOUT, FIRST_RETRY_PAUSE, MAX_RETRY_PAUSE)
                .run(() -> deleteTree(root))
                .ifPresent(lastFailure -> LOG.log(Logger.Level.ERROR, leakReport(root, lastFailure)));
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> paths;
        try (var walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        } catch (UncheckedIOException walkFailure) {
            throw walkFailure.getCause();
        }
        for (Path path : paths) {
            clearReadOnly(path);
            Files.deleteIfExists(path);
        }
    }

    /**
     * Git writes loose objects read-only. Windows refuses to delete a read-only file
     * ({@code AccessDeniedException}), so the flag is cleared first where the file system has it.
     */
    private static void clearReadOnly(Path path) throws IOException {
        DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (dos != null && Files.exists(path, LinkOption.NOFOLLOW_LINKS) && dos.readAttributes().isReadOnly()) {
            dos.setReadOnly(false);
        }
    }

    private static String leakReport(Path root, IOException failure) {
        return "LEAKED temporary Git repository " + root + " after " + DELETE_TIMEOUT + ": " + failure
                + System.lineSeparator() + "Leftover entries:" + System.lineSeparator() + describeLeftovers(root)
                + System.lineSeparator() + "Live processes referencing the repository:"
                + System.lineSeparator() + describeProcesses(root);
    }

    private static String describeLeftovers(Path root) {
        try (var walk = Files.walk(root)) {
            return walk.limit(MAX_LISTED_LEFTOVERS).map(path -> describeEntry(root, path))
                    .collect(joining(System.lineSeparator()));
        } catch (IOException | UncheckedIOException listingFailure) {
            return "  (cannot list the leftovers: " + listingFailure + ")";
        }
    }

    private static String describeEntry(Path root, Path path) {
        String relativeName = root.relativize(path).toString();
        String relative = relativeName.isEmpty() ? "." : relativeName;
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return "  " + relative + " size=" + attributes.size() + " modified=" + attributes.lastModifiedTime();
        } catch (IOException vanished) {
            return "  " + relative + " (gone while listing)";
        }
    }

    private static String describeProcesses(Path root) {
        String needle = root.toString();
        String listing = ProcessHandle.allProcesses()
                .filter(process -> process.info().commandLine().orElse("").contains(needle))
                .map(process -> "  pid " + process.pid() + ": " + process.info().commandLine().orElse(""))
                .collect(joining(System.lineSeparator()));
        return listing.isEmpty() ? "  (none)" : listing;
    }

    private static void pinRepositoryConfiguration(Git git, Path emptyExcludes) throws IOException {
        Files.createDirectories(emptyExcludes.getParent());
        Files.createFile(emptyExcludes);
        StoredConfig config = git.getRepository().getConfig();
        config.setBoolean("core", null, "autocrlf", false);
        config.setString("core", null, "excludesFile", emptyExcludes.toAbsolutePath().toString().replace('\\', '/'));
        config.setBoolean("commit", null, "gpgSign", false);
        config.save();
    }

    private static void isolateGitConfiguration() {
        previousReader = SystemReader.getInstance();
        SystemReader.setInstance(new EmptyConfigurationReader(previousReader));
    }

    private static void restoreGitConfiguration() {
        if (previousReader != null) {
            SystemReader.setInstance(previousReader);
            previousReader = null;
        }
    }

    private static final class EmptyConfigurationReader extends SystemReader.Delegate {

        private static final Path ABSENT_CONFIGURATION = Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("vintelli-absent-git-config-" + UUID.randomUUID());

        EmptyConfigurationReader(SystemReader delegate) {
            super(delegate);
        }

        @Override
        public FileBasedConfig openUserConfig(Config parent, FS fs) {
            return absent(parent, fs);
        }

        @Override
        public FileBasedConfig openSystemConfig(Config parent, FS fs) {
            return absent(parent, fs);
        }

        @Override
        public FileBasedConfig openJGitConfig(Config parent, FS fs) {
            return absent(parent, fs);
        }

        @Override
        public Path getXdgConfigDirectory(FS fs) {
            return ABSENT_CONFIGURATION;
        }

        private static FileBasedConfig absent(Config parent, FS fs) {
            return new FileBasedConfig(parent, ABSENT_CONFIGURATION.resolve("config").toFile(), fs);
        }
    }
}
