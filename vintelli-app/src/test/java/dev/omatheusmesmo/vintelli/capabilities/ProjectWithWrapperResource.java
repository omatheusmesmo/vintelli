package dev.omatheusmesmo.vintelli.capabilities;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * Points the project root of the local providers at a fresh Maven project
 * that carries both wrapper flavours, so the run provider detects the
 * wrapper on every host: the batch file on Windows, the executable script
 * elsewhere. The directory is removed when the application stops.
 */
public final class ProjectWithWrapperResource implements QuarkusTestResourceLifecycleManager {

    private Path project;

    @Override
    public Map<String, String> start() {
        try {
            project = Files.createTempDirectory("vintelli-wrapper-project");
            Files.writeString(project.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
            Files.writeString(project.resolve("mvnw.cmd"), "@echo off", StandardCharsets.US_ASCII);
            Path script = Files.writeString(project.resolve("mvnw"), "#!/bin/sh", StandardCharsets.UTF_8);
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(script, Set.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.of("vintelli.local.project-root", project.toString());
    }

    @Override
    public void stop() {
        if (project == null) {
            return;
        }
        try (Stream<Path> tree = Files.walk(project)) {
            tree.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
