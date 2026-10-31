package dev.omatheusmesmo.vintelli.capabilities;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Ranks the generic stub editor over the builtin stub through the provider
 * order configuration key, so the configured adapter selection runs end to end
 * through real bean discovery.
 */
public final class RankedEditorBridgeProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("vintelli.capabilities.editor-bridge.provider-order", "generic, builtin-stub");
    }
}
