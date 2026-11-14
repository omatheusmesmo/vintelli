package dev.omatheusmesmo.vintelli.tui.maven;

import java.util.List;

/**
 * One node of the aggregator tree read from the {@code <modules>} elements.
 * The selector is the module directory relative to the project root with
 * forward slashes, and empty for the root project: it is the value the run
 * passes to {@code -pl}, and no {@code -pl} at all for the root. The profiles
 * are the ids declared in the module's own {@code pom.xml}, in pom order.
 */
public record MavenModule(String artifactId, String selector, List<String> profiles, List<MavenPlugin> plugins,
        List<MavenModule> modules) {

    public MavenModule {
        artifactId = artifactId == null ? "" : artifactId;
        selector = selector == null ? "" : selector;
        profiles = List.copyOf(profiles);
        plugins = List.copyOf(plugins);
        modules = List.copyOf(modules);
    }

    public MavenModule(String artifactId, String selector, List<MavenModule> modules) {
        this(artifactId, selector, List.of(), List.of(), modules);
    }

    public MavenModule(String artifactId, String selector, List<String> profiles, List<MavenModule> modules) {
        this(artifactId, selector, profiles, List.of(), modules);
    }
}
