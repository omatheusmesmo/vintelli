package dev.omatheusmesmo.vintelli.local;

import java.nio.file.Path;
import java.util.UUID;

import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Keeps the Git configuration of the machine out of the tests: while the
 * annotated class runs, JGit reads no user, system, JGit or XDG configuration,
 * so a global {@code diff.noprefix}, a {@code core.excludesFile} or a
 * {@code core.hooksPath} in the home directory of the developer cannot change
 * what the tests observe. The previous {@link SystemReader} is restored after
 * the class.
 */
final class IsolatedGitConfiguration implements BeforeAllCallback, AfterAllCallback {

    private static final Path ABSENT_CONFIGURATION = Path.of(System.getProperty("java.io.tmpdir"))
            .resolve("vintelli-absent-git-config-" + UUID.randomUUID());

    private static SystemReader previous;

    @Override
    public void beforeAll(ExtensionContext context) {
        previous = SystemReader.getInstance();
        SystemReader.setInstance(new EmptyConfigurationReader(previous));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        SystemReader.setInstance(previous);
    }

    private static final class EmptyConfigurationReader extends SystemReader.Delegate {

        EmptyConfigurationReader(SystemReader delegate) {
            super(delegate);
        }

        @Override
        public FileBasedConfig openUserConfig(Config parent, FS fs) {
            return absent(parent, fs);
        }

        @Override
        public FileBasedConfig openSystemConfig(Config parent, FS fs) {
            return absent(parent, fs);
        }

        @Override
        public FileBasedConfig openJGitConfig(Config parent, FS fs) {
            return absent(parent, fs);
        }

        @Override
        public Path getXdgConfigDirectory(FS fs) {
            return ABSENT_CONFIGURATION;
        }

        private static FileBasedConfig absent(Config parent, FS fs) {
            return new FileBasedConfig(parent, ABSENT_CONFIGURATION.resolve("config").toFile(), fs);
        }
    }
}
