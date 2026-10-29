package dev.omatheusmesmo.vintelli.local;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Classification of the terminal command words Vintelli wants an explicit
 * user confirmation for before any child process starts: elevation and
 * machine or session control commands, and a recursive forced removal, which
 * is irreversible wherever it points. The gate is on the program name, so
 * payload arguments never change the decision and stay visible to the user
 * who confirms.
 */
final class DangerousCommands {

    private static final Set<String> SESSION_AND_MACHINE_CONTROL = Set.of(
            "sudo", "su", "doas", "shutdown", "reboot", "halt", "poweroff", "init");

    private static final Set<String> WINDOWS_EXECUTABLE_SUFFIXES = Set.of(".exe", ".cmd", ".bat", ".com");

    private DangerousCommands() {
    }

    /**
     * @param program first parsed token of a command line
     * @param args    remaining tokens
     * @return true when the caller must reissue the request with an explicit
     *         confirmation flag
     */
    static boolean requiresConfirmation(String program, List<String> args) {
        String base = programBaseName(program);
        if (SESSION_AND_MACHINE_CONTROL.contains(base)) {
            return true;
        }
        if ("rm".equals(base)) {
            return recursesForcibly(args);
        }
        return false;
    }

    private static String programBaseName(String program) {
        String lower = program.toLowerCase(Locale.ROOT);
        for (String suffix : WINDOWS_EXECUTABLE_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                lower = lower.substring(0, lower.length() - suffix.length());
                break;
            }
        }
        int separator = lower.lastIndexOf('/');
        int backslash = lower.lastIndexOf('\\');
        int last = Math.max(separator, backslash);
        return last >= 0 ? lower.substring(last + 1) : lower;
    }

    /**
     * Matches both the verbose {@code --recursive} with {@code --force} and
     * the affinity groups inside one dashed short flag such as {@code -rf} or
     * {@code -fr}.
     */
    private static boolean recursesForcibly(List<String> args) {
        boolean recursive = false;
        boolean force = false;
        for (String arg : args) {
            if (!arg.startsWith("-") || arg.length() < 2) {
                continue;
            }
            if ("--recursive".equals(arg)) {
                recursive = true;
            } else if ("--force".equals(arg)) {
                force = true;
            } else if (!arg.startsWith("--")) {
                for (int index = 1; index < arg.length(); index++) {
                    char flag = arg.charAt(index);
                    if (flag == 'r' || flag == 'R') {
                        recursive = true;
                    } else if (flag == 'f') {
                        force = true;
                    }
                }
            }
        }
        return recursive && force;
    }
}
