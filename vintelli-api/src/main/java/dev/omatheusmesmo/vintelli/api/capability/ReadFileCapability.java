package dev.omatheusmesmo.vintelli.api.capability;

import java.nio.file.Path;
import java.util.List;

/**
 * Reads the unicode text of a project file and lists directory content for the
 * project tree, through the local filesystem or through the optional IDEA
 * {@code read_file} tool.
 */
public interface ReadFileCapability extends ProviderDescriptor {

    /**
     * @param file file to read, absolute or relative to the project root
     * @return the full text of the file, decoded as UTF-8, or a failed result
     *         when the file does not exist, is unreadable or is not text
     */
    CapabilityResult<String> readText(Path file);

    /**
     * @param directory directory to list, absolute or relative to the project root
     * @return the direct children of the directory, or a failed result when the
     *         directory does not exist or is unreadable
     */
    CapabilityResult<List<DirEntry>> list(Path directory);

    record DirEntry(String name, boolean directory) {

        public DirEntry {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("A directory entry always has a name");
            }
        }
    }
}
