package dev.omatheusmesmo.vintelli.cli;

import dev.omatheusmesmo.vintelli.api.acp.AcpChatPort;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;

@CommandDefinition(name = "list", description = "Lists ACP agents and their executable availability.")
@Singleton
public final class AgentsListCommand implements Command<CommandInvocation> {

    private final AcpChatPort chat;

    /** Required by the AEsh metadata generator; CDI uses the injected constructor. */
    public AgentsListCommand() {
        this(null);
    }

    @Inject
    public AgentsListCommand(AcpChatPort chat) {
        this.chat = chat;
    }

    @Override
    public CommandResult execute(CommandInvocation invocation) {
        for (AcpChatPort.Agent agent : chat.agents()) {
            invocation.println(agent.id() + " - " + agent.name() + ": "
                    + (agent.available() ? "ready" : agent.reason()));
        }
        return CommandResult.SUCCESS;
    }
}
