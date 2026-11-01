package dev.omatheusmesmo.vintelli.api.terminal;

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
 *
 * <p>This is a heuristic, not a security boundary. It matches the base name
 * of the first word, with a Windows executable suffix removed, so it misses
 * wrappers and indirections such as {@code env rm -rf}, {@code sh -c
 * 'rm -rf x'}, {@code find . -delete}, {@code git clean -fdx}, {@code dd},
 * {@code mkfs}, {@code cmd}, {@code powershell} or {@code pwsh} (which
 * interpret their own command lines) or a program reached through an alias or
 * a path that differs from its name. Forced recursive removal is recognised
 * from POSIX short or long flags, PowerShell {@code Remove-Item}/{@code ri}/
 * {@code rm -Recurse -Force} parameters, or Windows {@code rmdir /s /q},
 * {@code rd /s /q} and {@code del /s /q} forms. Parsing is heuristic and does
 * not cover every spelling, abbreviation, parameter combination or
 * shell-specific interpretation. Nothing here limits what an approved command
 * does once it runs.
 */
public final class DangerousCommands {

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
    public static boolean requiresConfirmation(String program, List<String> args) {
        String base = programBaseName(program);
        if (SESSION_AND_MACHINE_CONTROL.contains(base)) {
            return true;
        }
        if ("rm".equals(base)) {
            return recursesForcibly(args) || powershellRecursiveForce(args);
        }
        if ("remove-item".equals(base) || "ri".equals(base)) {
            return powershellRecursiveForce(args);
        }
        if ("rmdir".equals(base) || "rd".equals(base) || "del".equals(base)) {
            return hasOption(args, "/s") && hasOption(args, "/q");
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

    private static boolean hasOption(List<String> args, String option) {
        return args.stream().anyMatch(argument -> option.equalsIgnoreCase(argument));
    }

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

    private static boolean powershellRecursiveForce(List<String> args) {
        boolean recursive = hasOption(args, "-recurse") || hasOption(args, "-r");
        boolean force = hasOption(args, "-force") || hasOption(args, "-f");
        return recursive && force;
    }
}
