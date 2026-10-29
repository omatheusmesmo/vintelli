package dev.omatheusmesmo.vintelli.core.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;

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
}
