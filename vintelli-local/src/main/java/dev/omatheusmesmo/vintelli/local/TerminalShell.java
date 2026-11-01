package dev.omatheusmesmo.vintelli.local;

import java.util.List;

/** A resolved shell executable and the argument vectors used to launch it. */
record TerminalShell(String id, String displayName, String executable, Kind kind) {

    enum Kind {
        POSIX,
        COMMAND_PROMPT,
        POWERSHELL
    }

    List<String> interactiveCommand() {
        return switch (kind) {
            case POSIX -> List.of(executable, "-i");
            case COMMAND_PROMPT -> List.of(executable, "/d", "/q");
            case POWERSHELL -> List.of(executable, "-NoLogo", "-NoExit");
        };
    }

    List<String> oneShotCommand(String commandLine) {
        return switch (kind) {
            case POSIX -> List.of(executable, "-c", commandLine);
            case COMMAND_PROMPT -> List.of(executable, "/d", "/c", commandLine);
            case POWERSHELL -> List.of(executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                    commandLine);
        };
    }
}
