package dev.omatheusmesmo.vintelli.lsp;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Language server profiles. Every profile declares the command of one language
 * server process; the active profile is picked by {@code vintelli.lsp.profile},
 * or the single configured profile when the key is absent. Adding a profile is
 * configuration only: a second profile such as {@code clangd} reads the same
 * keys as the first one and serves diagnostics and call hierarchies through the
 * {@code vintelli-api} ports.
 */
@ConfigMapping(prefix = "vintelli.lsp")
public interface LspProfileConfig {

    /**
     * Name of the profile that serves. Required as soon as more than one
     * profile is configured.
     */
    Optional<String> profile();

    /**
     * The configured profiles, keyed by profile name.
     */
    Map<String, Profile> profiles();

    /**
     * Whether the headless {@code /lsp} endpoints answer. They launch the
     * configured language server for their session, so they stay off unless a
     * test or a debugging session turns them on.
     */
    @WithName("debug-endpoint.enabled")
    @WithDefault("false")
    boolean debugEndpointEnabled();

    /**
     * One language server: command and arguments, the workspace root passed as
     * {@code rootUri} and {@code initializationOptions}, raw JSON, handed over
     * untouched in the initialize request.
     */
    interface Profile {

        /**
         * Executable of the language server. A bare name is resolved through
         * {@code PATH}, anything with a path separator is resolved directly.
         */
        Optional<String> command();

        /**
         * Arguments of the language server process, for example a jdt.ls data
         * directory or a bundle extension path.
         */
        Optional<List<String>> args();

        /**
         * Workspace root handed to the initialize request as {@code rootUri}
         * and used as the working directory of the server process.
         */
        Optional<String> workspaceRoot();

        /**
         * Raw JSON object handed to the server as {@code initializationOptions},
         * for example {@code {"bundles":["..."]}} for jdt.ls extensions.
         */
        Optional<String> initializationOptions();
    }
}
