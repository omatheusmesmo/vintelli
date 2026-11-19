package dev.omatheusmesmo.vintelli.acp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Registers the stdio twin as the {@code filesystem} agent of a chat service whose project root is a
 * fresh folder, with a secret file one level above it so a read through {@code ..} has a real target.
 */
public final class AcpChatFileSystemAgentResource extends EchoAcpAgentResource {

    static final String AGENT_ID = "filesystem";
    static final String NOTES = "notes-from-chat-session";
    static final String SECRET = "outside-the-project-root";

    private static Path workRoot;
    private static Path projectRoot;

    @Override
    public Map<String, String> start() {
        Map<String, String> properties = new HashMap<>(super.start());
        try {
            workRoot = Files.createTempDirectory("vintelli-acp-chat-fs-");
            projectRoot = Files.createDirectory(workRoot.resolve("project"));
            Files.writeString(projectRoot.resolve("notes.txt"), NOTES);
            Files.writeString(workRoot.resolve("secret.txt"), SECRET);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        properties.put("vintelli.acp.enabled", "true");
        properties.put("vintelli.local.project-root", projectRoot.toString());
        properties.put("vintelli.acp.agents." + AGENT_ID + ".name", "File system agent");
        properties.put("vintelli.acp.agents." + AGENT_ID + ".command", properties.get("vintelli.acp.probe.agent-command"));
        properties.put("vintelli.acp.agents." + AGENT_ID + ".args",
                listValue(properties.get("vintelli.acp.probe.agent-args")));
        return properties;
    }

    /**
     * The agent arguments are a list property whose converter treats a backslash as an escape, so a Windows
     * path in the argument file reference needs its backslashes doubled to survive.
     */
    private static String listValue(String argument) {
        return argument.replace("\\", "\\\\");
    }

    static Path projectRoot() {
        return projectRoot;
    }

    @Override
    public void stop() {
        super.stop();
        try {
            deleteTree(workRoot);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : ordered) {
                Files.deleteIfExists(path);
            }
        }
    }
}
