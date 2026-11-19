package dev.omatheusmesmo.vintelli.acp;

import java.util.List;

/**
 * Starts the {@link EchoAcpAgent} twin advertising the plan and edit session modes.
 */
public final class EchoAcpAgentModesResource extends EchoAcpAgentResource {

    @Override
    protected List<String> twinArguments() {
        return List.of("--modes");
    }
}
