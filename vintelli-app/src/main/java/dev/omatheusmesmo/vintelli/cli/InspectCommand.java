package dev.omatheusmesmo.vintelli.cli;

import dev.omatheusmesmo.vintelli.api.ShellStateSnapshot;
import dev.omatheusmesmo.vintelli.api.ShellStateView;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.SkippedProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandException;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;

/**
 * AEsh counterpart of the read-only inspection surfaces the app already
 * serves: the shell selection the MCP tools read through
 * {@link ShellStateView} and the capability status rows the HTTP
 * {@code /capabilities} endpoint renders. Prints both as plain text lines, so
 * a headless user inspects the same state without a browser.
 */
@CommandDefinition(name = "inspect", description = "Inspects the current shell selection and the capability status rows.")
@Singleton
public final class InspectCommand implements Command<CommandInvocation> {

    @Inject
    CapabilityRegistry capabilities;

    @Inject
    ShellStateView shellState;

    @Override
    public CommandResult execute(CommandInvocation invocation) throws CommandException, InterruptedException {
        ShellStateSnapshot snapshot = shellState.snapshot();
        invocation.println("Shell: tool window '" + snapshot.selectedToolWindowId()
                + "', file '" + snapshot.selectedFile() + "'");
        for (CapabilityStatus row : capabilities.snapshot()) {
            invocation.println(describe(row));
        }
        return CommandResult.SUCCESS;
    }

    private static String describe(CapabilityStatus row) {
        StringBuilder description = new StringBuilder(row.capability().label()).append(": ");
        if (row.isAvailable()) {
            description.append(row.providerIdentifier()).append(" (").append(row.provenance()).append(')');
        } else {
            description.append("unavailable: ").append(row.health().reason());
        }
        for (SkippedProvider skipped : row.skipped()) {
            description.append("; skipped ").append(skipped.identifier()).append(" (").append(skipped.reason()).append(')');
        }
        return description.toString();
    }
}
