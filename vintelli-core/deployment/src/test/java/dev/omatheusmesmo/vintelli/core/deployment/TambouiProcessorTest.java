package dev.omatheusmesmo.vintelli.core.deployment;

import java.util.ArrayList;
import java.util.List;

import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the TamboUI service providers are handed to the native image build:
 * the backend and capability service interfaces each produce a provider
 * registration for every implementation found on the classpath, and Quarkus
 * turns a registration into the kept service file plus the reflective
 * constructor access of the provider.
 */
class TambouiProcessorTest {

    private static final String REGISTRATIONS_PROPERTY = "vintelli.core.test.tamboui-service-providers";

    private static final String SERVICES = "META-INF/services/";
    private static final String JLINE_BACKEND = "dev.tamboui.backend.jline3.JLineBackendProvider";
    private static final String CORE_CAPABILITY = "dev.tamboui.capability.core.CoreCapabilityProvider";

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withEmptyApplication()
            .addBuildChainCustomizer(TambouiProcessorTest::captureServiceProviders);

    @Test
    void theJLineBackendProviderIsRegisteredAsABackendProvider() {
        assertRegistered(SERVICES + TambouiProcessor.BACKEND_PROVIDER, JLINE_BACKEND);
    }

    @Test
    void theCoreCapabilityProviderIsRegisteredAsACapabilityProvider() {
        assertRegistered(SERVICES + TambouiProcessor.CAPABILITY_PROVIDER, CORE_CAPABILITY);
    }

    private static void assertRegistered(String serviceFile, String provider) {
        String registrations = System.getProperty(REGISTRATIONS_PROPERTY, "");
        assertTrue(List.of(registrations.split(",")).contains(serviceFile + "=" + provider), registrations);
    }

    private static void captureServiceProviders(BuildChainBuilder chain) {
        chain.addBuildStep(new BuildStep() {
            @Override
            public void execute(BuildContext context) {
                List<String> registrations = new ArrayList<>();
                for (ServiceProviderBuildItem item : context.consumeMulti(ServiceProviderBuildItem.class)) {
                    for (String provider : item.providers()) {
                        registrations.add(item.serviceDescriptorFile() + "=" + provider);
                    }
                }
                System.setProperty(REGISTRATIONS_PROPERTY, String.join(",", registrations));
                context.produce(new FeatureBuildItem("vintelli-tamboui-test"));
            }
        }).consumes(ServiceProviderBuildItem.class).produces(FeatureBuildItem.class).build();
    }
}
