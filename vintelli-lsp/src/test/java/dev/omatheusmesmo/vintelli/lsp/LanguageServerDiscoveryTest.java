package dev.omatheusmesmo.vintelli.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class LanguageServerDiscoveryTest {

    private static final String LINUX = "Linux";
    private static final String WINDOWS = "Windows 11";
    private static final String MACOS = "Mac OS X";
    private static final String NO_SERVER_REASON = "No language server found. Install jdtls and put it on the PATH, "
            + "or set vintelli.lsp.profiles.<name>.command (see docs/language-servers.md).";

    @TempDir
    Path workspace;

    @Test
    void aJdtlsExecutableOnThePathIsDiscoveredByItsAbsolutePath() throws IOException {
        Path jdtls = executable(directory("bin"), "jdtls");

        Optional<LanguageServerDiscovery.Discovered> found = discovery(true, Map.of("PATH", parent(jdtls)), LINUX)
                .discover();

        assertEquals(jdtls.toAbsolutePath().toString(), found.orElseThrow().executable().toString());
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows treats every file as executable")
    void aJdtlsFileWithoutTheExecutableBitIsNotDiscovered() throws IOException {
        plainFile(directory("bin"), "jdtls");

        assertFalse(discovery(true, Map.of("PATH", workspace.resolve("bin").toString()), LINUX).discover().isPresent());
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows treats every file as executable")
    void thePathIsWalkedInOrderAndTheFirstExecutableWins() throws IOException {
        Path stale = plainFile(directory("stale"), "jdtls");
        Path installed = executable(directory("installed"), "jdtls");
        String path = stale.getParent() + File.pathSeparator + installed.getParent();

        Path found = discovery(true, Map.of("PATH", path), LINUX).discover().orElseThrow().executable();

        assertEquals(installed.toAbsolutePath().toString(), found.toString());
    }

    @Test
    void aDisabledDiscoveryNeverLooksAtThePath() throws IOException {
        Path jdtls = executable(directory("bin"), "jdtls");

        assertFalse(discovery(false, Map.of("PATH", parent(jdtls)), LINUX).discover().isPresent());
    }

    @Test
    void windowsCandidatesAreCmdBatAndExeOnlyOnWindows() throws IOException {
        Path launcher = executable(directory("bin"), "jdtls.bat");
        Map<String, String> environment = Map.of("PATH", parent(launcher));

        assertEquals(launcher.toAbsolutePath().toString(),
                discovery(true, environment, WINDOWS).discover().orElseThrow().executable().toString());
        assertFalse(discovery(true, environment, LINUX).discover().isPresent());
    }

    @Test
    void theImplicitProfileRunsTheDiscoveredExecutableWithTheDataDirectoryAndTheProjectRoot() throws IOException {
        Path jdtls = executable(directory("bin"), "jdtls");
        Path home = workspace.resolve("home");
        Path project = workspace.resolve("project");

        LspProfileConfig.Profile profile = discovery(true, Map.of("PATH", parent(jdtls)), LINUX, home, project)
                .discover().orElseThrow().profile();

        assertEquals(jdtls.toAbsolutePath().toString(), profile.command().orElseThrow());
        assertEquals(project.toAbsolutePath().normalize().toString(), profile.workspaceRoot().orElseThrow());
        List<String> args = profile.args().orElseThrow();
        assertEquals("-data", args.get(0));
        assertEquals(home.resolve(".cache").resolve("vintelli").resolve("jdtls-data"),
                Path.of(args.get(1)).getParent());
        assertTrue(Path.of(args.get(1)).getFileName().toString().matches("[0-9a-f]{16}"), args.get(1));
        assertEquals(Optional.empty(), profile.javaMinVersion());
        assertEquals(Optional.empty(), profile.initializationOptions());
        assertEquals(Optional.empty(), profile.javaHome());
    }

    @Test
    void theDataDirectoryIsStableForOneProjectAndDiffersBetweenProjects() throws IOException {
        Path jdtls = executable(directory("bin"), "jdtls");
        Map<String, String> environment = Map.of("PATH", parent(jdtls));
        Path home = workspace.resolve("home");

        String first = dataDirectory(discovery(true, environment, LINUX, home, workspace.resolve("one")));
        String again = dataDirectory(discovery(true, environment, LINUX, home, workspace.resolve("one")));
        String other = dataDirectory(discovery(true, environment, LINUX, home, workspace.resolve("two")));

        assertEquals(first, again);
        assertFalse(first.equals(other));
    }

    @Test
    void xdgCacheHomeOverridesTheDefaultOnLinux() throws IOException {
        Path jdtls = executable(directory("bin"), "jdtls");
        Path xdg = workspace.resolve("xdg-cache");
        Map<String, String> environment = Map.of("PATH", parent(jdtls), "XDG_CACHE_HOME", xdg.toString());

        String dataDirectory = dataDirectory(discovery(true, environment, LINUX, workspace.resolve("home"),
                workspace.resolve("project")));

        assertEquals(xdg.resolve("vintelli").resolve("jdtls-data"), Path.of(dataDirectory).getParent());
    }

    @Test
    void macOsKeepsTheDataDirectoryUnderLibraryCaches() throws IOException {
        Path jdtls = executable(directory("bin"), "jdtls");
        Path home = workspace.resolve("home");
        Map<String, String> environment = Map.of("PATH", parent(jdtls), "XDG_CACHE_HOME", workspace.resolve("xdg").toString());

        String dataDirectory = dataDirectory(discovery(true, environment, MACOS, home, workspace.resolve("project")));

        assertEquals(home.resolve("Library").resolve("Caches").resolve("vintelli").resolve("jdtls-data"),
                Path.of(dataDirectory).getParent());
    }

    @Test
    void windowsKeepsTheDataDirectoryUnderLocalAppData() throws IOException {
        Path launcher = executable(directory("bin"), "jdtls.cmd");
        Path localAppData = workspace.resolve("local");
        Map<String, String> environment = Map.of("PATH", parent(launcher), "LOCALAPPDATA", localAppData.toString());

        String dataDirectory = dataDirectory(discovery(true, environment, WINDOWS, workspace.resolve("home"),
                workspace.resolve("project")));

        assertEquals(localAppData.resolve("vintelli").resolve("jdtls-data"), Path.of(dataDirectory).getParent());
    }

    @Test
    void noServerOnThePathIsNotFoundAndTheClientSaysWhatToDoInOneLine() {
        try (LspClient client = new LspClient(unconfigured(),
                discovery(true, Map.of("PATH", workspace.toString()), LINUX))) {
            ProviderHealth health = client.health();

            assertFalse(health.isAvailable());
            assertEquals(NO_SERVER_REASON, health.reason());
            assertEquals(Optional.empty(), client.activeProfileName());
        }
    }

    @Test
    void aDisabledDiscoveryWithoutProfilesGivesTheSameOneLineReason() throws IOException {
        Path jdtls = executable(directory("bin"), "jdtls");
        try (LspClient client = new LspClient(unconfigured(),
                discovery(false, Map.of("PATH", parent(jdtls)), LINUX))) {
            assertEquals(NO_SERVER_REASON, client.health().reason());
            assertEquals(Optional.empty(), client.activeProfileName());
        }
    }

    @Test
    void aDiscoveredServerServesUnderItsNameAndSaysItWasFoundOnThePath() throws IOException {
        Path jdtls = executable(directory("bin"), "jdtls");
        try (LspClient client = new LspClient(unconfigured(),
                discovery(true, Map.of("PATH", parent(jdtls)), LINUX))) {
            assertTrue(client.health().isAvailable());
            assertEquals(Optional.of("jdtls"), client.activeProfileName());
            assertEquals("found on PATH over stdio", client.activeProvenance());
        }
    }

    @Test
    void aConfiguredProfileDisablesDiscoveryEvenWhenJdtlsIsOnThePath() throws IOException {
        Path configured = executable(directory("configured"), "unit-server");
        Path jdtls = executable(directory("bin"), "jdtls");
        LspProfileConfig config = new StaticProfileConfig.StaticConfig(Optional.empty(), Map.of("unit",
                new StaticProfileConfig.StaticProfile(List.of(configured.toString()), Optional.empty(),
                        Optional.empty())));
        try (LspClient client = new LspClient(config, discovery(true, Map.of("PATH", parent(jdtls)), LINUX))) {
            assertEquals(Optional.of("unit"), client.activeProfileName());
            assertEquals("unit over stdio", client.activeProvenance());
        }
    }

    private LanguageServerDiscovery discovery(boolean enabled, Map<String, String> environment, String os) {
        return discovery(enabled, environment, os, workspace.resolve("home"), workspace.resolve("project"));
    }

    private static LanguageServerDiscovery discovery(boolean enabled, Map<String, String> environment, String os,
            Path home, Path project) {
        return new LanguageServerDiscovery(enabled, environment, os, home.toString(), project);
    }

    private static String dataDirectory(LanguageServerDiscovery discovery) {
        return discovery.discover().orElseThrow().profile().args().orElseThrow().get(1);
    }

    private static LspProfileConfig unconfigured() {
        return new StaticProfileConfig.StaticConfig(Optional.empty(), Map.of());
    }

    private Path directory(String name) throws IOException {
        return Files.createDirectories(workspace.resolve(name));
    }

    private static Path executable(Path directory, String name) throws IOException {
        Path file = plainFile(directory, name);
        assertTrue(file.toFile().setExecutable(true, false));
        return file;
    }

    private static Path plainFile(Path directory, String name) throws IOException {
        return Files.writeString(directory.resolve(name), "#!/bin/sh\n");
    }

    private static String parent(Path file) {
        return file.getParent().toString();
    }
}
