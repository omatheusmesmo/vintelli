package dev.omatheusmesmo.vintelli.acp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Keeps the JVM launch of a test ACP agent short. The arguments, classpath
 * included, go into a JDK argument file, and the agent command line carries
 * only the {@code @file} reference, so a long test classpath cannot push the
 * process past the Windows command line limit.
 */
final class AgentArgumentFile {

    private AgentArgumentFile() {
    }

    static Path write(List<String> arguments) throws IOException {
        Path file = Files.createTempFile("vintelli-acp-agent-", ".args");
        file.toFile().deleteOnExit();
        Files.writeString(file, render(arguments), StandardCharsets.UTF_8);
        return file;
    }

    static String launcherArgument(Path file) {
        String reference = "@" + file.toAbsolutePath();
        if (reference.contains(",")) {
            throw new IllegalStateException("agent argument file path must not contain a comma: " + reference);
        }
        return reference;
    }

    static String render(List<String> arguments) {
        return arguments.stream()
                .map(AgentArgumentFile::quote)
                .collect(Collectors.joining("\n", "", "\n"));
    }

    private static String quote(String argument) {
        return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
