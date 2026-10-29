package dev.omatheusmesmo.vintelli.core.capability;

import io.smallrye.config.ConfigValidationException;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capability configuration mapping reads one comma separated provider
 * order per capability, and a property under {@code vintelli.capabilities}
 * that names no capability fails the configuration validation.
 */
class CapabilitiesConfigTest {

    @Test
    void readsTheConfiguredProviderOrderPerCapability() {
        SmallRyeConfig config = configOf(Map.of(
                "run.provider-order", " local, idea ",
                "read-file.provider-order", "lsp"));

        CapabilityOrders orders = new CapabilityOrders(config.getConfigMapping(CapabilitiesConfig.class));

        assertEquals(List.of("local", "idea"), orders.orderFor(CapabilityType.RUN));
        assertEquals(List.of("lsp"), orders.orderFor(CapabilityType.READ_FILE));
        assertEquals(List.of(), orders.orderFor(CapabilityType.DIAGNOSTICS));
        assertEquals(List.of(), orders.orderFor(CapabilityType.TERMINAL));
    }

    @Test
    void failsFastOnAPropertyThatNamesNoCapability() {
        SmallRyeConfigBuilder builder = new SmallRyeConfigBuilder()
                .withMapping(CapabilitiesConfig.class)
                .withSources(new PropertiesConfigSource(
                        Map.of("vintelli.capabilities.diagnostic.provider-order", "jdtls"),
                        "test"));

        ConfigValidationException failure = assertThrows(ConfigValidationException.class, builder::build);

        assertTrue(failure.getMessage().contains("diagnostic"), failure.getMessage());
    }

    private static SmallRyeConfig configOf(Map<String, String> rawProperties) {
        Map<String, String> properties = new HashMap<>();
        for (Map.Entry<String, String> property : rawProperties.entrySet()) {
            properties.put("vintelli.capabilities." + property.getKey(), property.getValue());
        }
        return new SmallRyeConfigBuilder()
                .withMapping(CapabilitiesConfig.class)
                .withSources(new PropertiesConfigSource(properties, "test"))
                .build();
    }
}
