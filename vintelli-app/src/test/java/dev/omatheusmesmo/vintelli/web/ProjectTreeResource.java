package dev.omatheusmesmo.vintelli.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/** Isolated filesystem and authenticated browser terminal for project-tree tests. */
public final class ProjectTreeResource implements QuarkusTestResourceLifecycleManager {
    static volatile Path project;

    @Override
    public Map<String, String> start() {
        try {
            project = Files.createTempDirectory("vintelli-project-tree-");
            Files.writeString(project.resolve("seed.txt"), "seed");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        return Map.ofEntries(
                Map.entry("vintelli.local.project-root", project.toString()),
                Map.entry("vintelli.web.enabled", "true"),
                Map.entry("vintelli.mcp.allowed-origins", WebTerminalResource.TRUSTED_ORIGIN),
                Map.entry("quarkus.http.cors.origins", WebTerminalResource.TRUSTED_ORIGIN),
                Map.entry("quarkus.security.users.embedded.plain-text", "true"),
                Map.entry("quarkus.security.users.embedded.users." + WebTerminalResource.USER, WebTerminalResource.PASSWORD),
                Map.entry("quarkus.security.users.embedded.roles." + WebTerminalResource.USER, "user"));
    }

    @Override
    public void stop() {
        if (project == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(project)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
