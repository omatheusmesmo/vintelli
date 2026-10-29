package dev.omatheusmesmo.vintelli.cli;

import org.aesh.command.Command;
import org.aesh.command.metadata.CommandMetadataProvider;
import org.aesh.command.metadata.MetadataProviderRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The {@code aesh-processor} contract: every Vintelli command resolves through
 * its build-time generated metadata provider, so the reflective annotation
 * fallback of AEsh never runs, and the generated command model is complete
 * for the native image. Removing {@code aesh-processor} from the build fails
 * this test.
 */
class AeshGeneratedMetadataTest {

    @Test
    void everyRootCommandHasAGeneratedMetadataProvider() {
        assertGenerated(RunCommand.class);
        assertGenerated(TerminalCommand.class);
        assertGenerated(ProblemsCommand.class);
        assertGenerated(InspectCommand.class);
        assertGenerated(AgentsCommand.class);
    }

    @Test
    void everyAgentsSubcommandHasAGeneratedMetadataProvider() {
        assertGenerated(AgentPromptCommand.class);
    }

    @Test
    void theProvidersCarryTheAnnotatedCommandNames() {
        assertEquals("run", MetadataProviderRegistry.getProvider(RunCommand.class).commandName());
        assertEquals("terminal", MetadataProviderRegistry.getProvider(TerminalCommand.class).commandName());
        assertEquals("problems", MetadataProviderRegistry.getProvider(ProblemsCommand.class).commandName());
        assertEquals("inspect", MetadataProviderRegistry.getProvider(InspectCommand.class).commandName());
        assertEquals("agents", MetadataProviderRegistry.getProvider(AgentsCommand.class).commandName());
        assertEquals("prompt", MetadataProviderRegistry.getProvider(AgentPromptCommand.class).commandName());
    }

    private static void assertGenerated(Class<? extends Command<?>> commandClass) {
        CommandMetadataProvider<?> provider = MetadataProviderRegistry.getProvider(commandClass);
        assertNotNull(provider, "no generated metadata provider for " + commandClass.getName());
    }
}
