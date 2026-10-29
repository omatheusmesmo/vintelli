package dev.omatheusmesmo.vintelli.acp;

import java.util.List;

/**
 * The twin answers protocol version 2 to an initialize that asked for version 1.
 */
public final class EchoAcpAgentVersionMismatchResource extends EchoAcpAgentResource {

    @Override
    protected List<String> twinArguments() {
        return List.of("--protocol-version=2");
    }
}
