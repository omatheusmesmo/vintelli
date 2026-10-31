package dev.omatheusmesmo.vintelli.tui.layout;

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
import java.util.Properties;

/** Project-scoped layout persistence, kept outside the checkout and replaced atomically. */
public final class ShellLayoutStore {
    private final Path file;

    public ShellLayoutStore(Path configRoot, Path projectRoot) {
        String identity;
        try {
            identity = projectRoot.toRealPath().toString();
        } catch (IOException unavailable) {
            identity = projectRoot.toAbsolutePath().normalize().toString();
        }
        file = configRoot == null ? null : configRoot.resolve("vintelli").resolve("layouts")
                .resolve(projectKey(identity) + ".properties");
    }

    public ShellLayout load() {
        if (file == null || !Files.isRegularFile(file)) {
            return new ShellLayout();
        }
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            values.load(input);
            return new ShellLayout(Double.parseDouble(values.getProperty("project.ratio")),
                    Double.parseDouble(values.getProperty("bottom.ratio")));
        } catch (IOException | IllegalArgumentException | NullPointerException invalid) {
            return new ShellLayout();
        }
    }

    /** A disabled store (null config directory) never writes to the process working directory. */
    public void save(ShellLayout layout) throws IOException {
        if (file == null) {
            return;
        }
        Files.createDirectories(file.getParent());
        Properties values = new Properties();
        values.setProperty("project.ratio", Double.toString(layout.projectRatio()));
        values.setProperty("bottom.ratio", Double.toString(layout.bottomRatio()));
        Path temporary = Files.createTempFile(file.getParent(), "layout-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                values.store(output, "Vintelli shell layout");
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

    public Path file() {
        return file;
    }

    private static String projectKey(String identity) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by Java", impossible);
        }
    }
}
