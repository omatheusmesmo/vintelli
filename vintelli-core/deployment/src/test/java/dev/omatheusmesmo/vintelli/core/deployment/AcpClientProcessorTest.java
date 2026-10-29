package dev.omatheusmesmo.vintelli.core.deployment;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.AcpJsonMapperSupplier;
import com.agentclientprotocol.sdk.json.jackson3.Jackson3AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the ACP client glue is wired without {@code ServiceLoader}: injecting
 * {@link AcpJsonMapper} fails the deployment unless {@code AcpClientProducers}
 * exists, and the reflective {@code AcpSchema} registrations fail the test
 * unless {@code AcpClientProcessor} produces them.
 */
class AcpClientProcessorTest {

    private static final String REGISTERED_PROPERTY = "vintelli.core.test.acp-reflective-classes";
    private static final String FULL_ACCESS_PROPERTY = "vintelli.core.test.acp-reflective-full-access";
    private static final String SELECTOR_PROPERTY = "acp.json.mapper.supplier";

    static {
        System.setProperty(SELECTOR_PROPERTY, PoisonedSupplier.class.getName());
    }

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar
                    .addClass(PoisonedSupplier.class)
                    .addAsResource(new StringAsset(PoisonedSupplier.class.getName()),
                            "META-INF/services/" + AcpJsonMapperSupplier.class.getName()))
            .addBuildChainCustomizer(AcpClientProcessorTest::captureReflectiveClasses);

    @Inject
    AcpJsonMapper acpJsonMapper;

    @Test
    void injectedMapperIsTheExplicitJackson3Wiring() throws Exception {
        assertTrue(acpJsonMapper instanceof Jackson3AcpJsonMapper);

        String json = acpJsonMapper.writeValueAsString(new TextContent("vintelli"));
        TextContent read = acpJsonMapper.readValue(json, TextContent.class);

        assertEquals("text", read.type());
        assertEquals("vintelli", read.text());
    }

    @Test
    void injectedMapperRoundTripsAPolymorphicSessionUpdate() throws Exception {
        String json = acpJsonMapper.writeValueAsString(new AgentMessageChunk(new TextContent("chunk")));
        AgentMessageChunk read = acpJsonMapper.readValue(json, AgentMessageChunk.class);

        assertTrue(read.content() instanceof TextContent, String.valueOf(read.content()));
        assertEquals("chunk", ((TextContent) read.content()).text());
    }

    @AfterAll
    static void clearSelector() {
        System.clearProperty(SELECTOR_PROPERTY);
    }

    @Test
    void injectedMapperIsNeverResolvedThroughServiceLoader() {
        assertTrue(acpJsonMapper instanceof Jackson3AcpJsonMapper, acpJsonMapper.getClass().getName());
    }

    @Test
    void everyAcpSchemaTypeIsRegisteredForReflection() {
        Set<String> registered = new HashSet<>(List.of(System.getProperty(REGISTERED_PROPERTY, "").split(",")));
        List<String> missing = new ArrayList<>();
        collectTypes(AcpSchema.class, missing, registered);
        assertTrue(missing.isEmpty(), "not registered for reflection: " + missing);
    }

    @Test
    void acpSchemaTypesAreRegisteredWithConstructorsMethodsAndFields() {
        assertEquals("true", System.getProperty(FULL_ACCESS_PROPERTY));
    }

    @Test
    void acpSchemaTypesAreRegisteredForReflection() {
        List<String> registered = List.of(System.getProperty(REGISTERED_PROPERTY, "").split(","));
        assertTrue(registered.contains(TextContent.class.getName()), registered.toString());
        assertTrue(registered.contains(AgentMessageChunk.class.getName()), registered.toString());
        assertTrue(registered.contains(NewSessionResponse.class.getName()), registered.toString());
        assertTrue(registered.contains("com.agentclientprotocol.sdk.spec.AcpSchema$JSONRPCMessage"), registered.toString());
    }

    private static void collectTypes(Class<?> type, List<String> missing, Set<String> registered) {
        if (!registered.contains(type.getName())) {
            missing.add(type.getName());
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            collectTypes(nested, missing, registered);
        }
    }

    private static void captureReflectiveClasses(BuildChainBuilder chain) {
        chain.addBuildStep(new BuildStep() {
            @Override
            public void execute(BuildContext context) {
                List<ReflectiveClassBuildItem> items = context.consumeMulti(ReflectiveClassBuildItem.class);
                System.setProperty(REGISTERED_PROPERTY, items.stream()
                        .flatMap(item -> item.getClassNames().stream())
                        .collect(Collectors.joining(",")));
                Set<String> unrestricted = new HashSet<>();
                for (ReflectiveClassBuildItem item : items) {
                    if (item.isConstructors() && item.isMethods() && item.isFields()) {
                        unrestricted.addAll(item.getClassNames());
                    }
                }
                System.setProperty(FULL_ACCESS_PROPERTY, String.valueOf(
                        unrestricted.contains(TextContent.class.getName())
                                && unrestricted.contains(AgentMessageChunk.class.getName())
                                && unrestricted.contains(NewSessionResponse.class.getName())));
                context.produce(new FeatureBuildItem("vintelli-acp-test"));
            }
        }).consumes(ReflectiveClassBuildItem.class).produces(FeatureBuildItem.class).build();
    }

    /**
     * Highest-precedence {@code ServiceLoader} candidate that fails when asked
     * for a mapper: the injected bean must not depend on the lookup it stands for.
     */
    public static final class PoisonedSupplier implements AcpJsonMapperSupplier {

        @Override
        public AcpJsonMapper get() {
            throw new IllegalStateException("the ACP JSON mapper was resolved through ServiceLoader");
        }

        @Override
        public int priority() {
            return Integer.MIN_VALUE;
        }
    }
}
