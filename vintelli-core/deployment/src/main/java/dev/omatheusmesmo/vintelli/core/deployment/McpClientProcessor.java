package dev.omatheusmesmo.vintelli.core.deployment;

import dev.omatheusmesmo.vintelli.core.McpClientProducers;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import org.jboss.jandex.ClassInfo;

import java.util.List;

/**
 * Build steps for the MCP client glue: keeps the JSON mapper and schema
 * validator producers and registers the McpSchema wire types for reflection,
 * since the SDK (de)serializes them with Jackson.
 */
final class McpClientProcessor {

    private static final String MCP_SCHEMA = "io.modelcontextprotocol.spec.McpSchema";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem("vintelli-core");
    }

    @BuildStep
    AdditionalBeanBuildItem producers() {
        return AdditionalBeanBuildItem.unremovableOf(McpClientProducers.class);
    }

    @BuildStep
    IndexDependencyBuildItem indexMcpCore() {
        return new IndexDependencyBuildItem("io.modelcontextprotocol.sdk", "mcp-core");
    }

    @BuildStep
    ReflectiveClassBuildItem mcpSchemaTypes(CombinedIndexBuildItem index) {
        List<String> names = index.getIndex().getKnownClasses().stream()
                .map(ClassInfo::name)
                .map(Object::toString)
                .filter(name -> name.equals(MCP_SCHEMA) || name.startsWith(MCP_SCHEMA + "$"))
                .sorted()
                .toList();
        return ReflectiveClassBuildItem.builder(names.toArray(String[]::new))
                .constructors()
                .methods()
                .fields()
                .build();
    }
}
