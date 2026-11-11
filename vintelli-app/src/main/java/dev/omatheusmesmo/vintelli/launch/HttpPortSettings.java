package dev.omatheusmesmo.vintelli.launch;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * The HTTP port each mode boots with. The headless {@code serve} command keeps
 * the documented port 8080, while the TUI, CLI and REPL bind a free loopback
 * port so that several instances run side by side. An explicitly configured
 * port always wins, and an explicit port that is already taken is reported
 * before Quarkus starts, because Quarkus would otherwise exit without a word.
 */
public final class HttpPortSettings {

    static final String PORT = "quarkus.http.port";
    static final String HOST = "quarkus.http.host";
    static final String DEFAULT_HOST = "127.0.0.1";
    static final int FREE_PORT = 0;

    private HttpPortSettings() {
    }

    /**
     * The port entries the mode boots with, before the user's own settings
     * are applied.
     */
    public static Map<String, String> forMode(RunMode mode) {
        return mode == RunMode.SERVER ? Map.of() : Map.of(PORT, String.valueOf(FREE_PORT));
    }

    /**
     * The message for an explicitly configured port that is already in use on
     * the configured host, or empty when no port was asked for or it is free.
     */
    public static Optional<String> takenExplicitPort(Properties systemProperties, Map<String, String> env) {
        Optional<String> configuredPort = configured(PORT, systemProperties, env);
        if (configuredPort.isEmpty()) {
            return Optional.empty();
        }
        int port = parsePort(configuredPort.get());
        if (port == FREE_PORT) {
            return Optional.empty();
        }
        String host = configured(HOST, systemProperties, env).orElse(DEFAULT_HOST);
        return isTaken(host, port) ? Optional.of(refusal(host, port)) : Optional.empty();
    }

    static String refusal(String host, int port) {
        return "Vintelli cannot start: port " + port + " on " + host + " is already in use."
                + System.lineSeparator()
                + "Stop the process that holds it, or set quarkus.http.port to a free port"
                + " (unset it to let Vintelli pick one)."
                + System.lineSeparator();
    }

    private static Optional<String> configured(String key, Properties systemProperties, Map<String, String> env) {
        String property = systemProperties.getProperty(key);
        if (property != null) {
            return Optional.of(property);
        }
        return Optional.ofNullable(env.get(LogSettings.environmentName(key)));
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notAPort) {
            return FREE_PORT;
        }
    }

    private static boolean isTaken(String host, int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(host, port));
            return false;
        } catch (BindException alreadyInUse) {
            return true;
        } catch (IOException unusable) {
            return false;
        }
    }
}
