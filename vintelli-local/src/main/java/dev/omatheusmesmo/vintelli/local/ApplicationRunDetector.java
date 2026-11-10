package dev.omatheusmesmo.vintelli.local;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import dev.omatheusmesmo.vintelli.local.BuildToolDetector.RunConfigurationSpecification;

/** Static discovery only: reading a build never evaluates it or starts its build tool. */
final class ApplicationRunDetector {

    private static final int MAX_BUILD_BYTES = 2 * 1024 * 1024;

    private ApplicationRunDetector() { }

    static List<RunConfigurationSpecification> detect(Path root, BuildToolDetector.BuildTool tool, List<String> command) {
        try {
            return switch (tool) {
                case MAVEN -> maven(root, command);
                case GRADLE -> gradle(root, command);
                case NONE -> List.of();
            };
        } catch (IOException | XmlPullParserException invalid) {
            return List.of();
        }
    }

    private static List<RunConfigurationSpecification> maven(Path root, List<String> command)
            throws IOException, XmlPullParserException {
        String text = read(root, root.resolve("pom.xml"));
        if (text.isEmpty() || text.contains("<!DOCTYPE")) return List.of();
        Model model = new MavenXpp3Reader().read(new StringReader(text));
        if ("pom".equals(model.getPackaging()) || model.getBuild() == null) return List.of();
        List<RunConfigurationSpecification> found = new ArrayList<>();
        for (Plugin plugin : model.getBuild().getPlugins()) {
            if (plugin.getArtifactId() == null) continue;
            switch (plugin.getArtifactId()) {
                case "quarkus-maven-plugin" -> add(found, command, "maven:quarkus:dev", "quarkus:dev");
                case "spring-boot-maven-plugin" -> add(found, command, "maven:spring-boot:run", "spring-boot:run");
                case "exec-maven-plugin" -> {
                    boolean mainClass = plugin.getConfiguration() instanceof Xpp3Dom configuration
                            && configuration.getChild("mainClass") != null
                            && configuration.getChild("mainClass").getValue() != null
                            && !configuration.getChild("mainClass").getValue().isBlank();
                    if (mainClass || !model.getProperties().getProperty("exec.mainClass", "").isBlank()) {
                        add(found, command, "maven:exec:java", "exec:java");
                    }
                }
                default -> { }
            }
        }
        return List.copyOf(found);
    }

    private static List<RunConfigurationSpecification> gradle(Path root, List<String> command) throws IOException {
        Path build = Files.isRegularFile(root.resolve("build.gradle.kts")) ? root.resolve("build.gradle.kts") : root.resolve("build.gradle");
        String text = read(root, build).replaceAll("(?s)/\\*.*?\\*/|(?m)//[^\\r\\n]*", " ");
        List<RunConfigurationSpecification> found = new ArrayList<>();
        if (plugin(text, "io.quarkus")) add(found, command, "gradle:quarkusDev", "quarkusDev");
        if (plugin(text, "org.springframework.boot")) add(found, command, "gradle:bootRun", "bootRun");
        if (plugin(text, "application") || bareApplicationPlugin(text)) {
            add(found, command, "gradle:run", "run");
        }
        return List.copyOf(found);
    }

    private static boolean plugin(String text, String id) {
        String literal = "['\"]" + Pattern.quote(id) + "['\"]";
        String declaration = "(?m)(?:^|[;{}])\\s*(?:id\\s*(?:\\(\\s*" + literal + "\\s*\\)|" + literal
                + ")|apply\\s+plugin\\s*:\\s*" + literal + "|apply\\s*\\(\\s*plugin\\s*=\\s*" + literal + "\\s*\\))";
        var matches = Pattern.compile(declaration).matcher(text);
        Pattern notApplied = Pattern.compile("\\bapply\\s*(?:\\(\\s*false\\s*\\)|false\\b)");
        while (matches.find()) {
            String tail = text.substring(matches.end()).split("[;\\r\\n}]", 2)[0];
            if (!notApplied.matcher(tail).find()) return true;
        }
        return false;
    }

    private static boolean bareApplicationPlugin(String text) {
        var blocks = Pattern.compile("(?s)\\bplugins\\s*\\{([^}]*)}").matcher(text);
        Pattern application = Pattern.compile("(?m)(?:^|[;\\r\\n])\\s*(?:application|`application`)\\s*(?=$|[;\\r\\n])");
        while (blocks.find()) {
            if (application.matcher(blocks.group(1)).find()) return true;
        }
        return false;
    }

    private static String read(Path root, Path file) throws IOException {
        if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(root)) return "";
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_BUILD_BYTES + 1);
            return bytes.length > MAX_BUILD_BYTES ? "" : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void add(List<RunConfigurationSpecification> found, List<String> command, String id, String task) {
        if (found.stream().anyMatch(existing -> existing.identifier().equals(id))) return;
        List<String> invocation = new ArrayList<>(command);
        invocation.add(task);
        found.add(new RunConfigurationSpecification(id, invocation));
    }
}
