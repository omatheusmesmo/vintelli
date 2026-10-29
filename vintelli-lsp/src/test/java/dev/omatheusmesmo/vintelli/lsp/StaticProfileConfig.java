package dev.omatheusmesmo.vintelli.lsp;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hand written implementations of the {@link LspProfileConfig} mapping, so the
 * tests build profiles without SmallRye: the mapped interface has no generated
 * implementation in plain JUnit.
 */
final class StaticProfileConfig {

    private StaticProfileConfig() {
    }

    record StaticProfile(List<String> launch, Optional<String> workspaceRoot,
            Optional<String> initializationOptions) implements LspProfileConfig.Profile {

        @Override
        public Optional<String> command() {
            if (launch == null || launch.isEmpty() || launch.get(0).isBlank()) {
                return Optional.empty();
            }
            return Optional.of(launch.get(0));
        }

        @Override
        public Optional<List<String>> args() {
            if (launch == null || launch.size() < 2) {
                return Optional.empty();
            }
            return Optional.of(launch.subList(1, launch.size()));
        }
    }

    record StaticConfig(Optional<String> profile,
            Map<String, LspProfileConfig.Profile> profiles) implements LspProfileConfig {

        @Override
        public boolean debugEndpointEnabled() {
            return false;
        }
    }
}
