package dev.omatheusmesmo.vintelli.tui.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreakpointStoreTest {

    @TempDir
    Path temporary;

    @Test
    void roundTripsSortedProjectRelativeLocationsAndDoesNotLeakIntoAnotherProject() throws Exception {
        Path firstProject = temporary.resolve("first");
        Path secondProject = temporary.resolve("second");
        Files.createDirectories(firstProject);
        Files.createDirectories(secondProject);
        Path storeFile = temporary.resolve("settings/breakpoints.properties");
        BreakpointStore store = new BreakpointStore(storeFile);
        Path canonicalProject = firstProject.toRealPath();
        List<BreakpointStore.Source> sources = List.of(
                new BreakpointStore.Source(firstProject.resolve("src/Z.java"), List.of(9)),
                new BreakpointStore.Source(firstProject.resolve("src/A.java"), List.of(2)),
                new BreakpointStore.Source(firstProject.resolve("src/Removed.java"), List.of()));

        store.save(firstProject, sources);

        assertEquals(List.of(new BreakpointStore.Source(canonicalProject.resolve("src/A.java"), List.of(2)),
                new BreakpointStore.Source(canonicalProject.resolve("src/Removed.java"), List.of()),
                new BreakpointStore.Source(canonicalProject.resolve("src/Z.java"), List.of(9))), store.load(firstProject));
        assertTrue(store.load(secondProject).isEmpty());
    }

    @Test
    void rejectsStoredLocationsThatEscapeThroughProjectSymlinks() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Path link = project.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable");
        }
        Path storeFile = temporary.resolve("settings/breakpoints.properties");
        Files.createDirectories(storeFile.getParent());
        Properties properties = new Properties();
        properties.setProperty("project", project.toRealPath().toString());
        properties.setProperty("count", "1");
        properties.setProperty("breakpoint.0.file", "linked/Secret.java");
        properties.setProperty("breakpoint.0.line", "4");
        try (Writer writer = Files.newBufferedWriter(storeFile, StandardCharsets.UTF_8)) {
            properties.store(writer, "test");
        }

        assertTrue(new BreakpointStore(storeFile).load(project).isEmpty());
    }
}
