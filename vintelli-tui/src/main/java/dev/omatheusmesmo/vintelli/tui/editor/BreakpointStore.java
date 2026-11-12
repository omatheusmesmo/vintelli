package dev.omatheusmesmo.vintelli.tui.editor;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * Project scoped, zero based line-number anchors, separate from source files and editor recovery text.
 * Edits keep anchors on their current line; a breakpoint past the new end of file is removed, and
 * undo does not resurrect a removed anchor.
 */
final class BreakpointStore {
    record Source(Path file, List<Integer> lines) {
        Source {
            lines = lines == null ? List.of() : lines.stream().filter(line -> line >= 0).distinct().sorted().toList();
        }
    }

    private final Path file;

    BreakpointStore(Path file) {
        this.file = file;
    }

    List<Source> load(Path project) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        Path root = project.toRealPath();
        if (!root.toString().equals(properties.getProperty("project"))) {
            return List.of();
        }
        List<Source> sources = new ArrayList<>();
        int count = Math.clamp(number(properties, "source.count", "count"), 0, 100_000);
        for (int index = 0; index < count; index++) {
            String prefix = "breakpoint." + index;
            String source = properties.getProperty(prefix + ".file");
            if (source == null) {
                continue;
            }
            try {
                Path candidate = safePath(root, source);
                if (candidate != null) {
                    String lineCountValue = properties.getProperty(prefix + ".lineCount");
                    List<Integer> lines;
                    if (lineCountValue == null) {
                        int legacyLine = number(properties, prefix + ".line");
                        lines = legacyLine < 0 ? List.of() : List.of(legacyLine);
                    } else {
                        int lineCount = Math.clamp(parse(lineCountValue), 0, 100_000);
                        List<Integer> restored = new ArrayList<>();
                        for (int line = 0; line < lineCount; line++) {
                            int value = number(properties, prefix + ".line." + line);
                            if (value >= 0) {
                                restored.add(value);
                            }
                        }
                        lines = restored;
                    }
                    sources.add(new Source(candidate, lines));
                }
            } catch (IOException | IllegalArgumentException invalid) {
                // A missing target behind a broken link is not a restorable in-project location.
            }
        }
        return List.copyOf(sources);
    }

    void save(Path project, List<Source> sources) throws IOException {
        Path root = project.toRealPath();
        Properties properties = new Properties();
        properties.setProperty("project", root.toString());
        List<Source> ordered = sources.stream().map(source -> {
            try {
                Path resolved = safeAbsolutePath(root, source.file());
                return resolved == null ? null : new Source(resolved, source.lines());
            } catch (IOException | IllegalArgumentException invalid) {
                return null;
            }
        }).filter(Objects::nonNull)
                .sorted(Comparator.comparing(source -> root.relativize(source.file()).toString())).toList();
        properties.setProperty("source.count", Integer.toString(ordered.size()));
        for (int index = 0; index < ordered.size(); index++) {
            Source source = ordered.get(index);
            String prefix = "breakpoint." + index;
            properties.setProperty(prefix + ".file", root.relativize(source.file()).toString());
            properties.setProperty(prefix + ".lineCount", Integer.toString(source.lines().size()));
            for (int line = 0; line < source.lines().size(); line++) {
                properties.setProperty(prefix + ".line." + line, Integer.toString(source.lines().get(line)));
            }
        }
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "editor-breakpoints-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "Vintelli source breakpoints");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Canonicalizes an absolute source before checking project containment, including missing files. */
    private static Path safeAbsolutePath(Path project, Path source) throws IOException {
        Path root = project.toRealPath();
        Path candidate = source.toAbsolutePath().normalize();
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            Path realCandidate = candidate.toRealPath();
            return realCandidate.startsWith(root) ? realCandidate : null;
        }
        Path existing = candidate;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return null;
        }
        Path realExisting = existing.toRealPath();
        if (!realExisting.startsWith(root)) {
            return null;
        }
        Path missing = realExisting.resolve(existing.relativize(candidate)).normalize();
        return missing.startsWith(root) ? missing : null;
    }

    /** Resolves an existing source canonically; missing sources are allowed only through an in-project real parent. */
    private static Path safePath(Path project, String source) throws IOException {
        Path root = project.toRealPath();
        Path relative = Path.of(source);
        if (relative.isAbsolute() || relative.toString().isBlank() || relative.toString().equals(".")) {
            return null;
        }
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            return null;
        }
        Path existing = candidate;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return null;
        }
        Path realExisting = existing.toRealPath();
        if (!realExisting.startsWith(root)) {
            return null;
        }
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            Path realCandidate = candidate.toRealPath();
            return realCandidate.startsWith(root) ? realCandidate : null;
        }
        Path missing = realExisting.resolve(existing.relativize(candidate)).normalize();
        return missing.startsWith(root) ? missing : null;
    }

    private static int number(Properties properties, String key) {
        return parse(properties.getProperty(key, "0"));
    }

    private static int number(Properties properties, String primaryKey, String fallbackKey) {
        return parse(properties.getProperty(primaryKey, properties.getProperty(fallbackKey, "0")));
    }

    private static int parse(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
