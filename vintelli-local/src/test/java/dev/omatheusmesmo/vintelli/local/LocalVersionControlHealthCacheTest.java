package dev.omatheusmesmo.vintelli.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;

/**
 * The registry calls {@code health()} on every selection: a repository that was
 * found is remembered for the life of the bean, so the discovery does not touch
 * the disk again, while a project outside any repository is probed again so a
 * repository created later is noticed.
 */
@ExtendWith(IsolatedGitConfiguration.class)
class LocalVersionControlHealthCacheTest {

    @TempDir
    Path temp;

    @Test
    void aFoundRepositoryIsRememberedWithoutTouchingTheDiskAgain() throws IOException, GitAPIException {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            var capability = new LocalVersionControlCapability(new LocalProjectRoot(repo));
            assertTrue(capability.health().isAvailable());

            try (var walk = Files.walk(repo.resolve(".git"))) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }

            for (int call = 0; call < 50; call++) {
                assertTrue(capability.health().isAvailable(), "health must come from the remembered repository");
            }
        }
    }

    @Test
    void aRepositoryCreatedAfterTheFirstHealthReadIsNoticed() throws IOException, GitAPIException {
        Path plain = temp.resolve("plain");
        Files.createDirectories(plain);
        var capability = new LocalVersionControlCapability(new LocalProjectRoot(plain));
        ProviderHealth first = capability.health();
        assertEquals(ProviderHealth.State.UNAVAILABLE, first.state());

        Git.init().setDirectory(plain.toFile()).call().close();

        assertTrue(capability.health().isAvailable(), "a git init after the first read must be noticed");
    }
}
