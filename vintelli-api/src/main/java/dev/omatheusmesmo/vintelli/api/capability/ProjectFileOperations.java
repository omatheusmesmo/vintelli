package dev.omatheusmesmo.vintelli.api.capability;

import java.nio.file.Path;

/** Mutates project entries through a provider rather than directly in the UI. */
public interface ProjectFileOperations {

    CapabilityResult<Path> createFile(Path directory, String name);

    CapabilityResult<Path> createDirectory(Path directory, String name);

    CapabilityResult<Path> rename(Path entry, String name);

    CapabilityResult<Path> delete(Path entry);
}
