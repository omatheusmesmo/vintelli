package dev.omatheusmesmo.vintelli.core.capability;

import io.smallrye.config.ConfigMapping;

import java.util.List;
import java.util.Optional;

/**
 * Provider order settings of every capability, one group per capability: a
 * comma separated identifier list ranks the providers with the first healthy
 * one serving.
 */
@ConfigMapping(prefix = "vintelli.capabilities")
public interface CapabilitiesConfig {

    /**
     * Ranking of the run capability providers, for example
     * {@code vintelli.capabilities.run.provider-order=maven,idea}.
     */
    Order run();

    /**
     * Ranking of the terminal capability providers.
     */
    Order terminal();

    /**
     * Ranking of the read-file capability providers.
     */
    Order readFile();

    /**
     * Ranking of the diagnostics capability providers.
     */
    Order diagnostics();

    /**
     * Ranking of the call-hierarchy capability providers.
     */
    Order callHierarchy();

    /**
     * Ranking of the editor-bridge capability providers. Without
     * configuration the builtin editor serves, because unlisted providers
     * sort by identifier and {@code builtin} comes first.
     */
    Order editorBridge();

    /**
     * Ranking of the vcs capability providers. Without configuration the
     * local JGit provider serves, because unlisted providers sort by
     * identifier and {@code local} comes first.
     */
    Order vcs();

    /**
     * Order settings of one capability.
     */
    interface Order {

        /**
         * Comma separated provider identifiers: the first healthy one serves,
         * unlisted providers follow sorted by identifier.
         */
        Optional<List<String>> providerOrder();
    }
}
