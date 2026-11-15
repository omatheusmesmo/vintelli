package dev.omatheusmesmo.vintelli.tui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.locks.LockSupport;

import dev.omatheusmesmo.vintelli.tui.keymap.ConfigDirectory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Saves only the selected configuration id, outside the checkout and scoped to its canonical root. */
@Singleton
public final class RunSelectionStore {

    private static final int REPLACE_ATTEMPTS = 4;
    private static final long FIRST_REPLACE_PAUSE_NANOS = Duration.ofMillis(25).toNanos();

    @FunctionalInterface
    interface Replacer {

        void replace(Path source, Path target, boolean atomic) throws IOException;
    }

    private static final Replacer FILE_SYSTEM = (source, target, atomic) -> Files.move(source, target, atomic
            ? new CopyOption[] {StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE}
            : new CopyOption[] {StandardCopyOption.REPLACE_EXISTING});

    private final Path file;
    private final Replacer replacer;

    @Inject
    public RunSelectionStore(@ConfigProperty(name = "vintelli.local.project-root", defaultValue = ".") String project,
            @ConfigProperty(name = "vintelli.config.dir") Optional<String> config) {
        this(ConfigDirectory.configRoot(config.orElseGet(ConfigDirectory::sharedConfigDir),
                System.getenv("XDG_CONFIG_HOME"), System.getenv("APPDATA"), System.getProperty("user.home")), Path.of(project));
    }

    public RunSelectionStore(Path configRoot, Path project) {
        this(configRoot, project, FILE_SYSTEM);
    }

    RunSelectionStore(Path configRoot, Path project, Replacer replacer) {
        Path canonical;
        try {
            canonical = project.toRealPath();
        } catch (IOException unavailable) {
            canonical = project.toAbsolutePath().normalize();
        }
        file = configRoot == null ? null : configRoot.resolve("vintelli/run-configurations").resolve(projectKey(canonical) + ".properties");
        this.replacer = replacer;
    }

    public Optional<String> load() {
        if (file == null || !Files.isRegularFile(file)) return Optional.empty();
        try (InputStream input = Files.newInputStream(file)) {
            if (Files.size(file) > 16_384) return Optional.empty();
            Properties values = new Properties();
            values.load(input);
            return Optional.ofNullable(values.getProperty("selected")).filter(value -> !value.isBlank());
        } catch (IOException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public void save(String identifier) throws IOException {
        if (file == null) return;
        Files.createDirectories(file.getParent());
        Properties values = new Properties();
        values.setProperty("selected", identifier);
        Path temporary = Files.createTempFile(file.getParent(), "run-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                values.store(output, "Vintelli run selection");
            }
            replaceWithRetry(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Renames the temporary file over the selection. On Windows that rename can fail while another process has
     * either file open, for example a virus scanner or the indexer. Such a hold is usually brief, so the rename
     * is retried with a doubling pause, at most {@code REPLACE_ATTEMPTS} times in total. The temporary file is
     * kept until the last attempt, and the last failure reaches the caller.
     */
    private void replaceWithRetry(Path temporary) throws IOException {
        long pauseNanos = FIRST_REPLACE_PAUSE_NANOS;
        for (int attempt = 1; attempt < REPLACE_ATTEMPTS; attempt++) {
            try {
                replaceOnce(temporary);
                return;
            } catch (IOException transientFailure) {
                LockSupport.parkNanos(pauseNanos);
                pauseNanos *= 2;
            }
        }
        replaceOnce(temporary);
    }

    private void replaceOnce(Path temporary) throws IOException {
        try {
            replacer.replace(temporary, file, true);
        } catch (AtomicMoveNotSupportedException unsupported) {
            replacer.replace(temporary, file, false);
        }
    }

    private static String projectKey(Path project) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(project.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by Java", impossible);
        }
    }
}
