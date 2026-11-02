package dev.omatheusmesmo.vintelli.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class RunModeTest {

    @Test
    void noArgumentsServeHeadless() {
        assertEquals(RunMode.SERVER, RunMode.of(List.of()));
    }

    @Test
    void tuiFlagSelectsTheTui() {
        assertEquals(RunMode.TUI, RunMode.of(List.of("--tui")));
    }

    @Test
    void tuiFlagWinsWhereverItAppears() {
        assertEquals(RunMode.TUI, RunMode.of(List.of("inspect", "--tui")));
    }

    @Test
    void leadingReplFlagSelectsTheRepl() {
        assertEquals(RunMode.REPL, RunMode.of(List.of("--repl")));
    }

    @Test
    void replFlagAfterACommandIsPartOfTheCommand() {
        assertEquals(RunMode.CLI, RunMode.of(List.of("terminal", "--repl")));
    }

    @Test
    void anyOtherArgumentRunsACliCommand() {
        assertEquals(RunMode.CLI, RunMode.of(List.of("inspect")));
    }
}
