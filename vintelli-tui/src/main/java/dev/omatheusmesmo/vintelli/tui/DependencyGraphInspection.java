package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraph;
import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraphProvider;

import java.nio.file.Path;
import java.util.List;

final class DependencyGraphInspection {

    private DependencyGraphInspection() {
    }

    static ProjectDependencyGraph read(String projectRoot, List<ProjectDependencyGraphProvider> providers) {
        Path root = Path.of(projectRoot);
        for (ProjectDependencyGraphProvider provider : providers) {
            if (provider.supports(root)) {
                return provider.read(root);
            }
        }
        return new ProjectDependencyGraph(List.of(), false, "No dependency provider supports the project");
    }
}
