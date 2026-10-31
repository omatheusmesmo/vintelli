package dev.omatheusmesmo.vintelli.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileVisitResult;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProjectFileOperations;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Local project mutations, confined to the configured root. */
@Singleton
@Unremovable
public final class LocalProjectFileOperations implements ProjectFileOperations {
    private final LocalProjectRoot root;

    @Inject
    public LocalProjectFileOperations(LocalProjectRoot root) {
        this.root = root;
    }

    @Override
    public CapabilityResult<Path> createFile(Path directory, String name) {
        return create(directory, name, false);
    }

    @Override
    public CapabilityResult<Path> createDirectory(Path directory, String name) {
        return create(directory, name, true);
    }

    private CapabilityResult<Path> create(Path directory, String name, boolean folder) {
        try {
            Path parent = root.confine(directory);
            if (parent == null || !Files.isDirectory(parent) || !validName(name)) {
                return failure("Invalid project directory or name");
            }
            Path target = parent.resolve(name);
            return CapabilityResult.ok(folder ? Files.createDirectory(target) : Files.createFile(target));
        } catch (IOException | RuntimeException error) {
            return failure(error.getMessage());
        }
    }

    @Override
    public CapabilityResult<Path> rename(Path entry, String name) {
        try {
            Path source = safeEntry(entry);
            if (source == null || !validName(name)) {
                return failure("Invalid project entry or name");
            }
            return CapabilityResult.ok(Files.move(source, source.resolveSibling(name)));
        } catch (IOException | RuntimeException error) {
            return failure(error.getMessage());
        }
    }

    @Override
    public CapabilityResult<Path> delete(Path entry) {
        try {
            Path target = safeEntry(entry);
            if (target == null) {
                return failure("Invalid project entry");
            }
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                    if (error != null) {
                        throw error;
                    }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            return CapabilityResult.ok(target);
        } catch (IOException | RuntimeException error) {
            return failure(error.getMessage());
        }
    }

    private Path safeEntry(Path entry) {
        Path absolute = entry.isAbsolute() ? entry.normalize() : root.canonical().resolve(entry).normalize();
        Path parent = absolute.getParent();
        if (parent == null || absolute.equals(root.canonical()) || !validName(absolute.getFileName().toString())) {
            return null;
        }
        Path safeParent = root.confine(parent);
        if (safeParent == null) {
            return null;
        }
        Path result = safeParent.resolve(absolute.getFileName());
        return Files.exists(result, LinkOption.NOFOLLOW_LINKS) ? result : null;
    }

    private static boolean validName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")) {
            return false;
        }
        Path path = Path.of(name);
        return !path.isAbsolute() && path.getRoot() == null && path.getNameCount() == 1
                && !name.contains("/") && !name.contains("\\");
    }

    private static CapabilityResult<Path> failure(String detail) {
        return CapabilityResult.failed(new CapabilityFailure("Project file operation failed", detail == null ? "" : detail));
    }
}
