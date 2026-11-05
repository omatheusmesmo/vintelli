package dev.omatheusmesmo.vintelli.core.deployment;

import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.CoreConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.test.QuarkusUnitTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the JGit types required by native use are registered for reflection. */
class JGitProcessorTest {

    private static final String REGISTERED_PROPERTY = "vintelli.core.test.jgit-reflective-classes";

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withEmptyApplication()
            .addBuildChainCustomizer(JGitProcessorTest::captureRegistrations);

    @Test
    void jgitEnumsAndMessagesAreRegisteredForReflection() {
        List<String> registered = List.of(System.getProperty(REGISTERED_PROPERTY, "").split(","));

        assertTrue(registered.contains(CoreConfig.AutoCRLF.class.getName()), registered.toString());
        assertTrue(registered.contains(JGitText.class.getName()), registered.toString());
    }

    private static void captureRegistrations(BuildChainBuilder chain) {
        chain.addBuildStep(new BuildStep() {
            @Override
            public void execute(BuildContext context) {
                System.setProperty(REGISTERED_PROPERTY, context.consumeMulti(ReflectiveClassBuildItem.class).stream()
                        .flatMap(item -> item.getClassNames().stream())
                        .collect(Collectors.joining(",")));
                context.produce(new FeatureBuildItem("vintelli-jgit-test-capture"));
            }
        }).consumes(ReflectiveClassBuildItem.class)
                .produces(FeatureBuildItem.class)
                .build();
    }
}
