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

/**
 * AEsh group command of the ACP Agents tool window: its subcommands drive the
 * agent sessions. Created as a CDI bean so the subcommands keep their injected
 * capability ports when AEsh resolves the group tree.
 */
@CommandDefinition(name = "agents", description = "Drives the ACP agent sessions.")
@Singleton
public final class AgentsCommand implements GroupCommand<CommandInvocation> {

    @Inject
    AgentPromptCommand prompt;

    @Override
    public List<Command<CommandInvocation>> getCommands() {
        return List.of(prompt);
    }

    @Override
    public CommandResult execute(CommandInvocation invocation) throws CommandException, InterruptedException {
        invocation.println("No agents subcommand given: use \"agents prompt <text>\"");
        return CommandResult.USAGE_ERROR;
    }
}
