package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

/** Enables a private temporary project for the native editor inspection test. */
public final class EditorInspectEnabledResource implements QuarkusTestResourceLifecycleManager {
    private static Path projectRoot;

    @Override
    public Map<String, String> start() {
        try {
            projectRoot = Files.createTempDirectory("vintelli-editor-inspect-");
            Files.writeString(projectRoot.resolve("Editor.java"), "class Editor {}\n");
            return Map.of(
                    "vintelli.editor.inspect.enabled", "true",
                    "vintelli.local.project-root", projectRoot.toString());
        } catch (IOException e) {
            throw new IllegalStateException("Could not create editor inspection test project", e);
        }
    }

    static Path projectRoot() {
        if (projectRoot == null) {
            throw new IllegalStateException("Editor inspection test resource has not started");
        }
        return projectRoot;
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
        } catch (IOException ignored) {
            // The test fixture is temporary; cleanup failure does not hide test results.
        } finally {
            projectRoot = null;
        }
    }
}
