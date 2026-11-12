package dev.omatheusmesmo.vintelli.lsp;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;

/**
 * Java-specific bridge to the debug adapter started by jdt.ls. LSP4J protocol
 * types remain inside the LSP module.
 */
public interface JavaDebugAdapterPort {

    ProviderHealth debugAdapterHealth();

    CapabilityResult<Integer> startDebugAdapter();
}
