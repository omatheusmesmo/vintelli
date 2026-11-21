package dev.omatheusmesmo.vintelli.tui.maven;

import java.util.List;

/** A plugin declared in a module and the goals explicitly declared in its executions. */
public record MavenPlugin(String groupId, String artifactId, List<String> goals) {

    public MavenPlugin {
        groupId = groupId == null || groupId.isBlank() ? "org.apache.maven.plugins" : groupId;
        artifactId = artifactId == null ? "" : artifactId;
        goals = List.copyOf(goals == null ? List.of() : goals);
    }

    public String displayName() {
        return artifactId.isBlank() ? groupId : artifactId;
    }

    public String goalId(String goal) {
        return groupId + ":" + artifactId + ":" + goal;
    }
}
