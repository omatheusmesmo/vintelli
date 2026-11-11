package dev.omatheusmesmo.vintelli.tui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;

import dev.omatheusmesmo.vintelli.tui.keymap.ConfigDirectory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Saves only the selected configuration id, outside the checkout and scoped to its canonical root. */
@Singleton
public final class RunSelectionStore {

    private final Path file;

    @Inject
    public RunSelectionStore(@ConfigProperty(name = "vintelli.local.project-root", defaultValue = ".") String project,
            @ConfigProperty(name = "vintelli.config.dir") Optional<String> config) {
        this(ConfigDirectory.configRoot(config.orElseGet(ConfigDirectory::sharedConfigDir),
                System.getenv("XDG_CONFIG_HOME"), System.getenv("APPDATA"), System.getProperty("user.home")), Path.of(project));
    }

    public RunSelectionStore(Path configRoot, Path project) {
        Path canonical;
        try {
            canonical = project.toRealPath();
        } catch (IOException unavailable) {
            canonical = project.toAbsolutePath().normalize();
        }
        file = configRoot == null ? null : configRoot.resolve("vintelli/run-configurations").resolve(projectKey(canonical) + ".properties");
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
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
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
