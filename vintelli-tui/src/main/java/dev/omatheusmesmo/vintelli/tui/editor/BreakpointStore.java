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
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;

/**
 * Project scoped, zero based line-number anchors, separate from source files and editor recovery text.
 * Edits move anchors with the text they mark; an anchor on a removed line is dropped, and undo restores
 * the anchors of the revision it returns to.
 */
final class BreakpointStore {
    record Source(Path file, List<Integer> lines, Map<Integer, String> conditions, Map<Integer, String> logMessages,
            Map<Integer, String> hitConditions) {
        Source {
            lines = lines == null ? List.of() : lines.stream().filter(line -> line >= 0).distinct().sorted().toList();
            conditions = retained(conditions, lines);
            logMessages = retained(logMessages, lines);
            hitConditions = retained(hitConditions, lines);
        }

        private static Map<Integer, String> retained(Map<Integer, String> values, List<Integer> lines) {
            return values == null ? Map.of() : Map.copyOf(values.entrySet().stream()
                    .filter(entry -> lines.contains(entry.getKey()) && entry.getValue() != null
                            && !entry.getValue().isBlank())
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().strip())));
        }

        Source(Path file, List<Integer> lines, Map<Integer, String> conditions) {
            this(file, lines, conditions, Map.of(), Map.of());
        }

        Source(Path file, List<Integer> lines) {
            this(file, lines, Map.of(), Map.of(), Map.of());
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
                    sources.add(new Source(candidate, lines,
                            restored(properties, prefix + ".condition.", lines, BreakpointStore::isStorableCondition),
                            restored(properties, prefix + ".logMessage.", lines, BreakpointStore::isStorableLogMessage),
                            restored(properties, prefix + ".hitCondition.", lines,
                                    DebugCapability.SourceBreakpoint::isValidHitCondition)));
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
                return resolved == null ? null : new Source(resolved, source.lines(), source.conditions(), source.logMessages(),
                        source.hitConditions());
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
            source.conditions().forEach((line, value) -> properties.setProperty(prefix + ".condition." + line, value));
            source.logMessages().forEach((line, value) -> properties.setProperty(prefix + ".logMessage." + line, value));
            source.hitConditions().forEach((line, value) -> properties.setProperty(prefix + ".hitCondition." + line, value));
        }
        writeAtomically(file, properties, "Vintelli source breakpoints");
    }

    /** Replaces the file in one move, so a crash never leaves a partly written store. */
    static void writeAtomically(Path file, Properties properties, String comment) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "editor-breakpoints-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, comment);
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

    private static Map<Integer, String> restored(Properties properties, String keyPrefix, List<Integer> lines,
            Predicate<String> storable) {
        Map<Integer, String> values = new TreeMap<>();
        for (int line : lines) {
            String value = properties.getProperty(keyPrefix + line);
            if (value != null && storable.test(value.strip())) {
                values.put(line, value.strip());
            }
        }
        return values;
    }

    private static boolean isStorableCondition(String condition) {
        return !condition.isEmpty() && condition.length() <= DebugCapability.SourceBreakpoint.MAX_CONDITION_LENGTH
                && condition.indexOf('\n') < 0 && condition.indexOf('\r') < 0;
    }

    private static boolean isStorableLogMessage(String message) {
        return !message.isEmpty() && message.length() <= DebugCapability.SourceBreakpoint.MAX_LOG_MESSAGE_LENGTH
                && message.indexOf('\n') < 0 && message.indexOf('\r') < 0;
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
