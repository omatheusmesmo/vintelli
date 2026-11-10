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

@Singleton
final class ShellConfigProducer {

    @Produces
    ShellConfig shellConfig(ToolWindowRegistry registry, ShellState state, KeymapRegistry keymapRegistry,
            Instance<ShellThemes> themes, ProjectSearch projectSearch,
            @All List<NavigationCapability> navigationProviders, CapabilityRegistry capabilityRegistry,
            @All List<StatusBarWidgetContributor> statusBarContributors,
            @All List<VersionControlCapability> vcsProviders, @All List<ProjectFileOperations> fileOperations,
            Instance<IdeaMcpConnection> ideaMcpConnections) {
        return ShellConfig.of(registry.toolWindows(), state, themes.get())
                .withKeymapSupplier(keymapRegistry::get)
                .withProjectSearch(projectSearch)
                .withNavigationProviders(navigationProviders)
                .withCapabilityRegistry(capabilityRegistry)
                .withVcsProviders(vcsProviders)
                .withPersistLayout(true)
                .withStatusBarContributors(statusBarContributors)
                .withFileOperations(fileOperations.isEmpty() ? null : fileOperations.getFirst())
                .withIdeaMcpConnection(ideaMcpConnections.isResolvable() ? ideaMcpConnections.get() : null);
    }
}
