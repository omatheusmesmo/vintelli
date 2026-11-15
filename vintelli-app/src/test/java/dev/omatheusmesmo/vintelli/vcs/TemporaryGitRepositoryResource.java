package dev.omatheusmesmo.vintelli.vcs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private static final Duration PROCESS_TERMINATION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PROCESS_KILL_TIMEOUT = Duration.ofSeconds(5);
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
     * Stops every process started with this repository before it is deleted.
     * Quarkus closes the test resources before it stops the application under
     * test, so that application is still alive, still holds its port and may
     * still write into the repository at this point. A repository that still
     * cannot be deleted fails with its real cause, and only after those processes
     * are gone, so the next test never meets a busy port.
     */
    @Override
    public void stop() {
        restoreGitConfiguration();
        Path root = repositoryRoot;
        repositoryRoot = null;
        if (root == null) {
            return;
        }
        stopProcessesUsing(root);
        deleteWithRetry(root);
    }

    /**
     * Each attempt walks the tree again, so entries that a process still writes
     * while the application stops are removed by a later attempt. The attempts
     * back off between failures and end at the deadline, which bounds the wait
     * without a fixed sleep.
     */
    private static void deleteWithRetry(Path root) {
        Optional<IOException> lastFailure = DeleteRetry
                .withRealClock(DELETE_TIMEOUT, FIRST_RETRY_PAUSE, MAX_RETRY_PAUSE)
                .run(() -> deleteTree(root));
        if (lastFailure.isPresent()) {
            throw new IllegalStateException(repositoryFailure(root, lastFailure.get()), lastFailure.get());
        }
    }

    private static void stopProcessesUsing(Path root) {
        List<ProcessHandle> users = processesUsing(root);
        users.forEach(ProcessHandle::destroy);
        List<ProcessHandle> survivors = awaitExit(users, PROCESS_TERMINATION_TIMEOUT);
        survivors.forEach(ProcessHandle::destroyForcibly);
        List<ProcessHandle> stuck = awaitExit(survivors, PROCESS_KILL_TIMEOUT);
        if (!stuck.isEmpty()) {
            throw new IllegalStateException("Cannot stop the processes using the temporary Git repository " + root
                    + System.lineSeparator() + describeProcesses(stuck));
        }
    }

    private static List<ProcessHandle> processesUsing(Path root) {
        String location = root.toString();
        long self = ProcessHandle.current().pid();
        return ProcessHandle.allProcesses()
                .filter(process -> process.pid() != self)
                .filter(process -> process.info().arguments()
                        .map(arguments -> Arrays.stream(arguments).anyMatch(argument -> names(argument, location)))
                        .orElse(false))
                .toList();
    }

    private static boolean names(String argument, String location) {
        return argument.equals(location) || argument.endsWith("=" + location);
    }

    private static List<ProcessHandle> awaitExit(List<ProcessHandle> processes, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<ProcessHandle> running = new ArrayList<>();
        for (ProcessHandle process : processes) {
            if (!exitedBefore(process, deadline)) {
                running.add(process);
            }
        }
        return running;
    }

    private static boolean exitedBefore(ProcessHandle process, long deadline) {
        try {
            process.onExit().get(Math.max(deadline - System.nanoTime(), 0), TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException stillRunning) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a process to exit", interrupted);
        } catch (ExecutionException neverCompletesExceptionally) {
            throw new IllegalStateException(neverCompletesExceptionally);
        }
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

    private static String repositoryFailure(Path root, IOException failure) {
        return "Cannot delete the temporary Git repository " + root + " after " + DELETE_TIMEOUT + ": " + failure
                + System.lineSeparator() + "Leftover entries:" + System.lineSeparator() + describeLeftovers(root)
                + System.lineSeparator() + "Live processes using the repository:"
                + System.lineSeparator() + describeProcesses(processesUsing(root));
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

    private static String describeProcesses(List<ProcessHandle> processes) {
        if (processes.isEmpty()) {
            return "  (none)";
        }
        return processes.stream()
                .map(process -> "  pid " + process.pid() + ": " + process.info().commandLine().orElse(""))
                .collect(joining(System.lineSeparator()));
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
