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

    /** Maven's command-line prefix for the conventional plugin artifact names. */
    public String prefix() {
        if (artifactId.endsWith("-maven-plugin")) {
            return artifactId.substring(0, artifactId.length() - "-maven-plugin".length());
        }
        if (artifactId.startsWith("maven-") && artifactId.endsWith("-plugin")) {
            return artifactId.substring("maven-".length(), artifactId.length() - "-plugin".length());
        }
        return artifactId.endsWith("-plugin")
                ? artifactId.substring(0, artifactId.length() - "-plugin".length())
                : artifactId;
    }

    public String goalId(String goal) {
        return prefix() + ":" + goal;
    }
}
