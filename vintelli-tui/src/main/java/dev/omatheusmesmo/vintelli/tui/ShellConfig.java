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
        IdeaMcpConnection ideaMcpConnection, ShellLayoutStore layoutStore) {

    /**
     * The shortest shell configuration: the default keymap built from the tool windows, no project
     * search, no navigation or version control providers, no registry, no file operations, no IDEA
     * connection, no layout store override, a non-persisted layout, and the tool windows as status bar
     * contributors.
     */
    public static ShellConfig of(List<ToolWindow> toolWindows, ShellState state, ShellThemes themes) {
        return new ShellConfig(toolWindows, state, () -> Keymap.defaultKeymap(toolWindows), themes, null, List.of(),
                null, List.of(), false, toolWindows, null, null, null);
    }

    public ShellConfig withKeymap(Keymap keymap) {
        return withKeymapSupplier(() -> keymap);
    }

    public ShellConfig withKeymapSupplier(Supplier<Keymap> keymapSupplier) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, layoutStore);
    }

    public ShellConfig withProjectSearch(ProjectSearch projectSearch) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, layoutStore);
    }

    public ShellConfig withNavigationProviders(List<NavigationCapability> navigationProviders) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, layoutStore);
    }

    public ShellConfig withCapabilityRegistry(CapabilityRegistry capabilityRegistry) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, layoutStore);
    }

    public ShellConfig withVcsProviders(List<VersionControlCapability> vcsProviders) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, layoutStore);
    }

    public ShellConfig withPersistLayout(boolean persistLayout) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, layoutStore);
    }

    public ShellConfig withStatusBarContributors(List<? extends StatusBarWidgetContributor> statusBarContributors) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, layoutStore);
    }

    public ShellConfig withFileOperations(ProjectFileOperations fileOperations) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, layoutStore);
    }

    public ShellConfig withIdeaMcpConnection(IdeaMcpConnection ideaMcpConnection) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, layoutStore);
    }

    public ShellConfig withLayoutStore(ShellLayoutStore layoutStore) {
        return new ShellConfig(toolWindows, state, keymapSupplier, themes, projectSearch, navigationProviders,
                capabilityRegistry, vcsProviders, persistLayout, statusBarContributors, fileOperations,
                ideaMcpConnection, layoutStore);
    }
}
