package dev.omatheusmesmo.vintelli.cli;

import dev.omatheusmesmo.vintelli.api.idea.IdeaMcpConnection;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandException;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;

/** Drops the previous IDEA MCP session and establishes a fresh connection. */
@CommandDefinition(name = "reconnect", description = "Reconnects to the configured IntelliJ IDEA MCP server.")
@Singleton
public final class IdeaReconnectCommand implements Command<CommandInvocation> {

    private final IdeaMcpConnection connection;

    /** Required by the AEsh generated metadata factory; CDI uses the constructor below. */
    public IdeaReconnectCommand() {
        this.connection = null;
    }

    @Inject
    IdeaReconnectCommand(IdeaMcpConnection connection) {
        this.connection = connection;
    }

    @Override
    public CommandResult execute(CommandInvocation invocation) throws CommandException, InterruptedException {
        return IdeaConnectCommand.report(connection.reconnectFromUserAction(), invocation);
    }
}
