package dev.omatheusmesmo.vintelli.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Where the Vintelli log file lives for the current user: the XDG state
 * directory on Linux, {@code ~/Library/Logs} on macOS and
 * {@code %LOCALAPPDATA%} on Windows.
 */
public final class LogFileLocation {

    static final String DIRECTORY = "vintelli";
    static final String FILE_NAME = "vintelli.log";

    private LogFileLocation() {
    }

    public static Path resolve(String osName, String userHome, Map<String, String> env) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String localAppData = env.get("LOCALAPPDATA");
            Path base = localAppData == null || localAppData.isBlank()
                    ? Path.of(userHome, "AppData", "Local")
                    : Path.of(localAppData);
            return base.resolve(DIRECTORY).resolve("logs").resolve(FILE_NAME);
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Path.of(userHome, "Library", "Logs", DIRECTORY, FILE_NAME);
        }
        String stateHome = env.get("XDG_STATE_HOME");
        Path base = stateHome != null && !stateHome.isBlank() && Path.of(stateHome).isAbsolute()
                ? Path.of(stateHome)
                : Path.of(userHome, ".local", "state");
        return base.resolve(DIRECTORY).resolve(FILE_NAME);
    }

    /**
     * Creates the directory of {@code file}; returns empty when it cannot be
     * created, so a read-only home never keeps Vintelli from starting.
     */
    public static Optional<Path> prepare(Path file) {
        try {
            Files.createDirectories(file.getParent());
            return Optional.of(file);
        } catch (IOException | RuntimeException failure) {
            return Optional.empty();
        }
    }

    public static Optional<Path> forCurrentUser() {
        return prepare(resolve(System.getProperty("os.name", ""), System.getProperty("user.home", ""), System.getenv()));
    }
}
