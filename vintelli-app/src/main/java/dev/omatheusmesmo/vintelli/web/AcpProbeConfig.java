package dev.omatheusmesmo.vintelli.web;

import io.smallrye.config.ConfigMapping;

import java.util.Optional;

/**
 * ACP SDK probe settings: the endpoint is off by default, and the stdio agent
 * it spawns is only known to whoever wires the probe (the integration test
 * resources set both values).
 */
@ConfigMapping(prefix = "vintelli.acp.probe")
public interface AcpProbeConfig {

    Optional<Boolean> enabled();

    Optional<String> agentCommand();

    Optional<String> agentArgs();
}
