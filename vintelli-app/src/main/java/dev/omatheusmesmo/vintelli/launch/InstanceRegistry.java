package dev.omatheusmesmo.vintelli.launch;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongPredicate;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The per-user record of the running Vintelli instances: one
 * {@code instances/<pid>.json} file per process, holding the pid, the bound
 * HTTP port, the host and the canonical project root. A client resolves the
 * instance that owns its project through {@link #findByProjectRoot(Path)}.
 * A file whose pid is no longer alive is stale: it is ignored and deleted.
 */
public final class InstanceRegistry {

    static final String VINTELLI_DIRECTORY = "vintelli";
    static final String INSTANCES_DIRECTORY = "instances";
    static final String FILE_SUFFIX = ".json";
    static final String TEMPORARY_PREFIX = "instance-";
    static final String TEMPORARY_SUFFIX = ".tmp";
    static final String MCP_PATH = "/mcp";
    static final String LOOPBACK = "127.0.0.1";

    static final String OWNER_ONLY_FILE = "rw-------";
    static final String OWNER_ONLY_DIRECTORY = "rwx------";

    private final Path directory;
    private final ObjectMapper mapper;
    private final LongPredicate alive;

    public InstanceRegistry(Path directory, ObjectMapper mapper, LongPredicate alive) {
        this.directory = directory;
        this.mapper = mapper;
        this.alive = alive;
    }

    public static InstanceRegistry forCurrentUser(ObjectMapper mapper) {
        return new InstanceRegistry(
                defaultDirectory(System.getProperty("os.name", ""), System.getProperty("user.home", ""), System.getenv()),
                mapper, InstanceRegistry::isAlive);
    }

    /**
     * The {@code instances} directory under the per-user state directory: the
     * XDG state home on Linux, {@code ~/Library/Application Support} on macOS
     * and {@code %LOCALAPPDATA%} on Windows.
     */
    public static Path defaultDirectory(String osName, String userHome, Map<String, String> env) {
        String os = osName.toLowerCase(Locale.ROOT);
        Path base;
        if (os.contains("win")) {
            String localAppData = env.get("LOCALAPPDATA");
            base = localAppData == null || localAppData.isBlank()
                    ? Path.of(userHome, "AppData", "Local")
                    : Path.of(localAppData);
        } else if (os.contains("mac") || os.contains("darwin")) {
            base = Path.of(userHome, "Library", "Application Support");
        } else {
            String stateHome = env.get("XDG_STATE_HOME");
            base = stateHome != null && !stateHome.isBlank() && Path.of(stateHome).isAbsolute()
                    ? Path.of(stateHome)
                    : Path.of(userHome, ".local", "state");
        }
        return base.resolve(VINTELLI_DIRECTORY).resolve(INSTANCES_DIRECTORY);
    }

    public static boolean isAlive(long pid) {
        return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /**
     * The host a client can connect to: a wildcard bind is reached on the
     * loopback address instead.
     */
    public static String reachableHost(String configuredHost) {
        return switch (configuredHost.trim()) {
            case "", "0.0.0.0", "*" -> LOOPBACK;
            case "::", "[::]" -> "::1";
            default -> configuredHost.trim();
        };
    }

    /**
     * The canonical form of a project root, so the file and a lookup agree
     * whatever spelling, relative path or symbolic link was given.
     */
    public static Path canonical(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return Files.exists(absolute) ? absolute.toRealPath() : absolute;
        } catch (IOException unresolvable) {
            return absolute;
        }
    }

    public void publish(Instance instance) throws IOException {
        prepareDirectory();
        pruneStale();
        Path temporary = Files.createTempFile(directory, TEMPORARY_PREFIX, TEMPORARY_SUFFIX,
                ownerOnly(OWNER_ONLY_FILE));
        try {
            Files.write(temporary, mapper.writeValueAsBytes(toJson(instance)));
            Files.move(temporary, fileOf(instance.pid()), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void retire(long pid) throws IOException {
        Files.deleteIfExists(fileOf(pid));
    }

    /**
     * The live instance that owns {@code projectRoot}, or empty when none does.
     * Stale files met on the way are deleted; when several live instances
     * claim the same root, the most recently published one wins.
     */
    public Optional<Instance> findByProjectRoot(Path projectRoot) {
        Path root = canonical(projectRoot);
        return readLive().stream()
                .filter(candidate -> candidate.instance().projectRoot().equals(root))
                .max(Comparator.comparingLong(Candidate::modifiedMillis))
                .map(Candidate::instance);
    }

    private List<Candidate> readLive() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().endsWith(FILE_SUFFIX))
                    .map(this::readCandidate)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    private void pruneStale() {
        readLive();
    }

    private Optional<Candidate> readCandidate(Path file) {
        try {
            JsonNode node = mapper.readTree(file.toFile());
            long pid = node.path("pid").asLong(-1);
            int port = node.path("port").asInt(-1);
            String host = node.path("host").asText("");
            String projectRoot = node.path("projectRoot").asText("");
            if (pid <= 0 || port <= 0 || host.isBlank() || projectRoot.isBlank()) {
                return Optional.empty();
            }
            if (!alive.test(pid)) {
                Files.deleteIfExists(file);
                return Optional.empty();
            }
            Instance instance = new Instance(pid, port, host, Path.of(projectRoot));
            return Optional.of(new Candidate(instance, Files.getLastModifiedTime(file).toMillis()));
        } catch (IOException | RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private ObjectNode toJson(Instance instance) {
        return mapper.createObjectNode()
                .put("pid", instance.pid())
                .put("port", instance.port())
                .put("host", instance.host())
                .put("projectRoot", canonical(instance.projectRoot()).toString());
    }

    private Path fileOf(long pid) {
        return directory.resolve(pid + FILE_SUFFIX);
    }

    private void prepareDirectory() throws IOException {
        Files.createDirectories(directory);
        if (supportsPosix()) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(OWNER_ONLY_DIRECTORY));
        }
    }

    private static FileAttribute<?>[] ownerOnly(String permissions) {
        if (!supportsPosix()) {
            return new FileAttribute<?>[0];
        }
        return new FileAttribute<?>[] {
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))
        };
    }

    private static boolean supportsPosix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /**
     * A running Vintelli process: where it listens and which project it owns.
     */
    public record Instance(long pid, int port, String host, Path projectRoot) {

        public String mcpUrl() {
            String authority = host.contains(":") ? "[" + host + "]" : host;
            return "http://" + authority + ":" + port + MCP_PATH;
        }
    }

    private record Candidate(Instance instance, long modifiedMillis) {
    }
}
