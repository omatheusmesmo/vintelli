package dev.omatheusmesmo.vintelli.cli;

import java.util.Locale;

import dev.omatheusmesmo.vintelli.api.idea.IdeaMcpConnection;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandException;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;

/** Reports the latest IDEA MCP state without starting a connection attempt. */
@CommandDefinition(name = "status", description = "Shows the current IntelliJ IDEA MCP connection state.")
@Singleton
public final class IdeaStatusCommand implements Command<CommandInvocation> {

    private final IdeaMcpConnection connection;

    /** Required by the AEsh generated metadata factory; CDI uses the constructor below. */
    public IdeaStatusCommand() {
        this.connection = null;
    }

    @Inject
    IdeaStatusCommand(IdeaMcpConnection connection) {
        this.connection = connection;
    }

    @Override
    public CommandResult execute(CommandInvocation invocation) throws CommandException, InterruptedException {
        IdeaMcpConnection.Snapshot status = connection.connectionStatus();
        invocation.println(render(status));
        return "CONNECTED".equals(status.state()) ? CommandResult.SUCCESS : CommandResult.FAILURE;
    }

    static String render(IdeaMcpConnection.Snapshot status) {
        return "IDEA MCP status: " + status.state().toLowerCase(Locale.ROOT).replace('_', ' ')
                + (status.detail().isBlank() ? "" : " — " + status.detail());
    }
}
