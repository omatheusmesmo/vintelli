package dev.omatheusmesmo.vintelli.tui.editor;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Private Vintelli session metadata; never reads or writes IntelliJ workspace files. */
final class EditorSessionStore {
    record Tab(Path file, int row, int column, String recovery) {}
    record Session(List<Tab> tabs, int selected) {}
    private final Path file;

    EditorSessionStore(Path file) { this.file = file; }

    Session load(Path project) throws IOException {
        if (!Files.isRegularFile(file)) { return new Session(List.of(), 0); }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        if (!project.toString().equals(properties.getProperty("project"))) { return new Session(List.of(), 0); }
        List<Tab> tabs = new ArrayList<>();
        int count = Math.clamp(number(properties, "count"), 0, 100);
        for (int index = 0; index < count; index++) {
            String prefix = "tab." + index;
            String path = properties.getProperty(prefix + ".file");
            if (path != null) {
                tabs.add(new Tab(Path.of(path), Math.max(0, number(properties, prefix + ".row")),
                        Math.max(0, number(properties, prefix + ".column")), properties.getProperty(prefix + ".recovery")));
            }
        }
        return new Session(List.copyOf(tabs), number(properties, "selected"));
    }

    void save(Path project, List<EditorDocument> documents, int selected) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("project", project.toString());
        properties.setProperty("count", Integer.toString(documents.size()));
        properties.setProperty("selected", Integer.toString(selected));
        for (int index = 0; index < documents.size(); index++) {
            EditorDocument document = documents.get(index);
            String prefix = "tab." + index;
            properties.setProperty(prefix + ".file", project.relativize(document.file()).toString());
            properties.setProperty(prefix + ".row", Integer.toString(document.row()));
            properties.setProperty(prefix + ".column", Integer.toString(document.column()));
            if (document.dirty()) { properties.setProperty(prefix + ".recovery", document.text()); }
        }
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "editor-session-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "Vintelli editor session");
            }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static int number(Properties properties, String key) {
        try { return Integer.parseInt(properties.getProperty(key, "0")); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
