package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/** Creates a small Maven project with one dependency intentionally absent from the offline cache. */
public final class ExternalLibrariesResource implements QuarkusTestResourceLifecycleManager {

    static volatile Path project;

    @Override
    public Map<String, String> start() {
        try {
            project = Files.createTempDirectory("vintelli-external-libraries-");
            Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                    + "<groupId>test</groupId><artifactId>offline-project</artifactId><version>1.0</version>"
                    + "<dependencies><dependency><groupId>example</groupId><artifactId>offline-fixture</artifactId>"
                    + "<version>1.0</version><scope>test</scope></dependency></dependencies></project>");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        return Map.of("vintelli.inspect.enabled", "true", "vintelli.local.project-root", project.toString());
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
