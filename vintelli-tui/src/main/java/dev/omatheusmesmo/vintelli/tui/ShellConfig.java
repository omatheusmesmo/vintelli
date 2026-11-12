package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProjectFileOperations;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.api.idea.IdeaMcpConnection;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch;
import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidgetContributor;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.layout.ShellLayoutStore;
import java.util.List;
import java.util.function.Supplier;

public record ShellConfig(List<ToolWindow> toolWindows, ShellState state, Supplier<Keymap> keymapSupplier,
        ShellThemes themes, ProjectSearch projectSearch, List<NavigationCapability> navigationProviders,
        CapabilityRegistry capabilityRegistry, List<VersionControlCapability> vcsProviders, boolean persistLayout,
        List<? extends StatusBarWidgetContributor> statusBarContributors, ProjectFileOperations fileOperations,
        IdeaMcpConnection ideaMcpConnection, IdeaStatusBadge ideaStatus, ShellLayoutStore layoutStore,
        boolean marquee) {

    /**
     * The shortest shell configuration: the default keymap built from the tool windows, no project
     * search, no navigation or version control providers, no registry, no file operations, no IDEA
     * connection, no IDEA status segment, no layout store override, a non-persisted layout, the marquee
     * off, and the tool windows as status bar contributors.
     */
    public static ShellConfig of(List<ToolWindow> toolWindows, ShellState state, ShellThemes themes) {
        return new ShellConfig(toolWindows, state, () -> Keymap.defaultKeymap(toolWindows), themes, null, List.of(),
                null, List.of(), false, toolWindows, null, null, IdeaStatusBadge.absent(), null, false);
    }

    public ShellConfig withMarquee(boolean marquee) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, ideaStatus, layoutStore, marquee);
    }

    public ShellConfig withKeymap(Keymap keymap) {
        return withKeymapSupplier(() -> keymap);
    }

    public ShellConfig withKeymapSupplier(Supplier<Keymap> keymapSupplier) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, ideaStatus, layoutStore, marquee);
    }

    public ShellConfig withProjectSearch(ProjectSearch projectSearch) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, ideaStatus, layoutStore, marquee);
    }

    public ShellConfig withNavigationProviders(List<NavigationCapability> navigationProviders) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, ideaStatus, layoutStore, marquee);
    }

    public ShellConfig withCapabilityRegistry(CapabilityRegistry capabilityRegistry) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, ideaStatus, layoutStore, marquee);
    }

    public ShellConfig withVcsProviders(List<VersionControlCapability> vcsProviders) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, ideaStatus, layoutStore, marquee);
    }

    public ShellConfig withPersistLayout(boolean persistLayout) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, ideaStatus, layoutStore, marquee);
    }

    public ShellConfig withStatusBarContributors(List<? extends StatusBarWidgetContributor> statusBarContributors) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, ideaStatus, layoutStore, marquee);
    }

    public ShellConfig withFileOperations(ProjectFileOperations fileOperations) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, ideaStatus, layoutStore, marquee);
    }

    public ShellConfig withIdeaMcpConnection(IdeaMcpConnection ideaMcpConnection) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, ideaStatus, layoutStore, marquee);
    }

    public ShellConfig withIdeaStatus(IdeaStatusBadge ideaStatus) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, ideaStatus, layoutStore, marquee);
    }

    public ShellConfig withLayoutStore(ShellLayoutStore layoutStore) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, ideaStatus, layoutStore, marquee);
    }
}
