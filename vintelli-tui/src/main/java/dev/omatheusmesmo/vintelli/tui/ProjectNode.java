package dev.omatheusmesmo.vintelli.tui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** One entry of the project tree: a file or a directory with its children and expansion state. */
final class ProjectNode {

    Path relative;
    String name;
    boolean directory;
    List<ProjectNode> children = new ArrayList<>();
    ProjectNode parent;
    boolean expanded;

    ProjectNode(Path relative, String name, boolean directory) {
        this.relative = relative;
        this.name = name;
        this.directory = directory;
    }

    int depth() {
        int depth = 0;
        for (ProjectNode ancestor = parent; ancestor != null; ancestor = ancestor.parent) {
            depth++;
        }
        return depth;
    }
}
