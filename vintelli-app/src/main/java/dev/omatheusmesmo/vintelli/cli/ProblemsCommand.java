package dev.omatheusmesmo.vintelli.cli;

import java.nio.file.Path;
import java.util.List;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandException;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Argument;

/**
 * AEsh wrapper of the {@link DiagnosticsCapability} port behind the Problems
 * tool window: prints every problem the selected provider currently knows for
 * one file in a {@code severity source path:line:column message} shape.
 */
@CommandDefinition(name = "problems", description = "Lists the code problems reported for one file.")
@Singleton
public final class ProblemsCommand implements Command<CommandInvocation> {

    @Inject
    CapabilityRegistry capabilities;

    @Argument(description = "File to report the problems of")
    String file;

    @Override
    public CommandResult execute(CommandInvocation invocation) throws CommandException, InterruptedException {
        return switch (capabilities.diagnostics()) {
            case CapabilitySelection.Selected<DiagnosticsCapability> selection ->
                    problems(selection.provider(), invocation);
            case CapabilitySelection.Unavailable<DiagnosticsCapability> unavailable -> {
                invocation.println("Problems capability is unavailable: " + unavailable.reason());
                yield CommandResult.FAILURE;
            }
        };
    }

    private CommandResult problems(DiagnosticsCapability diagnostics, CommandInvocation invocation) {
        CapabilityResult<List<DiagnosticsCapability.Diagnostic>> problems =
                diagnostics.diagnostics(Path.of(file));
        if (problems instanceof CapabilityResult.Failed<List<DiagnosticsCapability.Diagnostic>> failed) {
            invocation.println(describe(failed.failure()));
            return CommandResult.FAILURE;
        }
        List<DiagnosticsCapability.Diagnostic> diagnosticsFound =
                ((CapabilityResult.Ok<List<DiagnosticsCapability.Diagnostic>>) problems).value();
        if (diagnosticsFound.isEmpty()) {
            invocation.println("No problems in " + file);
            return CommandResult.SUCCESS;
        }
        for (DiagnosticsCapability.Diagnostic diagnostic : diagnosticsFound) {
            invocation.println(describe(diagnostic));
        }
        return CommandResult.SUCCESS;
    }

    private static String describe(CapabilityFailure failure) {
        return failure.reason() + (failure.detail().isEmpty() ? "" : ": " + failure.detail());
    }

    private static String describe(DiagnosticsCapability.Diagnostic diagnostic) {
        return diagnostic.severity() + " " + diagnostic.source() + " "
                + diagnostic.filePath() + ":" + (diagnostic.startLine() + 1) + ":" + (diagnostic.startColumn() + 1)
                + " " + diagnostic.message();
    }
}
