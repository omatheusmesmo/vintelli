package dev.omatheusmesmo.vintelli;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.launch.LaunchOptions;
import dev.omatheusmesmo.vintelli.launch.LogFileLocation;
import dev.omatheusmesmo.vintelli.launch.LogSettings;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Resolves the mode and {@code --debug} before Quarkus boots, because logging
 * is configured at boot, then hands the remaining arguments to
 * {@link VintelliMain}.
 */
@QuarkusMain
public class VintelliLauncher {

    public static void main(String[] args) {
        LaunchOptions options = LaunchOptions.parse(args, System.getenv());
        Optional<Path> logFile = LogSettings.usesFile(options.mode(), options.debug())
                ? LogFileLocation.forCurrentUser()
                : Optional.empty();
        Map<String, String> settings = LogSettings.forMode(options.mode(), options.debug(), logFile);
        LogSettings.notYetConfigured(settings, System.getProperties(), System.getenv())
                .forEach(System::setProperty);
        if (options.debug()) {
            announce(logFile);
        }
        Quarkus.run(VintelliMain.class, options.argArray());
    }

    private static void announce(Optional<Path> logFile) {
        System.err.println(logFile
                .map(file -> "Vintelli debug log: " + file)
                .orElse("Vintelli debug log: the log directory is not writable, file logging is off"));
    }
}
