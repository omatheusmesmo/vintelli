package dev.omatheusmesmo.vintelli.lsp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Finds a Java language server when no profile is configured: the {@code jdtls}
 * executable on {@code PATH}, resolved like a bare profile command. On Windows
 * the {@code .cmd}, {@code .bat} and {@code .exe} launchers are tried in that
 * order. The result is an implicit profile whose data directory sits under the
 * user cache and is keyed by the project root, so one server per project keeps
 * its index.
 *
 * <p>The environment, the operating system, the home directory and the project
 * root are handed in, so the lookup never reads the machine it runs on by
 * itself. It only checks files, and starts no process.
 */
final class LanguageServerDiscovery {

    static final String PROFILE = "jdtls";
    static final String NO_SERVER_REASON = "No language server found. Install jdtls and put it on the PATH, or set "
            + "vintelli.lsp.profiles.<name>.command (see docs/language-servers.md).";

    private static final String APPLICATION = "vintelli";
    private static final String DATA_DIRECTORY = "jdtls-data";
    private static final int DATA_KEY_BYTES = 8;

    private final boolean enabled;
    private final Map<String, String> environment;
    private final String osName;
    private final String userHome;
    private final Path projectRoot;

    LanguageServerDiscovery(boolean enabled, Map<String, String> environment, String osName, String userHome,
            Path projectRoot) {
        this.enabled = enabled;
        this.environment = Map.copyOf(environment);
        this.osName = osName == null ? "" : osName;
        this.userHome = userHome == null ? "" : userHome;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    static LanguageServerDiscovery system(boolean enabled, Path projectRoot) {
        return new LanguageServerDiscovery(enabled, System.getenv(), System.getProperty("os.name", ""),
                System.getProperty("user.home", ""), projectRoot);
    }

    /**
     * @return the discovered executable with the implicit profile that runs it, or
     *         empty when discovery is disabled or no candidate is executable on
     *         {@code PATH}
     */
    Optional<Discovered> discover() {
        if (!enabled) {
            return Optional.empty();
        }
        String path = environment.get("PATH");
        for (String candidate : candidateNames()) {
            Optional<Path> executable = LspClient.resolve(candidate, path);
            if (executable.isPresent()) {
                return Optional.of(new Discovered(executable.get(), new ImplicitProfile(executable.get(),
                        dataDirectory(), projectRoot)));
            }
        }
        return Optional.empty();
    }

    private List<String> candidateNames() {
        return isWindows() ? List.of(PROFILE + ".cmd", PROFILE + ".bat", PROFILE + ".exe") : List.of(PROFILE);
    }

    private Path dataDirectory() {
        String key = HexFormat.of().formatHex(projectKey(), 0, DATA_KEY_BYTES);
        return cacheRoot().resolve(APPLICATION).resolve(DATA_DIRECTORY).resolve(key);
    }

    private Path cacheRoot() {
        if (isWindows()) {
            return nonBlank(environment.get("LOCALAPPDATA"))
                    .map(Path::of)
                    .orElseGet(() -> Path.of(userHome, "AppData", "Local"));
        }
        if (osName.toLowerCase(Locale.ROOT).contains("mac")) {
            return Path.of(userHome, "Library", "Caches");
        }
        return nonBlank(environment.get("XDG_CACHE_HOME"))
                .map(Path::of)
                .filter(Path::isAbsolute)
                .orElseGet(() -> Path.of(userHome, ".cache"));
    }

    private byte[] projectKey() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(projectRoot.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", unavailable);
        }
    }

    private boolean isWindows() {
        return osName.toLowerCase(Locale.ROOT).contains("win");
    }

    private static Optional<String> nonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    /**
     * The executable found on {@code PATH} and the implicit profile that runs it.
     */
    record Discovered(Path executable, LspProfileConfig.Profile profile) {
    }

    /**
     * The profile of a discovered server: the executable, a data directory for
     * the server and the project root as workspace. It sets no Java minimum, so
     * the runtime check does not apply to it.
     */
    private record ImplicitProfile(Path executable, Path dataDirectory, Path project)
            implements LspProfileConfig.Profile {

        @Override
        public Optional<String> command() {
            return Optional.of(executable.toString());
        }

        @Override
        public Optional<List<String>> args() {
            return Optional.of(List.of("-data", dataDirectory.toString()));
        }

        @Override
        public Optional<String> workspaceRoot() {
            return Optional.of(project.toString());
        }

        @Override
        public Optional<String> initializationOptions() {
            return Optional.empty();
        }

        @Override
        public Optional<Integer> javaMinVersion() {
            return Optional.empty();
        }

        @Override
        public Optional<String> javaHome() {
            return Optional.empty();
        }
    }
}
