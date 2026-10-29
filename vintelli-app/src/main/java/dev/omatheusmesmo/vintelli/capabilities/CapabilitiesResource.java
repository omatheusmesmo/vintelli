package dev.omatheusmesmo.vintelli.capabilities;

import java.util.List;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Read only headless surface of the capability selection: one status row per
 * capability, with the active provider, its provenance, the higher ranked
 * providers skipped and the unavailable reasons. Pairs with
 * {@link dev.omatheusmesmo.vintelli.tui.ShellInspectResource} as an inspect
 * endpoint that integration tests, including the native runner, read without
 * ever attaching a terminal.
 */
@Path("/capabilities")
@Singleton
public final class CapabilitiesResource {

    private final CapabilityRegistry registry;

    @Inject
    CapabilitiesResource(CapabilityRegistry registry) {
        this.registry = registry;
    }

    /**
     * @return the current status row of every capability
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<CapabilityStatus> snapshot() {
        return registry.snapshot();
    }
}
