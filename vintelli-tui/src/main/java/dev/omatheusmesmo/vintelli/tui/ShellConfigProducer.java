package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProjectFileOperations;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.api.idea.IdeaMcpConnection;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch;
import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidgetContributor;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapRegistry;
import io.quarkus.arc.All;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Singleton
final class ShellConfigProducer {

    @Produces
    ShellConfig shellConfig(ToolWindowRegistry registry, ShellState state, KeymapRegistry keymapRegistry,
            Instance<ShellThemes> themes, ProjectSearch projectSearch,
            @All List<NavigationCapability> navigationProviders, CapabilityRegistry capabilityRegistry,
            @All List<StatusBarWidgetContributor> statusBarContributors,
            @All List<VersionControlCapability> vcsProviders, @All List<ProjectFileOperations> fileOperations,
            Instance<IdeaMcpConnection> ideaMcpConnections,
            @ConfigProperty(name = "vintelli.idea.mcp.url") Optional<String> ideaUrl,
            @ConfigProperty(name = "vintelli.tui.marquee", defaultValue = "true") boolean marquee) {
        IdeaMcpConnection ideaMcpConnection = ideaMcpConnections.isResolvable() ? ideaMcpConnections.get() : null;
        return ShellConfig.of(registry.toolWindows(), state, themes.get())
                .withKeymapSupplier(keymapRegistry::get)
                .withProjectSearch(projectSearch)
                .withNavigationProviders(navigationProviders)
                .withCapabilityRegistry(capabilityRegistry)
                .withVcsProviders(vcsProviders)
                .withPersistLayout(true)
                .withStatusBarContributors(statusBarContributors)
                .withFileOperations(fileOperations.isEmpty() ? null : fileOperations.getFirst())
                .withIdeaMcpConnection(ideaMcpConnection)
                .withIdeaStatus(IdeaStatusBadge.of(ideaUrl, ideaMcpConnection))
                .withMarquee(marquee);
    }
}
