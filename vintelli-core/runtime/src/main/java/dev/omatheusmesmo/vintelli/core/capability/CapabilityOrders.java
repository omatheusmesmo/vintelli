package dev.omatheusmesmo.vintelli.core.capability;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Parsed provider orders, read once at startup from the capability
 * configuration. A property under {@code vintelli.capabilities} that names no
 * capability fails the configuration validation: a typo such as
 * {@code vintelli.capabilities.diagnostic.provider-order} must never degrade
 * into silently ignored configuration.
 */
@Singleton
public final class CapabilityOrders {

    private final Map<CapabilityType, List<String>> orders;

    @Inject
    CapabilityOrders(CapabilitiesConfig config) {
        this(fromConfig(config));
    }

    CapabilityOrders(Map<CapabilityType, List<String>> orders) {
        this.orders = Map.copyOf(orders);
    }

    private static Map<CapabilityType, List<String>> fromConfig(CapabilitiesConfig config) {
        Map<CapabilityType, List<String>> parsed = new EnumMap<>(CapabilityType.class);
        add(parsed, CapabilityType.RUN, config.run());
        add(parsed, CapabilityType.TERMINAL, config.terminal());
        add(parsed, CapabilityType.READ_FILE, config.readFile());
        add(parsed, CapabilityType.DIAGNOSTICS, config.diagnostics());
        add(parsed, CapabilityType.CALL_HIERARCHY, config.callHierarchy());
        return parsed;
    }

    private static void add(Map<CapabilityType, List<String>> parsed, CapabilityType capability, CapabilitiesConfig.Order order) {
        parsed.put(capability, order.providerOrder()
                .orElseGet(List::of)
                .stream()
                .map(String::trim)
                .filter(identifier -> !identifier.isEmpty())
                .toList());
    }

    List<String> orderFor(CapabilityType capability) {
        return orders.getOrDefault(capability, List.of());
    }
}
