package dev.omatheusmesmo.vintelli.local;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;

/**
 * Proves that the explicit {@code a/} and {@code b/} prefixes in
 * {@link LocalVersionControlCapability} survive a hostile Git configuration
 * that really reaches JGit. Unlike {@link IsolatedGitConfiguration}, the
 * {@link SystemReader} here delegates to a user configuration carrying
 * {@code diff.noprefix = true}, which JGit reads, so the test fails when the
 * explicit {@code setOldPrefix}/{@code setNewPrefix} calls are removed.
 */
class LocalVersionControlCapabilityPrefixGuardTest {

    private static final String AUTHOR = "Vintelli Test";
    private static final String EMAIL = "test@vintelli.invalid";

    @TempDir
    Path temp;

    @Test
    void explicitPrefixesSurviveHostileDiffConfiguration() throws IOException, GitAPIException {
        Path home = temp.resolve("home");
        Files.createDirectories(home);
        Path gitconfig = home.resolve(".gitconfig");
        Files.writeString(gitconfig, """
                [diff]
                    noprefix = true
                """);

        Path absent = temp.resolve("absent-" + UUID.randomUUID());
        Files.createDirectories(absent);

        SystemReader previous = SystemReader.getInstance();
        try {
            SystemReader.setInstance(new SystemReader.Delegate(previous) {
                @Override
                public FileBasedConfig openUserConfig(Config parent, FS fs) {
                    return new FileBasedConfig(parent, gitconfig.toFile(), fs);
                }

                @Override
                public FileBasedConfig openSystemConfig(Config parent, FS fs) {
                    return new FileBasedConfig(parent, absent.resolve("system").toFile(), fs);
                }

                @Override
                public FileBasedConfig openJGitConfig(Config parent, FS fs) {
                    return new FileBasedConfig(parent, absent.resolve("jgit").toFile(), fs);
                }

                @Override
                public Path getXdgConfigDirectory(FS fs) {
                    return absent;
                }
            });

            Path repo = temp.resolve("repo");
            Files.createDirectories(repo);
            try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
                Files.writeString(repo.resolve("a.txt"), "one\n");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial").setAuthor(AUTHOR, EMAIL).setCommitter(AUTHOR, EMAIL).setSign(false).call();
                Files.writeString(repo.resolve("a.txt"), "two\n");

                var capability = new LocalVersionControlCapability(new LocalProjectRoot(repo));
                CapabilityResult<String> result = capability.diff(Path.of("a.txt"));
                assertTrue(result instanceof CapabilityResult.Ok<String>, "diff must succeed");
                String diff = ((CapabilityResult.Ok<String>) result).value();
                assertTrue(diff.contains("diff --git a/a.txt b/a.txt"), diff);
                assertTrue(diff.contains("--- a/a.txt"), diff);
                assertTrue(diff.contains("+++ b/a.txt"), diff);
            }
        } finally {
            SystemReader.setInstance(previous);
        }
    }
}
