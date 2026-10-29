package dev.omatheusmesmo.vintelli.capabilities;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Ranks the local provider first for the read-file capability through the
 * provider order configuration key, so the wiring test proves the local bean
 * serves by the same ranking the app uses instead of competing with the other
 * discovered read-file beans for the default alphabetical order.
 */
public final class LocalProvidersRankedProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("vintelli.capabilities.read-file.provider-order", "local");
    }
}
