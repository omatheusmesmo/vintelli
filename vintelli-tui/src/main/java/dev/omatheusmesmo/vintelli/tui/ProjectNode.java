package dev.omatheusmesmo.vintelli.tui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraph.Disposition;

/** One entry of the project tree: a file or a directory with its children and expansion state. */
final class ProjectNode {

    Path relative;
    String name;
    boolean directory;
    List<ProjectNode> children = new ArrayList<>();
    ProjectNode parent;
    boolean expanded;
    boolean externalLibraries;
    boolean dependency;
    String scope = "";
    boolean optional;
    Disposition disposition;
    Path jar;
    String status = "";

    ProjectNode(Path relative, String name, boolean directory) {
        this.relative = relative;
        this.name = name;
        this.directory = directory;
    }

    static ProjectNode externalLibraries(Path relative, String label) {
        ProjectNode node = new ProjectNode(relative, label, true);
        node.externalLibraries = true;
        return node;
    }

    boolean compactsChild() {
        return directory && !externalLibraries && children.size() == 1 && children.getFirst().directory;
    }

    ProjectNode compactEnd() {
        ProjectNode end = this;
        while (end.compactsChild()) {
            end = end.children.getFirst();
        }
        return end;
    }

    String compactName() {
        StringBuilder name = new StringBuilder(this.name);
        for (ProjectNode ancestor = parent; ancestor != null && ancestor.compactsChild(); ancestor = ancestor.parent) {
            name.insert(0, ancestor.name + "/");
        }
        return name.toString();
    }

    /** The number of rows above this node: the ancestors that are not folded into a compacted chain. */
    int depth() {
        int depth = 0;
        for (ProjectNode ancestor = parent; ancestor != null; ancestor = ancestor.parent) {
            if (!ancestor.compactsChild()) {
                depth++;
            }
        }
        return depth;
    }
}
