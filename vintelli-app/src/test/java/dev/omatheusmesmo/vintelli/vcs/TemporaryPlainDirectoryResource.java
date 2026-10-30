package dev.omatheusmesmo.vintelli.vcs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Creates a temporary plain directory, which no repository root and no
 * worktree, before the application under test boots (the packaged native
 * runner included). The returned config properties point
 * {@code vintelli.local.project-root} at it and switch the VCS debug endpoint
 * on, so the tests can assert the explicit failure of a project root that is
 * not inside a Git repository.
 */
public final class TemporaryPlainDirectoryResource implements QuarkusTestResourceLifecycleManager {

    private static volatile Path projectRoot;

    @Override
    public Map<String, String> start() {
        try {
            Path root = Files.createTempDirectory("vintelli-vcs-plain-it").toRealPath();
            Files.writeString(root.resolve("plain.txt"), "one plain file\n");
            projectRoot = root;
            return Map.of(
                    "vintelli.local.project-root", root.toString(),
                    "vintelli.vcs.debug-endpoint.enabled", "true");
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalStateException("Cannot prepare the temporary plain directory for the VCS tests", e);
        }
    }

    @Override
    public void stop() {
        Path root = projectRoot;
        projectRoot = null;
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
}
