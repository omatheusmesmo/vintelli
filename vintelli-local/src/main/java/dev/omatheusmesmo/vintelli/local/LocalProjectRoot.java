package dev.omatheusmesmo.vintelli.local;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Absolute directory the standalone provider works in: where build tools are
 * detected, where relative file reads resolve and where the project tree
 * lives. Set with {@code vintelli.local.project-root}; when unset, the working
 * directory of the process at startup is used, which matches launching
 * Vintelli inside a project checkout or inside the headless starter output.
 *
 * <p>The stored path is resolved to its real location when it exists, so
 * filesystem confinement compares against the canonical root rather than a
 * possibly aliased one.
 */
@Singleton
public final class LocalProjectRoot {

    private static final Predicate<String> NOT_BLANK = value -> value != null && !value.isBlank();

    private final Path root;

    @Inject
    LocalProjectRoot(@ConfigProperty(name = "vintelli.local.project-root") Optional<String> configuredRoot) {
        root = configuredRoot.filter(NOT_BLANK).map(Path::of)
                .orElseGet(() -> Path.of("").toAbsolutePath())
                .normalize().toAbsolutePath();
    }

    LocalProjectRoot(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * @return the configured or process working directory, absolute and
     *         normalized, without following symlinks
     */
    Path raw() {
        return root;
    }

    /**
     * @return the real root used for symlink safe confinement of absolute
     *         paths and as the parent all {@code ..} segments must land inside
     * @throws UncheckedIOException when the configured root cannot be resolved
     */
    Path canonical() {
        try {
            if (Files.exists(root)) {
                return root.toRealPath();
            }
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot resolve the project root " + root, e);
        }
    }
}
