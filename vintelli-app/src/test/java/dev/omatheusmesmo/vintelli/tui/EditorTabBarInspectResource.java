package dev.omatheusmesmo.vintelli.tui;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

/**
 * Enables the render and the editor inspection endpoints over a private
 * temporary project with two files, so a test can open them and read the tab
 * bar back through {@code /tui/render}.
 */
public final class EditorTabBarInspectResource implements QuarkusTestResourceLifecycleManager {

    private Path projectRoot;

    @Override
    public Map<String, String> start() {
        try {
            projectRoot = Files.createTempDirectory("vintelli-tab-bar-");
            Files.writeString(projectRoot.resolve("Alpha.java"), "class Alpha {}\n");
            Files.writeString(projectRoot.resolve("Beta.java"), "class Beta {}\n");
            return Map.of(
                    "vintelli.inspect.enabled", "true",
                    "vintelli.editor.inspect.enabled", "true",
                    "vintelli.local.project-root", projectRoot.toString());
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the tab bar test project", e);
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
            throw new UncheckedIOException("Could not delete the tab bar test project", e);
        }
    }
}
