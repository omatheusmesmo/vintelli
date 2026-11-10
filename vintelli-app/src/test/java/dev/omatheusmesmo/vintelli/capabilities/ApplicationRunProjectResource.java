package dev.omatheusmesmo.vintelli.capabilities;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Comparator;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public final class ApplicationRunProjectResource implements QuarkusTestResourceLifecycleManager {

    private Path directory;
    private static Path projectRoot;

    public static Path projectRoot() {
        return projectRoot;
    }

    @Override
    public Map<String, String> start() {
        try {
            directory = Files.createTempDirectory("vintelli-application-run-");
            Path project = Files.createDirectory(directory.resolve("project"));
            projectRoot = project;
            Path config = Files.createDirectory(directory.resolve("config"));
            Files.writeString(project.resolve("pom.xml"), """
                    <project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>app</artifactId><version>1</version>
                    <build><plugins>
                    <plugin><artifactId>quarkus-maven-plugin</artifactId></plugin>
                    <plugin><artifactId>spring-boot-maven-plugin</artifactId></plugin>
                    </plugins></build></project>
                    """);
            Path unixWrapper = Files.writeString(project.resolve("mvnw"),
                    "#!/bin/sh\nprintf 'start:%s\\n' \"$1\"\nif [ \"$1\" = test ]; then exit 7; fi\n");
            unixWrapper.toFile().setExecutable(true);
            Files.writeString(project.resolve("mvnw.cmd"), "@echo off\r\necho start:%1\r\nif \"%1\"==\"test\" exit /b 7\r\n");
            return Map.of("vintelli.local.project-root", project.toString(), "vintelli.config.dir", config.toString(),
                    "vintelli.inspect.enabled", "true");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public void stop() {
        if (directory == null) return;
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
