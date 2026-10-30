package dev.omatheusmesmo.vintelli.acp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentArgumentFileTest {

    private static final int WINDOWS_COMMAND_LINE_LIMIT = 32_767;
    private static final int LONG_CLASSPATH_LENGTH = 40_000;

    @Test
    void longWindowsClasspathStaysOutOfTheCommandLine() throws IOException {
        String classpath = syntheticClasspath("C:\\m2\\repository\\dev\\example\\artifact", ";", "\\");

        assertRoundTripKeepsCommandLineShort(List.of("-cp", classpath, "dev.omatheusmesmo.vintelli.acp.EchoAcpAgent",
                "--protocol-version=2"));
    }

    @Test
    void longPosixClasspathStaysOutOfTheCommandLine() throws IOException {
        String classpath = syntheticClasspath("/home/dev/.m2/repository/dev/example/artifact", ":", "/");

        assertRoundTripKeepsCommandLineShort(List.of("-cp", classpath, "dev.omatheusmesmo.vintelli.acp.EchoAcpAgent"));
    }

    private static void assertRoundTripKeepsCommandLineShort(List<String> javaArguments) throws IOException {
        String classpath = javaArguments.get(1);
        assertTrue(classpath.length() >= LONG_CLASSPATH_LENGTH, "precondition: classpath must be long");

        String legacyAgentArgs = String.join(",", javaArguments);
        assertTrue(legacyAgentArgs.length() > WINDOWS_COMMAND_LINE_LIMIT,
                "the comma joined classpath used to exceed the Windows command line limit");

        Path file = AgentArgumentFile.write(javaArguments);
        try {
            String launcher = AgentArgumentFile.launcherArgument(file);
            assertTrue(launcher.length() < 1_000, () -> "launcher argument must stay short: " + launcher);
            assertEquals(javaArguments, parse(Files.readString(file, StandardCharsets.UTF_8)));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static String syntheticClasspath(String entryPrefix, String separator, String pathSeparator) {
        StringBuilder classpath = new StringBuilder();
        for (int index = 0; classpath.length() < LONG_CLASSPATH_LENGTH; index++) {
            if (!classpath.isEmpty()) {
                classpath.append(separator);
            }
            classpath.append(entryPrefix).append(pathSeparator).append("artifact-").append(index).append(".jar");
        }
        return classpath.toString();
    }

    private static List<String> parse(String content) {
        List<String> arguments = new ArrayList<>();
        for (String line : content.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            assertEquals('"', line.charAt(0));
            assertEquals('"', line.charAt(line.length() - 1));
            StringBuilder argument = new StringBuilder();
            for (int index = 1; index < line.length() - 1; index++) {
                char current = line.charAt(index);
                if (current == '\\') {
                    index++;
                    current = line.charAt(index);
                }
                argument.append(current);
            }
            arguments.add(argument.toString());
        }
        return arguments;
    }
}
