package dev.omatheusmesmo.vintelli.capabilities;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Ranks the two stub read-file providers over the provider order configuration
 * key, so selection and fallback run through real bean discovery.
 */
public final class RankedReadFileProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("vintelli.capabilities.read-file.provider-order", "primary-reader, backup-reader");
    }
}
