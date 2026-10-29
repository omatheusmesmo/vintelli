package dev.omatheusmesmo.vintelli.cli;

import java.nio.file.Path;
import java.util.List;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.TerminalCapability;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandException;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Arguments;
import org.aesh.command.option.Option;

/**
 * AEsh wrapper of the {@link TerminalCapability} port behind the Terminal tool
 * window. The command line is executed by the selected provider, which may
 * answer with a confirmation request when the command is dangerous: the caller
 * reissues it with the {@code --yes} flag, or through the interactive
 * confirmation the provider asks for.
 */
@CommandDefinition(name = "terminal", description = "Executes the command line with the selected terminal provider.",
        stopAtFirstPositional = true)
@Singleton
public final class TerminalCommand implements Command<CommandInvocation> {

    @Inject
    CapabilityRegistry capabilities;

    @Arguments(description = "Command line to execute")
    List<String> words;

    @Option(shortName = 'y', hasValue = false, name = "yes", description = "Confirm a command the provider asked to confirm explicitly")
    boolean confirmed;

    @Override
    public CommandResult execute(CommandInvocation invocation) throws CommandException, InterruptedException {
        if (words == null || words.isEmpty()) {
            invocation.println("Nothing to execute: pass a command line");
            return CommandResult.USAGE_ERROR;
        }
        String executionLine = String.join(" ", words);
        return switch (capabilities.terminal()) {
            case CapabilitySelection.Selected<TerminalCapability> selection ->
                    terminal(selection.provider(), executionLine, invocation);
            case CapabilitySelection.Unavailable<TerminalCapability> unavailable -> {
                invocation.println("Terminal capability is unavailable: " + unavailable.reason());
                yield CommandResult.FAILURE;
            }
        };
    }

    private CommandResult terminal(TerminalCapability terminal, String executionLine, CommandInvocation invocation) {
        CapabilityResult<TerminalCapability.TerminalResult> first = terminal.execute(
                new TerminalCapability.TerminalRequest(executionLine, Path.of("."), confirmed));
        if (first.isFailure()) {
            invocation.println(describe(first));
            return CommandResult.FAILURE;
        }
        TerminalCapability.TerminalResult result =
                ((CapabilityResult.Ok<TerminalCapability.TerminalResult>) first).value();
        if (result.outcome() == TerminalCapability.TerminalOutcome.REQUIRES_CONFIRMATION && !confirmed) {
            invocation.println("Vintelli asks for explicit confirmation before running this line");
            invocation.println("Reissue with \"terminal --yes " + executionLine + "\"");
            return CommandResult.FAILURE;
        }
        invocation.print(result.output());
        return result.exitCode() == 0 ? CommandResult.SUCCESS : CommandResult.FAILURE;
    }

    private static String describe(CapabilityResult<?> failed) {
        if (failed instanceof CapabilityResult.Failed<?> failure) {
            return failure.failure().reason() + (failure.failure().detail().isEmpty()
                    ? "" : ": " + failure.failure().detail());
        }
        return "unavailable";
    }
}
