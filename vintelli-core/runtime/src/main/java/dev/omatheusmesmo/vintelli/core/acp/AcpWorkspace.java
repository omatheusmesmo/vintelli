package dev.omatheusmesmo.vintelli.core.acp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * File access the client serves to an agent (fs/read_text_file and
 * fs/write_text_file), confined to the session root: relative paths resolve
 * against it, absolute paths must already lie inside it, and existing files
 * are compared by their real path so a symbolic link cannot leave the root.
 */
public final class AcpWorkspace {

    private final Path root;

    public AcpWorkspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public String read(String requested, Integer line, Integer limit) throws IOException {
        Path file = confine(requested);
        String content = Files.readString(file);
        if (line == null && limit == null) {
            return content;
        }
        List<String> lines = content.lines().toList();
        int from = line == null ? 0 : Math.max(0, Math.min(line - 1, lines.size()));
        int to = limit == null ? lines.size() : Math.min(lines.size(), from + Math.max(0, limit));
        return String.join("\n", lines.subList(from, to));
    }

    public void write(String requested, String content) throws IOException {
        Path file = confine(requested);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content);
    }

    private Path confine(String requested) throws IOException {
        Path candidate = Path.of(requested);
        Path resolved = (candidate.isAbsolute() ? candidate : root.resolve(candidate)).normalize();
        if (!resolved.startsWith(root)) {
            throw new AcpAccessDeniedException("path is outside the session root: " + requested);
        }
        if (Files.exists(resolved) && !resolved.toRealPath().startsWith(root.toRealPath())) {
            throw new AcpAccessDeniedException("path resolves outside the session root: " + requested);
        }
        return resolved;
    }
}
