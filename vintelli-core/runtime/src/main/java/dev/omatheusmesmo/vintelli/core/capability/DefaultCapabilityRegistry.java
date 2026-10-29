package dev.omatheusmesmo.vintelli.core.capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.omatheusmesmo.vintelli.api.capability.CallHierarchyCapability;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProviderDescriptor;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.api.capability.SkippedProvider;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Implementation of the read only capability contract. Holds the providers
 * registered for each capability, hands the ports out and feeds the status
 * read model. All state is final and immutable after construction: selection
 * reads provider health live, and thread visibility of a health flip is the
 * provider's job. Providers must be {@code @Singleton}: a dependent scoped
 * provider instantiated during discovery would never be destroyed.
 */
@Singleton
public final class DefaultCapabilityRegistry implements CapabilityRegistry {

    private final CapabilityOrders orders;
    private final ProviderGroup<RunCapability> runGroup;
    private final ProviderGroup<TerminalCapability> terminalGroup;
    private final ProviderGroup<ReadFileCapability> readGroup;
    private final ProviderGroup<DiagnosticsCapability> diagnosticsGroup;
    private final ProviderGroup<CallHierarchyCapability> callHierarchyGroup;

    @Inject
    DefaultCapabilityRegistry(Instance<RunCapability> runProviders, Instance<TerminalCapability> terminalProviders,
            Instance<ReadFileCapability> readFileProviders, Instance<DiagnosticsCapability> diagnosticsProviders,
            Instance<CallHierarchyCapability> callHierarchyProviders, CapabilityOrders orders) {
        this(collect(runProviders), collect(terminalProviders), collect(readFileProviders),
                collect(diagnosticsProviders), collect(callHierarchyProviders), orders);
    }

    DefaultCapabilityRegistry(List<RunCapability> runProviders, List<TerminalCapability> terminalProviders,
            List<ReadFileCapability> readFileProviders, List<DiagnosticsCapability> diagnosticsProviders,
            List<CallHierarchyCapability> callHierarchyProviders, CapabilityOrders orders) {
        this.orders = orders;
        this.runGroup = ProviderGroup.of(CapabilityType.RUN, runProviders);
        this.terminalGroup = ProviderGroup.of(CapabilityType.TERMINAL, terminalProviders);
        this.readGroup = ProviderGroup.of(CapabilityType.READ_FILE, readFileProviders);
        this.diagnosticsGroup = ProviderGroup.of(CapabilityType.DIAGNOSTICS, diagnosticsProviders);
        this.callHierarchyGroup = ProviderGroup.of(CapabilityType.CALL_HIERARCHY, callHierarchyProviders);
    }

    @Override
    public CapabilitySelection<RunCapability> run() {
        return select(runGroup);
    }

    @Override
    public CapabilitySelection<TerminalCapability> terminal() {
        return select(terminalGroup);
    }

    @Override
    public CapabilitySelection<ReadFileCapability> readFile() {
        return select(readGroup);
    }

    @Override
    public CapabilitySelection<DiagnosticsCapability> diagnostics() {
        return select(diagnosticsGroup);
    }

    @Override
    public CapabilitySelection<CallHierarchyCapability> callHierarchy() {
        return select(callHierarchyGroup);
    }

    @Override
    public List<CapabilityStatus> snapshot() {
        List<CapabilityStatus> rows = new ArrayList<>();
        rows.add(status(runGroup));
        rows.add(status(terminalGroup));
        rows.add(status(readGroup));
        rows.add(status(diagnosticsGroup));
        rows.add(status(callHierarchyGroup));
        return List.copyOf(rows);
    }

    private CapabilityStatus status(ProviderGroup<?> group) {
        ProviderChoice choice = CapabilitySelector.choose(group.capability(), group.snapshots(),
                orders.orderFor(group.capability()));
        if (!choice.isAvailable()) {
            return CapabilityStatus.unavailable(group.capability(), choice.unavailableReason());
        }
        List<SkippedProvider> skipped = new ArrayList<>(choice.skipped().size());
        for (ProviderSnapshot passed : choice.skipped()) {
            skipped.add(new SkippedProvider(passed.identifier(), passed.health().reason()));
        }
        return CapabilityStatus.available(group.capability(), choice.selected().identifier(),
                choice.selected().provenance(), skipped);
    }

    private <T extends ProviderDescriptor> CapabilitySelection<T> select(ProviderGroup<T> group) {
        ProviderChoice choice = CapabilitySelector.choose(group.capability(), group.snapshots(),
                orders.orderFor(group.capability()));
        if (!choice.isAvailable()) {
            return CapabilitySelection.unavailable(group.capability(), choice.unavailableReason());
        }
        return CapabilitySelection.selected(group.capability(), group.provider(choice.selected().identifier()));
    }

    private static <T> List<T> collect(Instance<T> providers) {
        List<T> collected = new ArrayList<>();
        for (T provider : providers) {
            collected.add(provider);
        }
        return List.copyOf(collected);
    }

    private static final class ProviderGroup<T extends ProviderDescriptor> {

        private final CapabilityType capability;
        private final List<T> ports;
        private final List<String> identifiers;
        private final Map<String, T> byIdentifier;

        private static <T extends ProviderDescriptor> ProviderGroup<T> of(CapabilityType capability, List<T> ports) {
            Map<String, T> byIdentifier = new LinkedHashMap<>();
            List<String> identifiers = new ArrayList<>(ports.size());
            for (T port : ports) {
                String identifier = reportedIdentifierOf(port, capability);
                T previous = byIdentifier.putIfAbsent(identifier, port);
                if (previous != null) {
                    throw new IllegalStateException("Two providers of '" + capability.label()
                            + "' share the identifier '" + identifier + "'");
                }
                identifiers.add(identifier);
            }
            return new ProviderGroup<>(capability, List.copyOf(ports), List.copyOf(identifiers), Map.copyOf(byIdentifier));
        }

        private static <T extends ProviderDescriptor> String reportedIdentifierOf(T port, CapabilityType capability) {
            String identifier;
            try {
                identifier = port.identifier();
            } catch (RuntimeException failure) {
                throw new IllegalStateException("The provider " + port.getClass().getName() + " of '"
                        + capability.label() + "' failed while reporting its identifier: " + failure,
                        failure);
            }
            if (identifier == null || identifier.isBlank()) {
                throw new IllegalStateException("The provider " + port.getClass().getName() + " of '"
                        + capability.label() + "' must report a non blank identifier");
            }
            return identifier;
        }

        private ProviderGroup(CapabilityType capability, List<T> ports, List<String> identifiers,
                Map<String, T> byIdentifier) {
            this.capability = capability;
            this.ports = ports;
            this.identifiers = identifiers;
            this.byIdentifier = byIdentifier;
        }

        CapabilityType capability() {
            return capability;
        }

        List<ProviderSnapshot> snapshots() {
            List<ProviderSnapshot> snapshots = new ArrayList<>(ports.size());
            for (int index = 0; index < ports.size(); index++) {
                snapshots.add(ProviderSnapshot.of(identifiers.get(index), ports.get(index)));
            }
            return snapshots;
        }

        T provider(String identifier) {
            return byIdentifier.get(identifier);
        }
    }
}
