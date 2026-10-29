package dev.omatheusmesmo.vintelli.cli;

import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.aesh.command.AeshCommandRuntimeBuilder;
import org.aesh.command.Command;
import org.aesh.command.CommandException;
import org.aesh.command.CommandNotFoundException;
import org.aesh.command.CommandResult;
import org.aesh.command.CommandRuntime;
import org.aesh.command.impl.registry.AeshCommandRegistryBuilder;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.parser.CommandLineParserException;
import org.aesh.command.validator.CommandValidatorException;
import org.aesh.command.validator.OptionValidatorException;

/**
 * The AEsh command layer of Vintelli: the CDI-built root commands of the CLI,
 * registered as instances so every command keeps its injected capability
 * ports while the {@code aesh-processor} generated metadata does the parsing.
 * The AEsh reflection annotation-scan path is never used: the build carries
 * {@code aesh-processor} and every {@code @CommandDefinition} here has a
 * generated provider.
 *
 * <p>One-shot mode is scriptable and POSIX shaped: the first argument names a
 * command and the rest go to it pre-tokenized, so quoted prompt text and
 * special characters survive.
 */
@Singleton
public final class VintelliCliCommands {

    private static final String HELP_COMMAND = "help";
    private static final String HELP_OPTION = "--help";

    private final AeshCommandRegistryBuilder<CommandInvocation> registryBuilder;
    private final CommandRuntime<CommandInvocation> runtime;

    @Inject
    VintelliCliCommands(RunCommand run, TerminalCommand terminal, ProblemsCommand problems,
            InspectCommand inspect, AgentsCommand agents) {
        this.registryBuilder = AeshCommandRegistryBuilder.builder();
        register(run);
        register(terminal);
        register(problems);
        register(inspect);
        register(agents);
        this.runtime = AeshCommandRuntimeBuilder.<CommandInvocation>builder()
                .commandRegistry(registryBuilder.create())
                .build();
    }

    private void register(Command<CommandInvocation> command) {
        try {
            registryBuilder.command(command);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot register the AEsh command "
                    + command.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    AeshCommandRegistryBuilder<CommandInvocation> registryBuilder() {
        return registryBuilder;
    }

    /**
     * @return the command names of the CLI root
     */
    Set<String> commandNames() {
        return runtime.getCommandRegistry().getAllCommandNames();
    }

    private int listCommands() {
        System.out.println("Available commands: " + String.join(", ", commandNames()));
        return CommandResult.SUCCESS.getExitCode();
    }

    private int printUsage(String commandName) {
        String usage = runtime.commandInfo(commandName);
        if (usage == null) {
            System.err.println("Unknown command: " + commandName);
            System.err.println("Available commands: " + String.join(", ", commandNames()));
            return CommandResult.COMMAND_NOT_FOUND.getExitCode();
        }
        System.out.println(usage);
        return CommandResult.SUCCESS.getExitCode();
    }

    /**
     * Executes one pre-tokenized command line and maps the outcome on the
     * POSIX shaped {@link CommandResult} codes.
     *
     * @param tokens the command line, first token naming the command
     * @return the process exit code
     */
    public int execute(List<String> tokens) {
        String commandName = tokens.get(0);
        String[] arguments = tokens.subList(1, tokens.size()).toArray(String[]::new);
        if (HELP_COMMAND.equals(commandName)) {
            return arguments.length == 0 ? listCommands() : printUsage(arguments[0]);
        }
        if (arguments.length > 0 && HELP_OPTION.equals(arguments[0])) {
            return printUsage(commandName);
        }
        try {
            return runtime.executeCommand(commandName, arguments).getExitCode();
        } catch (CommandNotFoundException e) {
            System.err.println("Unknown command: " + e.getCommandName());
            System.err.println("Available commands: " + String.join(", ", commandNames()));
            return CommandResult.COMMAND_NOT_FOUND.getExitCode();
        } catch (CommandLineParserException | OptionValidatorException | CommandValidatorException e) {
            System.err.println(e.getMessage());
            String help = runtime.commandInfo(commandName);
            if (help != null) {
                System.err.println(help);
            }
            return CommandResult.USAGE_ERROR.getExitCode();
        } catch (CommandException e) {
            System.err.println(e.getMessage());
            return CommandResult.FAILURE.getExitCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CommandResult.INTERRUPTED.getExitCode();
        } catch (Exception e) {
            System.err.println(commandName + " failed: " + e.getMessage());
            return CommandResult.FAILURE.getExitCode();
        }
    }
}
