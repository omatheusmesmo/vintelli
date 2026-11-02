package dev.omatheusmesmo.vintelli.launch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the command line and the environment say about one process start: the
 * mode, whether detailed logging was requested, and the arguments left for the
 * application once the {@code --debug} flag is removed.
 *
 * <p>{@code --debug} is recognised only among the leading flags (before the
 * first command word), so it never swallows an argument that belongs to a
 * command such as {@code terminal git --debug}. {@code VINTELLI_DEBUG=1}
 * (or {@code true}) turns it on from the environment.
 */
public record LaunchOptions(RunMode mode, boolean debug, List<String> args) {

    static final String DEBUG_FLAG = "--debug";
    static final String DEBUG_ENV = "VINTELLI_DEBUG";

    public static LaunchOptions parse(String[] args, Map<String, String> env) {
        boolean debug = isEnabled(env.get(DEBUG_ENV));
        List<String> remaining = new ArrayList<>(args.length);
        boolean leading = true;
        for (String arg : args) {
            if (leading && DEBUG_FLAG.equals(arg)) {
                debug = true;
                continue;
            }
            if (!arg.startsWith("--")) {
                leading = false;
            }
            remaining.add(arg);
        }
        List<String> kept = List.copyOf(remaining);
        return new LaunchOptions(RunMode.of(kept), debug, kept);
    }

    public String[] argArray() {
        return args.toArray(String[]::new);
    }

    private static boolean isEnabled(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }
}
