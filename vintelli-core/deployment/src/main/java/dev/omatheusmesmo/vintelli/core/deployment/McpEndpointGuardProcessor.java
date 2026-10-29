package dev.omatheusmesmo.vintelli.core.deployment;

import dev.omatheusmesmo.vintelli.core.http.McpEndpointGuard;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;

/**
 * Registers the Host and Origin guard on the local endpoints as an
 * unremovable bean so it always filters {@code /mcp} and {@code /tui/render},
 * whatever else the application configures.
 */
final class McpEndpointGuardProcessor {

    @BuildStep
    AdditionalBeanBuildItem guard() {
        return AdditionalBeanBuildItem.unremovableOf(McpEndpointGuard.class);
    }
}
