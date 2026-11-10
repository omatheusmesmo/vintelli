package dev.omatheusmesmo.vintelli.web;

import java.io.IOException;
import java.util.List;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import dev.omatheusmesmo.vintelli.tui.RunSelectionStore;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Inspects the saved run choice without starting processes or forwarding keyboard input. */
@Path("/tui/run-selection")
@Singleton
@Blocking
@Produces(MediaType.APPLICATION_JSON)
public final class RunSelectionResource {

    private final CapabilityRegistry capabilities;
    private final RunSelectionStore store;
    private final boolean enabled;

    @Inject
    public RunSelectionResource(CapabilityRegistry capabilities, RunSelectionStore store,
            @ConfigProperty(name = "vintelli.inspect.enabled", defaultValue = "false") boolean enabled) {
        this.capabilities = capabilities;
        this.store = store;
        this.enabled = enabled;
    }

    @GET
    public SelectionView get() {
        checkEnabled();
        List<String> configurations = configurations();
        String selected = store.load().filter(configurations::contains)
                .orElseGet(() -> configurations.isEmpty() ? "" : configurations.getFirst());
        return new SelectionView(configurations, selected);
    }

    @POST
    public SelectionView select(@QueryParam("id") String identifier) throws IOException {
        checkEnabled();
        if (identifier == null || identifier.isBlank() || !configurations().contains(identifier)) {
            throw new BadRequestException("Unknown run configuration");
        }
        store.save(identifier);
        return get();
    }

    private void checkEnabled() {
        if (!enabled) throw new NotFoundException();
    }

    private List<String> configurations() {
        if (capabilities.run() instanceof CapabilitySelection.Selected<RunCapability> selected
                && selected.provider().listConfigurations() instanceof CapabilityResult.Ok<List<RunCapability.RunConfiguration>> configurations) {
            return configurations.value().stream().map(RunCapability.RunConfiguration::identifier).toList();
        }
        return List.of();
    }

    public record SelectionView(List<String> configurations, String selected) { }
}
