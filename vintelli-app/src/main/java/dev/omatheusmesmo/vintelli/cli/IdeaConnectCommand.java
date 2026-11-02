package dev.omatheusmesmo.vintelli.cli;

import dev.omatheusmesmo.vintelli.api.idea.IdeaMcpConnection;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandException;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;

/** Connects to the configured IDEA MCP server and prints its classified state. */
@CommandDefinition(name = "connect", description = "Connects to the configured IntelliJ IDEA MCP server.")
@Singleton
public final class IdeaConnectCommand implements Command<CommandInvocation> {

    private final IdeaMcpConnection connection;

    /** Required by the AEsh generated metadata factory; CDI uses the constructor below. */
    public IdeaConnectCommand() {
        this.connection = null;
    }

    @Inject
    IdeaConnectCommand(IdeaMcpConnection connection) {
        this.connection = connection;
    }

    @Override
    public CommandResult execute(CommandInvocation invocation) throws CommandException, InterruptedException {
        return report(connection.reconnectFromUserAction(), invocation);
    }

    static CommandResult report(IdeaMcpConnection.Snapshot status, CommandInvocation invocation) {
        invocation.println(IdeaStatusCommand.render(status));
        return "CONNECTED".equals(status.state()) ? CommandResult.SUCCESS : CommandResult.FAILURE;
    }
}
