package dev.omatheusmesmo.vintelli.core.deployment;

import java.util.ArrayList;
import java.util.List;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageProxyDefinitionBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;

/**
 * Native image registration for the LSP4J based client of {@code vintelli-lsp}
 * (decision recorded in issue 20): LSP4J drives every endpoint through dynamic
 * proxies and (de)serializes its protocol types with Gson, so the whole
 * {@code org.eclipse.lsp4j} tree plus the client callback classes must be
 * registered for reflection and the service interfaces as proxy definitions.
 *
 * <p>Everything is derived from the Jandex index, so an LSP4J upgrade or a
 * rename of the client callback needs no hand edited registration here: the
 * callback classes are the implementors of {@code LanguageClient} in the index.
 */
final class LspClientProcessor {

    private static final String LSP4J_PREFIX = "org.eclipse.lsp4j.";
    private static final DotName LANGUAGE_CLIENT = DotName.createSimple("org.eclipse.lsp4j.services.LanguageClient");
    private static final List<String> PROXY_ENDPOINTS = List.of(
            "org.eclipse.lsp4j.services.LanguageServer",
            "org.eclipse.lsp4j.services.TextDocumentService",
            "org.eclipse.lsp4j.services.WorkspaceService",
            "org.eclipse.lsp4j.services.NotebookDocumentService");
    private static final String JSONRPC_ENDPOINT = "org.eclipse.lsp4j.jsonrpc.Endpoint";

    @BuildStep
    void indexLspArtifacts(BuildProducer<IndexDependencyBuildItem> dependencies) {
        dependencies.produce(new IndexDependencyBuildItem("org.eclipse.lsp4j", "org.eclipse.lsp4j"));
        dependencies.produce(new IndexDependencyBuildItem("org.eclipse.lsp4j", "org.eclipse.lsp4j.jsonrpc"));
        dependencies.produce(new IndexDependencyBuildItem("dev.omatheusmesmo", "vintelli-lsp"));
    }

    @BuildStep
    ReflectiveClassBuildItem lsp4jTypes(CombinedIndexBuildItem index) {
        List<String> names = index.getIndex().getKnownClasses().stream()
                .map(ClassInfo::name)
                .map(Object::toString)
                .filter(name -> name.startsWith(LSP4J_PREFIX))
                .toList();
        List<String> registrations = new ArrayList<>(names);
        index.getIndex().getAllKnownImplementors(LANGUAGE_CLIENT).stream()
                .map(ClassInfo::name)
                .map(Object::toString)
                .filter(name -> !name.startsWith(LSP4J_PREFIX))
                .forEach(registrations::add);
        return ReflectiveClassBuildItem.builder(registrations.toArray(String[]::new))
                .constructors()
                .methods()
                .fields()
                .build();
    }

    @BuildStep
    void lsp4jProxies(BuildProducer<NativeImageProxyDefinitionBuildItem> proxies) {
        for (String service : PROXY_ENDPOINTS) {
            proxies.produce(new NativeImageProxyDefinitionBuildItem(service, JSONRPC_ENDPOINT));
        }
    }
}
