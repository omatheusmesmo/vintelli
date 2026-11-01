package dev.omatheusmesmo.vintelli.vcs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

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

    @Override
    public void stop() {
        restoreGitConfiguration();
        Path root = repositoryRoot;
        repositoryRoot = null;
        if (root == null) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
