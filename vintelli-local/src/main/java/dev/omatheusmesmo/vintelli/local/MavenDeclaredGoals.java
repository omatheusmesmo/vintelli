package dev.omatheusmesmo.vintelli.local;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/** Reads executable plugin goals from the same POM sections shown by the Maven window. */
final class MavenDeclaredGoals {

    private static final int MAX_BUILD_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_MODULES = 500;

    private MavenDeclaredGoals() {
    }

    static Set<String> of(Path root, String selector) {
        Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException | RuntimeException unavailable) {
            return Set.of();
        }
        Map<String, Set<String>> goalsBySelector = new HashMap<>();
        try {
            collect(realRoot, realRoot, "", 0, new HashSet<>(), new int[1], goalsBySelector);
        } catch (IOException | XmlPullParserException | RuntimeException ignored) {
            return Set.of();
        }
        return Set.copyOf(goalsBySelector.getOrDefault(selector, Set.of()));
    }

    private static void collect(Path root, Path directory, String selector, int depth, Set<Path> visited,
            int[] counted, Map<String, Set<String>> goalsBySelector)
            throws IOException, XmlPullParserException {
        if (depth > MAX_DEPTH || ++counted[0] > MAX_MODULES) {
            throw new IOException("Maven project is too deep or has too many modules");
        }
        Path realDirectory = directory.toRealPath();
        if (!realDirectory.startsWith(root) || !visited.add(realDirectory)) {
            throw new IOException("Maven module is outside the project root or listed twice");
        }
        Path pom = realDirectory.resolve("pom.xml").toRealPath();
        if (!pom.startsWith(root)) {
            throw new IOException("pom.xml is outside the project root");
        }
        Model model = readModel(pom);
        goalsBySelector.put(selector, pluginGoals(model));
        for (String module : model.getModules()) {
            Path child = realDirectory.resolve(module).normalize();
            if (!child.startsWith(root)) {
                throw new IOException("Maven module is outside the project root");
            }
            Path realChild = child.toRealPath();
            if (!realChild.startsWith(root)) {
                throw new IOException("Maven module is outside the project root");
            }
            String childSelector = root.relativize(realChild).toString().replace('\\', '/');
            collect(root, realChild, childSelector, depth + 1, visited, counted, goalsBySelector);
        }
    }

    private static Model readModel(Path pom) throws IOException, XmlPullParserException {
        if (!Files.isRegularFile(pom) || Files.size(pom) > MAX_BUILD_BYTES) {
            throw new IOException("pom.xml is unavailable or too large");
        }
        String text;
        try (InputStream input = Files.newInputStream(pom)) {
            byte[] bytes = input.readNBytes(MAX_BUILD_BYTES + 1);
            if (bytes.length > MAX_BUILD_BYTES) {
                throw new IOException("pom.xml is too large");
            }
            text = new String(bytes, StandardCharsets.UTF_8);
        }
        if (text.contains("<!DOCTYPE")) {
            throw new IOException("DOCTYPE is not accepted");
        }
        return new MavenXpp3Reader().read(new StringReader(text));
    }

    private static Set<String> pluginGoals(Model model) {
        Build build = model.getBuild();
        if (build == null) {
            return Set.of();
        }
        Set<String> goals = new LinkedHashSet<>();
        for (Plugin plugin : build.getPlugins()) {
            String artifactId = plugin.getArtifactId();
            if (artifactId == null || artifactId.isBlank()) {
                continue;
            }
            String groupId = plugin.getGroupId() == null || plugin.getGroupId().isBlank()
                    ? "org.apache.maven.plugins" : plugin.getGroupId();
            for (PluginExecution execution : plugin.getExecutions()) {
                for (String goal : execution.getGoals()) {
                    if (!goal.isBlank()) {
                        goals.add(groupId + ":" + artifactId + ":" + goal.trim());
                    }
                }
            }
        }
        return Set.copyOf(goals);
    }
}
