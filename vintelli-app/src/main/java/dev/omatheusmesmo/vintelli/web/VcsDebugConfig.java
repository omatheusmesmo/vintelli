package dev.omatheusmesmo.vintelli.web;

import io.smallrye.config.ConfigMapping;

import java.util.Optional;

/**
 * Version control debug endpoint settings: the endpoint is off by default,
 * because it serves the Git state of the project to any loopback caller.
 */
@ConfigMapping(prefix = "vintelli.vcs.debug-endpoint")
public interface VcsDebugConfig {

    Optional<Boolean> enabled();
}
