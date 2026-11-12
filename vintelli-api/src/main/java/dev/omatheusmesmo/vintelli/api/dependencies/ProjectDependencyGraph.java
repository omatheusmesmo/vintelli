package dev.omatheusmesmo.vintelli.api.dependencies;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Dependency information shown under a project's External Libraries node. */
public record ProjectDependencyGraph(List<Module> modules, boolean complete, String message) {

    public ProjectDependencyGraph {
        modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
        message = Objects.requireNonNullElse(message, "");
    }

    public record Module(String name, List<LibraryDependency> dependencies) {
        public Module {
            name = Objects.requireNonNull(name, "name");
            dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        }
    }

    public record LibraryDependency(String groupId, String artifactId, String version, String scope,
            Disposition disposition, Path jar, List<LibraryDependency> children, boolean optional) {
        public LibraryDependency(String groupId, String artifactId, String version, String scope,
                Disposition disposition, Path jar, List<LibraryDependency> children) {
            this(groupId, artifactId, version, scope, disposition, jar, children, false);
        }

        public LibraryDependency {
            groupId = Objects.requireNonNullElse(groupId, "");
            artifactId = Objects.requireNonNullElse(artifactId, "unknown");
            version = Objects.requireNonNullElse(version, "?");
            scope = Objects.requireNonNullElse(scope, "compile");
            disposition = Objects.requireNonNull(disposition, "disposition");
            children = List.copyOf(Objects.requireNonNull(children, "children"));
        }

        public String coordinates() {
            return groupId.isBlank() ? artifactId + ":" + version
                    : groupId + ":" + artifactId + ":" + version;
        }
    }

    public enum Disposition {
        RESOLVED,
        MISSING,
        OMITTED
    }
}
