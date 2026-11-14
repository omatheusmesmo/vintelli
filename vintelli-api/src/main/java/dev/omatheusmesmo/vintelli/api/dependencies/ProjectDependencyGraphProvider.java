package dev.omatheusmesmo.vintelli.api.dependencies;

import java.nio.file.Path;

/** Provider for build-system dependency graphs. Implementations must never block a render thread. */
public interface ProjectDependencyGraphProvider {

    boolean supports(Path projectRoot);

    ProjectDependencyGraph read(Path projectRoot);
}
