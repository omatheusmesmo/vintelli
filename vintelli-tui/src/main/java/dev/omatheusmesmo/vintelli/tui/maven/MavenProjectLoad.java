package dev.omatheusmesmo.vintelli.tui.maven;

/** The outcome of reading the project: a module tree, or the reason there is none. */
public sealed interface MavenProjectLoad {

    record Loaded(MavenModule root) implements MavenProjectLoad {
    }

    record Missing(String reason) implements MavenProjectLoad {
    }

    record Unreadable(String reason) implements MavenProjectLoad {
    }
}
