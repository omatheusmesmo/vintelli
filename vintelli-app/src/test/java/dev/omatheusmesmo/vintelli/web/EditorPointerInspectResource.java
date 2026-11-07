package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

/** Enables the gated editor inspection surface over a temporary project with a 40-line numbered file. */
public final class EditorPointerInspectResource implements QuarkusTestResourceLifecycleManager {

    private Path projectRoot;

    @Override
    public Map<String, String> start() {
        try {
            projectRoot = Files.createTempDirectory("vintelli-editor-pointer-");
            StringBuilder text = new StringBuilder();
            for (int line = 1; line <= 40; line++) {
                text.append("line ").append(line).append('\n');
            }
            Files.writeString(projectRoot.resolve("Pointer.java"), text.toString());
            return Map.of(
                    "vintelli.editor.inspect.enabled", "true",
                    "vintelli.local.project-root", projectRoot.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the pointer inspection project", e);
        }
    }

    @Override
    public void stop() {
        if (projectRoot == null) {
            return;
        }
        try (var paths = Files.walk(projectRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete the pointer inspection project", e);
        }
    }
}
