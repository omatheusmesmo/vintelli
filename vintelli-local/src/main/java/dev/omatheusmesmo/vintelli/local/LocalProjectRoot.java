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
 * detected, where relative file reads and terminal working directories
 * resolve and where the project tree lives. Set with
 * {@code vintelli.local.project-root}; when unset, the working directory of
 * the process at startup is used, which matches launching Vintelli inside a
 * project checkout or inside the headless starter output.
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
    public Path raw() {
        return root;
    }

    /**
     * @return the real root used for symlink safe confinement of absolute
     *         paths and as the parent all {@code ..} segments must land inside
     * @throws UncheckedIOException when the configured root cannot be resolved
     */
    public Path canonical() {
        try {
            if (Files.exists(root)) {
                return root.toRealPath();
            }
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot resolve the project root " + root, e);
        }
    }

    /**
     * Resolves the requested path against the root when relative, follows
     * every symlink layer through the real location and answers null when the
     * result is neither the root itself nor a descendant of it, or when the
     * location cannot be resolved at all.
     */
    public Path confine(Path requested) {
        Path canonicalRoot = canonical();
        boolean absoluteOrRooted = requested.isAbsolute() || requested.getRoot() != null;
        Path resolved = absoluteOrRooted ? requested : canonicalRoot.resolve(requested);
        Path real;
        try {
            real = Files.exists(resolved) ? resolved.toRealPath() : absentChildOf(resolved);
        } catch (IOException failure) {
            return null;
        }
        return real.startsWith(canonicalRoot) ? real : null;
    }

    /**
     * A missing child is checked through its real parent, so a traversal like
     * {@code ../secret} over a parent outside the root still fails while a
     * fresh name inside the root is permitted.
     */
    private static Path absentChildOf(Path resolved) throws IOException {
        Path parent = resolved.getParent();
        if (parent == null) {
            return resolved.normalize();
        }
        return parent.toRealPath().resolve(resolved.getFileName());
    }
}
