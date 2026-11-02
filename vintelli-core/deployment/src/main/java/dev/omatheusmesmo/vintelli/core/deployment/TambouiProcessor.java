package dev.omatheusmesmo.vintelli.core.deployment;

import java.util.List;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;

/**
 * Build steps that keep the TamboUI service providers reachable in the native
 * image. TamboUI discovers its terminal backend and its capability probes
 * through {@code ServiceLoader}, which Quarkus disables in the native build,
 * and its jars ship no native-image metadata. Each service interface is
 * declared by name and every provider listed in a {@code META-INF/services}
 * file on the classpath is kept, together with the service file itself, so a
 * different backend added to the classpath needs no deployment change.
 */
final class TambouiProcessor {

    static final String BACKEND_PROVIDER = "dev.tamboui.terminal.BackendProvider";
    static final String CAPABILITY_PROVIDER = "dev.tamboui.capability.CapabilityProvider";

    @BuildStep
    List<ServiceProviderBuildItem> tambouiServiceProviders() {
        return List.of(
                ServiceProviderBuildItem.allProvidersFromClassPath(BACKEND_PROVIDER),
                ServiceProviderBuildItem.allProvidersFromClassPath(CAPABILITY_PROVIDER));
    }
}
