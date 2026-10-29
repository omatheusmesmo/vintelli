package dev.omatheusmesmo.vintelli.local;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability.DirEntry;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Local filesystem of the read-file capability, confined to the
 * {@link LocalProjectRoot}: absolute paths, relative paths and even symlink
 * chains must land inside the root. Traversal with {@code ..} segments or a
 * symlink that resolves outside the root fails with an explicit reason; the
 * canonical root is compared against the real location of the target, so no
 * accepted path can ever touch files outside the project.
 */
@Unremovable
@Singleton
public final class LocalReadFileCapability implements ReadFileCapability {

    private final LocalProjectRoot projectRoot;

    @Inject
    LocalReadFileCapability(LocalProjectRoot projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public String identifier() {
        return "local";
    }

    @Override
    public ProviderHealth health() {
        return ProviderHealth.available();
    }

    @Override
    public String provenance() {
        return "local filesystem, confined to the project root";
    }

    @Override
    public CapabilityResult<String> readText(Path file) {
        Path inside = confine(file);
        if (inside == null) {
            return CapabilityResult.failed(new CapabilityFailure(
                    "The path escapes the project root", file.toString()));
        }
        try {
            return CapabilityResult.ok(Files.readString(inside, StandardCharsets.UTF_8));
        } catch (NoSuchFileException absent) {
            return CapabilityResult.failed(new CapabilityFailure("The file does not exist", inside.toString()));
        } catch (CharacterCodingException unreadable) {
            return CapabilityResult.failed(new CapabilityFailure(
                    "The file is not readable as UTF 8 text", inside.toString()));
        } catch (IOException failure) {
            return CapabilityResult.failed(new CapabilityFailure("The file cannot be read",
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
        }
    }

    @Override
    public CapabilityResult<List<DirEntry>> list(Path directory) {
        Path inside = confine(directory);
        if (inside == null) {
            return CapabilityResult.failed(new CapabilityFailure(
                    "The path escapes the project root", directory.toString()));
        }
        if (!Files.isDirectory(inside)) {
            return CapabilityResult.failed(new CapabilityFailure("The path is not a directory",
                    inside.toString()));
        }
        List<DirEntry> entries = new ArrayList<>();
        try (Stream<Path> children = Files.list(inside)) {
            children.forEach(child -> entries.add(new DirEntry(child.getFileName().toString(),
                    Files.isDirectory(child))));
        } catch (IOException failure) {
            return CapabilityResult.failed(new CapabilityFailure("The directory cannot be listed",
                    failureMessage(failure)));
        }
        entries.sort(Comparator.comparing((DirEntry entry) -> !entry.directory()).thenComparing(DirEntry::name));
        return CapabilityResult.ok(List.copyOf(entries));
    }

    /**
     * Resolves the requested path against the root when relative, follows
     * every symlink layer through the real location and answers null when the
     * result is not the root itself or a descendant of it.
     */
    private Path confine(Path requested) {
        return projectRoot.confine(requested);
    }

    private static String failureMessage(IOException failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
