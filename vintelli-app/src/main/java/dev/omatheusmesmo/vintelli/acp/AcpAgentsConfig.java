package dev.omatheusmesmo.vintelli.acp;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "vintelli.acp")
public interface AcpAgentsConfig {

    @WithDefault("false")
    boolean enabled();

    @WithDefault("opencode")
    String defaultAgent();

    Map<String, Agent> agents();

    interface Agent {
        String name();
        String command();
        Optional<List<String>> args();
    }
}
