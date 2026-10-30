package dev.omatheusmesmo.vintelli.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.RunCapability;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandException;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Argument;

/**
 * AEsh wrapper of the {@link RunCapability} port, the same capability the Run
 * tool window reads. Without a name it lists what the selected provider can
 * run; with a name it starts the run and streams the output lines live into
 * this command output.
 */
@CommandDefinition(name = "run", description = "Runs a build tool goal, task or run configuration; without a name it lists what can run.")
@Singleton
public final class RunCommand implements Command<CommandInvocation> {

    @Inject
    CapabilityRegistry capabilities;

    @Argument(description = "Configuration name to run, for example a Maven lifecycle goal; empty lists the available names")
    String configurationName;

    @Override
    public CommandResult execute(CommandInvocation invocation) throws CommandException, InterruptedException {
        return switch (capabilities.run()) {
            case CapabilitySelection.Selected<RunCapability> selection -> execute(selection.provider(), invocation);
            case CapabilitySelection.Unavailable<RunCapability> unavailable -> {
                invocation.println("Run capability is unavailable: " + unavailable.reason());
                yield CommandResult.FAILURE;
            }
        };
    }

    private CommandResult execute(RunCapability runs, CommandInvocation invocation) {
        if (configurationName == null || configurationName.isBlank()) {
            return list(runs.listConfigurations(), invocation);
        }
        CapabilityResult<RunCapability.RunHandle> started = runs.start(
                new RunCapability.RunRequest(configurationName, Path.of("."), Map.of()),
                invocation::println);
        if (started.isFailure()) {
            return failure(started, invocation);
        }
        return CommandResult.SUCCESS;
    }

    private static CommandResult list(CapabilityResult<List<RunCapability.RunConfiguration>> listing,
            CommandInvocation invocation) {
        if (listing instanceof CapabilityResult.Failed<List<RunCapability.RunConfiguration>> failed) {
            invocation.println(failed.failure().reason());
            return CommandResult.FAILURE;
        }
        List<RunCapability.RunConfiguration> configurations = ((CapabilityResult.Ok<List<RunCapability.RunConfiguration>>) listing).value();
        if (configurations.isEmpty()) {
            invocation.println("No run configurations found");
            return CommandResult.SUCCESS;
        }
        for (RunCapability.RunConfiguration configuration : configurations) {
            invocation.println(configuration.identifier() + " " + configuration.summary());
        }
        return CommandResult.SUCCESS;
    }

    private static CommandResult failure(CapabilityResult<?> result, CommandInvocation invocation) {
        CapabilityResult.Failed<?> failed = (CapabilityResult.Failed<?>) result;
        CapabilityFailure failure = failed.failure();
        invocation.println(failure.reason() + (failure.detail().isEmpty() ? "" : ": " + failure.detail()));
        return CommandResult.FAILURE;
    }
}
