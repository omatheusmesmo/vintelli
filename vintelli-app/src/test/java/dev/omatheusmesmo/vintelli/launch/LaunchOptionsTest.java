package dev.omatheusmesmo.vintelli.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LaunchOptionsTest {

    private static LaunchOptions parse(Map<String, String> env, String... args) {
        return LaunchOptions.parse(args, env);
    }

    @Test
    void withoutFlagsOrEnvironmentDebugIsOff() {
        LaunchOptions options = parse(Map.of(), "inspect");
        assertFalse(options.debug());
        assertEquals(List.of("inspect"), options.args());
    }

    @Test
    void leadingDebugFlagTurnsDebugOnAndIsStripped() {
        LaunchOptions options = parse(Map.of(), "--debug", "inspect");
        assertTrue(options.debug());
        assertEquals(RunMode.CLI, options.mode());
        assertEquals(List.of("inspect"), options.args());
    }

    @Test
    void debugAloneOpensTheTui() {
        LaunchOptions options = parse(Map.of(), "--debug");
        assertTrue(options.debug());
        assertEquals(RunMode.TUI, options.mode());
        assertEquals(List.of(), options.args());
    }

    @Test
    void debugBeforeServeKeepsTheHeadlessServer() {
        LaunchOptions options = parse(Map.of(), "--debug", "serve");
        assertTrue(options.debug());
        assertEquals(RunMode.SERVER, options.mode());
        assertEquals(List.of("serve"), options.args());
    }

    @Test
    void debugNextToAModeFlagIsStrippedAndKeepsTheMode() {
        LaunchOptions options = parse(Map.of(), "--tui", "--debug");
        assertTrue(options.debug());
        assertEquals(RunMode.TUI, options.mode());
        assertEquals(List.of("--tui"), options.args());
    }

    @Test
    void debugAfterACommandWordBelongsToTheCommand() {
        LaunchOptions options = parse(Map.of(), "terminal", "git", "--debug");
        assertFalse(options.debug());
        assertEquals(List.of("terminal", "git", "--debug"), options.args());
    }

    @Test
    void environmentVariableTurnsDebugOn() {
        assertTrue(parse(Map.of("VINTELLI_DEBUG", "1"), "inspect").debug());
        assertTrue(parse(Map.of("VINTELLI_DEBUG", "true"), "inspect").debug());
    }

    @Test
    void otherEnvironmentValuesKeepDebugOff() {
        assertFalse(parse(Map.of("VINTELLI_DEBUG", "0"), "inspect").debug());
        assertFalse(parse(Map.of("VINTELLI_DEBUG", ""), "inspect").debug());
    }

    @Test
    void argArrayCarriesTheStrippedArguments() {
        assertEquals(List.of("--repl"), List.of(parse(Map.of(), "--debug", "--repl").argArray()));
    }
}
