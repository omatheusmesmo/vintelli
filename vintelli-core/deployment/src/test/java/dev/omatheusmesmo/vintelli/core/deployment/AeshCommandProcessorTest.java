package dev.omatheusmesmo.vintelli.core.deployment;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.test.QuarkusUnitTest;
import org.aesh.command.metadata.CommandMetadataProvider;
import org.aesh.command.metadata.MetadataRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the AEsh command layer reflection needs are derived from the build
 * indexes: an implementor of {@link MetadataRegistry} in the application is
 * registered for reflective construction, and the discovery resources in the
 * native image carry the AEsh registry files.
 */
class AeshCommandProcessorTest {

    private static final String REGISTERED_PROPERTY = "vintelli.core.test.aesh-reflective-classes";
    private static final String RESOURCES_PROPERTY = "vintelli.core.test.aesh-resources";

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClass(GeneratedRegistryProbe.class))
            .addBuildChainCustomizer(AeshCommandProcessorTest::captureNativeEntries);

    @Test
    void metadataRegistriesInTheApplicationAreRegisteredForReflection() {
        List<String> registered = registeredClasses();
        assertTrue(registered.contains(GeneratedRegistryProbe.class.getName()), registered.toString());
        assertTrue(registered.contains("org.aesh.command.internal.InternalCommandMetadataRegistry"), registered.toString());
    }

    @Test
    void aeshDiscoveryResourcesAreKeptInTheNativeImage() {
        String resources = System.getProperty(RESOURCES_PROPERTY, "");
        assertTrue(resources.contains("META-INF/aesh/registry"), resources);
        assertTrue(resources.contains("META-INF/services/org.aesh.command.metadata.MetadataRegistry"), resources);
    }

    private static List<String> registeredClasses() {
        return List.of(System.getProperty(REGISTERED_PROPERTY, "").split(","));
    }

    private static void captureNativeEntries(BuildChainBuilder chain) {
        chain.addBuildStep(new BuildStep() {
            @Override
            public void execute(BuildContext context) {
                Set<String> names = new HashSet<>();
                for (ReflectiveClassBuildItem item : context.consumeMulti(ReflectiveClassBuildItem.class)) {
                    names.addAll(item.getClassNames());
                }
                System.setProperty(REGISTERED_PROPERTY, String.join(",", names));
                System.setProperty(RESOURCES_PROPERTY, context.consumeMulti(NativeImageResourceBuildItem.class)
                        .stream()
                        .flatMap(item -> item.getResources().stream())
                        .collect(Collectors.joining(",")));
                context.produce(new FeatureBuildItem("vintelli-aesh-test"));
            }
        }).consumes(ReflectiveClassBuildItem.class).consumes(NativeImageResourceBuildItem.class)
                .produces(FeatureBuildItem.class).build();
    }

    /**
     * Stand in for the processor generated registry: the build step must pick
     * every application implementor of the AEsh metadata registry up, whatever
     * package generated it.
     */
    public static final class GeneratedRegistryProbe implements MetadataRegistry {

        @Override
        public CommandMetadataProvider<?> get(String commandClassName) {
            return null;
        }
    }
}
