package dev.omatheusmesmo.vintelli.core.deployment;

import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraph;
import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraph.LibraryDependency;
import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraph.Module;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourcePatternsBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.test.QuarkusUnitTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the inspection DTOs needed by native JSON serialization are registered. */
class ShellThemeProcessorTest {

    private static final String REGISTERED_PROPERTY = "vintelli.core.test.shell-inspection-reflective-classes";
    private static final String RESOURCE_PATTERNS_PROPERTY = "vintelli.core.test.shell-inspection-resource-patterns";

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withEmptyApplication()
            .addBuildChainCustomizer(ShellThemeProcessorTest::captureRegistrations);

    @Test
    void dependencyGraphPayloadTypesAreRegisteredForReflection() {
        List<String> registered = List.of(System.getProperty(REGISTERED_PROPERTY, "").split(","));

        assertTrue(registered.contains(ProjectDependencyGraph.class.getName()),
                registered.toString());
        assertTrue(registered.contains(Module.class.getName()),
                registered.toString());
        assertTrue(registered.contains(LibraryDependency.class.getName()),
                registered.toString());
    }

    @Test
    void mavenModelSuperPomIsIncludedAsNativeImageResource() {
        List<String> patterns = List.of(System.getProperty(RESOURCE_PATTERNS_PROPERTY, "").split(","));

        assertTrue(patterns.stream()
                .anyMatch(pattern -> Pattern.compile(pattern).matcher("org/apache/maven/model/pom-4.0.0.xml").matches()),
                patterns.toString());
    }

    private static void captureRegistrations(BuildChainBuilder chain) {
        chain.addBuildStep(new BuildStep() {
            @Override
            public void execute(BuildContext context) {
                System.setProperty(REGISTERED_PROPERTY, context.consumeMulti(ReflectiveClassBuildItem.class).stream()
                        .flatMap(item -> item.getClassNames().stream())
                        .collect(Collectors.joining(",")));
                System.setProperty(RESOURCE_PATTERNS_PROPERTY,
                        context.consumeMulti(NativeImageResourcePatternsBuildItem.class).stream()
                                .flatMap(item -> item.getIncludePatterns().stream())
                                .collect(Collectors.joining(",")));
                context.produce(new FeatureBuildItem("vintelli-shell-inspection-test-capture"));
            }
        }).consumes(ReflectiveClassBuildItem.class)
                .consumes(NativeImageResourcePatternsBuildItem.class)
                .produces(FeatureBuildItem.class)
                .build();
    }
}
