package dev.omatheusmesmo.vintelli.launch;

import java.util.List;

/**
 * The surface a Vintelli process serves, resolved from the command line
 * arguments. This is the single place that maps arguments to a mode, so both
 * the logging setup before Quarkus boots and the entry point that runs the
 * surface agree on it.
 */
public enum RunMode {

    TUI,
    CLI,
    REPL,
    SERVER;

    static final String TUI_FLAG = "--tui";
    static final String REPL_FLAG = "--repl";

    /**
     * Resolves the mode for arguments that no longer carry {@code --debug}.
     */
    public static RunMode of(List<String> args) {
        if (args.contains(TUI_FLAG)) {
            return TUI;
        }
        if (args.isEmpty()) {
            return SERVER;
        }
        if (REPL_FLAG.equals(args.get(0))) {
            return REPL;
        }
        return CLI;
    }
}
