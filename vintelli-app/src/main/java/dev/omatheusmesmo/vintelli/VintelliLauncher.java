package dev.omatheusmesmo.vintelli;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.launch.HttpPortSettings;
import dev.omatheusmesmo.vintelli.launch.LaunchOptions;
import dev.omatheusmesmo.vintelli.launch.LogFileLocation;
import dev.omatheusmesmo.vintelli.launch.LogSettings;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Resolves the mode and {@code --debug} before Quarkus boots, because logging
 * and the HTTP port are configured at boot, then hands the remaining arguments
 * to {@link VintelliMain}.
 */
@QuarkusMain
public class VintelliLauncher {

    public static void main(String[] args) {
        LaunchOptions options = LaunchOptions.parse(args, System.getenv());
        Optional<String> takenPort = HttpPortSettings.takenExplicitPort(System.getProperties(), System.getenv());
        if (takenPort.isPresent()) {
            System.err.print(takenPort.get());
            System.exit(1);
        }
        Optional<Path> logFile = LogSettings.usesFile(options.mode(), options.debug())
                ? LogFileLocation.forCurrentUser()
                : Optional.empty();
        Map<String, String> settings = new LinkedHashMap<>(
                LogSettings.forMode(options.mode(), options.debug(), logFile));
        settings.putAll(HttpPortSettings.forMode(options.mode()));
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
