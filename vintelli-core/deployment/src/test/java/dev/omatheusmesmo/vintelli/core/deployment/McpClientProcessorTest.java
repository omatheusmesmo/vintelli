package dev.omatheusmesmo.vintelli.core.deployment;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpClientProcessorTest {

    private static final String REGISTERED_PROPERTY = "vintelli.core.test.reflective-classes";

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withEmptyApplication()
            .addBuildChainCustomizer(McpClientProcessorTest::captureReflectiveClasses);

    @Inject
    McpJsonMapper mapper;

    @Inject
    JsonSchemaValidator validator;

    @Test
    void injectedMapperRoundTripsMcpSchemaTypesWithoutServiceLoader() throws Exception {
        String json = mapper.writeValueAsString(new Implementation("vintelli", "1.0"));

        Implementation read = mapper.readValue(json, Implementation.class);

        assertEquals("vintelli", read.name());
        assertEquals("1.0", read.version());
    }

    @Test
    void injectedValidatorChecksArgumentsAgainstASchema() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")),
                "required", List.of("path"));

        assertTrue(validator.validate(schema, Map.of("path", "/tmp")).valid());
        assertFalse(validator.validate(schema, Map.of()).valid());
    }

    @Test
    void mcpSchemaTypesAreRegisteredForReflection() {
        List<String> registered = List.of(System.getProperty(REGISTERED_PROPERTY, "").split(","));
        assertTrue(registered.contains(CallToolRequest.class.getName()), registered.toString());
        assertTrue(registered.contains(Implementation.class.getName()), registered.toString());
        assertTrue(registered.contains("io.modelcontextprotocol.spec.McpSchema$JSONRPCRequest"), registered.toString());
    }

    private static void captureReflectiveClasses(BuildChainBuilder chain) {
        chain.addBuildStep(new BuildStep() {
            @Override
            public void execute(BuildContext context) {
                System.setProperty(REGISTERED_PROPERTY, context.consumeMulti(ReflectiveClassBuildItem.class).stream()
                        .flatMap(item -> item.getClassNames().stream())
                        .collect(Collectors.joining(",")));
                context.produce(new FeatureBuildItem("vintelli-core-test-capture"));
            }
        }).consumes(ReflectiveClassBuildItem.class).produces(FeatureBuildItem.class).build();
    }
}
