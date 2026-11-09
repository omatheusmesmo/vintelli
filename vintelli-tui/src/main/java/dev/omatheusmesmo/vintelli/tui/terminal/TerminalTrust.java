package dev.omatheusmesmo.vintelli.tui.terminal;

import java.util.Map;

/**
 * The environment checks that decide whether a terminal feature may run: a
 * variable counts when it is set to a non blank value, a session is remote
 * when an SSH variable is present, and a basic terminal is one that draws no
 * color at all. Each feature keeps its own list of trusted terminal names.
 */
public final class TerminalTrust {

    private TerminalTrust() {
    }

    public static boolean present(Map<String, String> env, String key) {
        String value = env.get(key);
        return value != null && !value.isBlank();
    }

    public static boolean remoteSession(Map<String, String> env) {
        return present(env, "SSH_CONNECTION") || present(env, "SSH_CLIENT") || present(env, "SSH_TTY");
    }

    public static boolean basicTerminal(Map<String, String> env) {
        String term = env.get("TERM");
        return "dumb".equalsIgnoreCase(term) || "linux".equalsIgnoreCase(term);
    }
}
