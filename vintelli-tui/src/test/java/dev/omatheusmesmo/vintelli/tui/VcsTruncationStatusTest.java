package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VcsTruncationStatusTest {

    @Test
    void statusMarksTruncatedChangedPathCountAsPartial() {
        VersionControlCapability.Status complete = new VersionControlCapability.Status("main", 0, 0,
                List.of(new VersionControlCapability.Entry(VersionControlCapability.State.MODIFIED, "one.txt")));
        VersionControlCapability.Status truncated = new VersionControlCapability.Status("main", 0, 0,
                List.of(new VersionControlCapability.Entry(VersionControlCapability.State.MODIFIED, "one.txt")), true);

        assertEquals("VCS: main (+0/-0), 1 changed", shellWith(complete).status());
        assertEquals("VCS: main (+0/-0), 1+ changed (partial)", shellWith(truncated).status());
    }

    private static VintelliShell shellWith(VersionControlCapability.Status status) {
        List<ToolWindow> windows = List.of();
        VersionControlCapability provider = new VersionControlCapability() {
            @Override
            public CapabilityResult<Status> status() {
                return CapabilityResult.ok(status);
            }

            @Override
            public CapabilityResult<String> diff(Path path) {
                return CapabilityResult.ok("");
            }

            @Override
            public CapabilityResult<String> diffWorkingTree() {
                return CapabilityResult.ok("");
            }

            @Override
            public CapabilityResult<Boolean> stage(Path path, boolean confirmed) {
                return CapabilityResult.ok(false);
            }

            @Override
            public CapabilityResult<Boolean> unstage(Path path, boolean confirmed) {
                return CapabilityResult.ok(false);
            }

            @Override
            public CapabilityResult<Boolean> commit(String message, boolean confirmed) {
                return CapabilityResult.ok(false);
            }

            @Override
            public CapabilityResult<Boolean> switchBranch(String branch, boolean confirmed) {
                return CapabilityResult.ok(false);
            }

            @Override
            public String identifier() {
                return "test-vcs";
            }

            @Override
            public ProviderHealth health() {
                return ProviderHealth.available();
            }

            @Override
            public String provenance() {
                return "test provider";
            }
        };
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        VintelliShell shell = new VintelliShell(windows, new ShellState(), () -> Keymap.defaultKeymap(windows),
                themes, null, List.of(), List.of(provider));
        shell.refreshVcs();
        return shell;
    }
}
