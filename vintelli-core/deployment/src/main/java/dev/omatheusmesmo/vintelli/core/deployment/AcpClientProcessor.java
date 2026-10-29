package dev.omatheusmesmo.vintelli.core.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import org.jboss.jandex.ClassInfo;

import java.util.List;

/**
 * Build steps for the ACP client glue: indexes {@code acp-core} and keeps its
 * {@code spec.AcpSchema} wire types reachable for Jackson 3, which
 * (de)serializes them through reflection in the native image.
 */
final class AcpClientProcessor {

    private static final String ACP_SCHEMA = "com.agentclientprotocol.sdk.spec.AcpSchema";

    @BuildStep
    IndexDependencyBuildItem indexAcpCore() {
        return new IndexDependencyBuildItem("com.agentclientprotocol", "acp-core");
    }

    @BuildStep
    ReflectiveClassBuildItem acpSchemaTypes(CombinedIndexBuildItem index) {
        List<String> names = index.getIndex().getKnownClasses().stream()
                .map(ClassInfo::name)
                .map(Object::toString)
                .filter(name -> name.equals(ACP_SCHEMA) || name.startsWith(ACP_SCHEMA + "$"))
                .sorted()
                .toList();
        return ReflectiveClassBuildItem.builder(names.toArray(String[]::new))
                .constructors()
                .methods()
                .fields()
                .build();
    }
}
