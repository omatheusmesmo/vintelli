package dev.omatheusmesmo.vintelli.launch;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * The {@code quarkus.log.*} properties each mode boots with. They are plain
 * runtime configuration, applied as system properties before Quarkus starts,
 * so a property the user already set (system property or environment variable)
 * always wins.
 */
public final class LogSettings {

    static final String CONSOLE_ENABLED = "quarkus.log.console.enabled";
    static final String CONSOLE_STDERR = "quarkus.log.console.stderr";
    static final String CONSOLE_LEVEL = "quarkus.log.console.level";
    static final String FILE_ENABLED = "quarkus.log.file.enabled";
    static final String FILE_PATH = "quarkus.log.file.path";
    static final String FILE_LEVEL = "quarkus.log.file.level";
    static final String APP_CATEGORY_LEVEL = "quarkus.log.category.\"dev.omatheusmesmo\".level";

    private LogSettings() {
    }

    /**
     * Whether the mode writes a log file, which decides if the log directory
     * has to be prepared at all.
     */
    public static boolean usesFile(RunMode mode, boolean debug) {
        return debug || mode == RunMode.TUI;
    }

    public static Map<String, String> forMode(RunMode mode, boolean debug, Optional<Path> logFile) {
        Map<String, String> settings = new LinkedHashMap<>();
        switch (mode) {
            case TUI -> settings.put(CONSOLE_ENABLED, "false");
            case CLI, REPL -> {
                settings.put(CONSOLE_STDERR, "true");
                settings.put(CONSOLE_LEVEL, "ERROR");
            }
            case SERVER -> {
            }
        }
        if (debug) {
            settings.put(APP_CATEGORY_LEVEL, "DEBUG");
        }
        logFile.ifPresent(file -> {
            settings.put(FILE_ENABLED, "true");
            settings.put(FILE_PATH, file.toString());
            settings.put(FILE_LEVEL, debug ? "DEBUG" : "WARN");
        });
        return settings;
    }

    /**
     * The entries of {@code settings} the user did not already configure
     * through a system property or an environment variable.
     */
    public static Map<String, String> notYetConfigured(Map<String, String> settings, Properties systemProperties,
            Map<String, String> env) {
        Map<String, String> missing = new LinkedHashMap<>();
        settings.forEach((key, value) -> {
            if (systemProperties.getProperty(key) == null && !env.containsKey(environmentName(key))) {
                missing.put(key, value);
            }
        });
        return missing;
    }

    static String environmentName(String property) {
        StringBuilder name = new StringBuilder(property.length());
        for (int i = 0; i < property.length(); i++) {
            char c = property.charAt(i);
            name.append(Character.isLetterOrDigit(c) ? Character.toUpperCase(c) : '_');
        }
        return name.toString();
    }
}
