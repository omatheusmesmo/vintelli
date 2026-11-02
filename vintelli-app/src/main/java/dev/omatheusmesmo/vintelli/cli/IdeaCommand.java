package dev.omatheusmesmo.vintelli.cli;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandException;
import org.aesh.command.CommandResult;
import org.aesh.command.GroupCommand;
import org.aesh.command.invocation.CommandInvocation;

/** AEsh command group for inspecting and controlling the optional IDEA MCP connection. */
@CommandDefinition(name = "idea", description = "Inspects and connects to the IntelliJ IDEA MCP server.")
@Singleton
public final class IdeaCommand implements GroupCommand<CommandInvocation> {

    private final IdeaStatusCommand status;
    private final IdeaConnectCommand connect;
    private final IdeaReconnectCommand reconnect;

    /** Required by the AEsh generated metadata factory; CDI uses the constructor below. */
    public IdeaCommand() {
        this.status = null;
        this.connect = null;
        this.reconnect = null;
    }

    @Inject
    IdeaCommand(IdeaStatusCommand status, IdeaConnectCommand connect, IdeaReconnectCommand reconnect) {
        this.status = status;
        this.connect = connect;
        this.reconnect = reconnect;
    }

    @Override
    public List<Command<CommandInvocation>> getCommands() {
        return List.of(status, connect, reconnect);
    }

    @Override
    public CommandResult execute(CommandInvocation invocation) throws CommandException, InterruptedException {
        invocation.println("No IDEA subcommand given: use \"idea status\", \"idea connect\" or \"idea reconnect\"");
        return CommandResult.USAGE_ERROR;
    }
}
