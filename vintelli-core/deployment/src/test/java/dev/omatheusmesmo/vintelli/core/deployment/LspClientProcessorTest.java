package dev.omatheusmesmo.vintelli.core.deployment;

import java.util.List;
import java.util.stream.Collectors;

import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageProxyDefinitionBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.test.QuarkusUnitTest;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The native registration of the LSP4J client is derived from the Jandex index
 * at build time: the assertions run over the captured build items of a small
 * application, so an LSP4J upgrade keeps passing while the build step keeps
 * rediscovering the types and the four service proxies.
 */
class LspClientProcessorTest {

    private static final String REGISTERED_PROPERTY = "vintelli.core.test.reflective-classes";
    private static final String PROXIES_PROPERTY = "vintelli.core.test.proxy-definitions";

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .withEmptyApplication()
            .addBuildChainCustomizer(LspClientProcessorTest::captureRegistrations);

    @Test
    void lsp4jTypesAreRegisteredForReflection() {
        List<String> registered = List.of(System.getProperty(REGISTERED_PROPERTY, "").split(","));

        assertTrue(registered.contains("org.eclipse.lsp4j.Hover"), registered.toString());
        assertTrue(registered.contains("org.eclipse.lsp4j.jsonrpc.messages.Either"), registered.toString());
        assertTrue(registered.contains(LanguageServer.class.getName()), registered.toString());
        assertTrue(registered.contains("org.eclipse.lsp4j.jsonrpc.RemoteEndpoint"), registered.toString());
        assertTrue(registered.contains("dev.omatheusmesmo.vintelli.lsp.VintelliLanguageClient"),
                registered.toString());
    }

    @Test
    void theFourServiceProxiesAreRegistered() {
        List<String> definitions = List.of(System.getProperty(PROXIES_PROPERTY, "").split(";"));
        assertEquals(4, definitions.stream().filter(line -> !line.isBlank()).count(), definitions.toString());

        assertTrue(definitions.contains("org.eclipse.lsp4j.services.LanguageServer,"
                + "org.eclipse.lsp4j.jsonrpc.Endpoint"), definitions.toString());
        assertTrue(definitions.contains("org.eclipse.lsp4j.services.TextDocumentService,"
                + "org.eclipse.lsp4j.jsonrpc.Endpoint"), definitions.toString());
        assertTrue(definitions.contains("org.eclipse.lsp4j.services.WorkspaceService,"
                + "org.eclipse.lsp4j.jsonrpc.Endpoint"), definitions.toString());
        assertTrue(definitions.contains("org.eclipse.lsp4j.services.NotebookDocumentService,"
                + "org.eclipse.lsp4j.jsonrpc.Endpoint"), definitions.toString());
    }

    private static void captureRegistrations(BuildChainBuilder chain) {
        chain.addBuildStep(new BuildStep() {
            @Override
            public void execute(BuildContext context) {
                System.setProperty(REGISTERED_PROPERTY, context.consumeMulti(ReflectiveClassBuildItem.class).stream()
                        .flatMap(item -> item.getClassNames().stream())
                        .collect(Collectors.joining(",")));
                System.setProperty(PROXIES_PROPERTY, context.consumeMulti(NativeImageProxyDefinitionBuildItem.class)
                        .stream()
                        .map(item -> String.join(",", item.getClasses()))
                        .collect(Collectors.joining(";")));
                context.produce(new FeatureBuildItem("vintelli-lsp-test-capture"));
            }
        }).consumes(ReflectiveClassBuildItem.class).consumes(NativeImageProxyDefinitionBuildItem.class)
                .produces(FeatureBuildItem.class).build();
    }
}
