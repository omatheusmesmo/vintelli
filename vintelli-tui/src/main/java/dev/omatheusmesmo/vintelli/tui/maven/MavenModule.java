package dev.omatheusmesmo.vintelli.tui.maven;

import java.util.List;

/**
 * One node of the aggregator tree read from the {@code <modules>} elements.
 * The selector is the module directory relative to the project root with
 * forward slashes, and empty for the root project: it is the value the run
 * passes to {@code -pl}, and no {@code -pl} at all for the root.
 */
public record MavenModule(String artifactId, String selector, List<MavenModule> modules) {

    public MavenModule {
        artifactId = artifactId == null ? "" : artifactId;
        selector = selector == null ? "" : selector;
        modules = List.copyOf(modules);
    }
}
