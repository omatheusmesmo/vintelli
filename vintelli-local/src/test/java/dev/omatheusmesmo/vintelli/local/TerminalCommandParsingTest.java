package dev.omatheusmesmo.vintelli.local;

import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pure behaviour of the terminal argv parser and the dangerous command gate:
 * quoting stays inside one argument, operator characters are plain
 * characters, and the programs that erase or control the machine always come
 * back as an explicit confirmation request.
 */
class TerminalCommandParsingTest {

    @Test
    void splitsOnWhitespaceAndKeepsQuotedWordsTogether() {
        assertIterableEquals(List.of("mvn", "clean", "install", "-Pprofile"),
                TerminalCommandParser.parse("mvn  clean   install -Pprofile"));
        assertIterableEquals(List.of("git", "commit", "-m", "one long message"),
                TerminalCommandParser.parse("git commit -m 'one long message'"));
        assertIterableEquals(List.of("echo", "double"),
                TerminalCommandParser.parse("echo \"double\""));
    }

    @Test
    void treatsOperatorCharactersAsPlainArgumentsInsteadOfChaining() {
        assertIterableEquals(List.of("vintelli-clean", "&&", "rm", "-rf", "/"),
                TerminalCommandParser.parse("vintelli-clean && rm -rf /"));
        assertIterableEquals(List.of("sh", "-c", "echo hi"), TerminalCommandParser.parse("sh -c 'echo hi'"));
    }

    @Test
    void rejectsEmptyLinesAndUnterminatedQuotes() {
        assertThrows(IllegalArgumentException.class, () -> TerminalCommandParser.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> TerminalCommandParser.parse("echo 'open"));
        assertThrows(IllegalArgumentException.class, () -> TerminalCommandParser.parse("echo \"open"));
    }

    @Test
    void gatesElevationAndMachineControlCommandsBehindConfirmation() {
        for (String program : List.of("sudo", "/usr/bin/sudo", "SUDO.EXE", "shutdown", "shutdown.cmd",
                "/sbin/init", "doas", "poweroff")) {
            assertTrue(DangerousCommands.requiresConfirmation(program, List.of("anything")),
                    program + " must require confirmation");
        }
    }

    @Test
    void gatesTheRecursiveForcedRemovalButNotABuildDirectory() {
        assertTrue(DangerousCommands.requiresConfirmation("rm", List.of("-rf", "/")));
        assertTrue(DangerousCommands.requiresConfirmation("rm", List.of("-fr")));
        assertTrue(DangerousCommands.requiresConfirmation("rm", List.of("--recursive", "--force")));
        assertTrue(DangerousCommands.requiresConfirmation("rm", List.of("-r", "-f")));
        assertTrue(DangerousCommands.requiresConfirmation("rm.exe", List.of("-rf", "target")));
        assertFalse(DangerousCommands.requiresConfirmation("rm", List.of("-r")));
        assertFalse(DangerousCommands.requiresConfirmation("rm", List.of()));
    }
}
