package dev.omatheusmesmo.vintelli.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.web.AcpProbeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AcpAgentCatalogTest {

    @TempDir
    Path directory;

    @Test
    void discoversExecutableWithoutStartingItAndKeepsArgumentsSeparate() throws Exception {
        Path executable = Files.writeString(directory.resolve("agent"), "must never execute during discovery");
        assertTrue(executable.toFile().setExecutable(true));
        AcpAgentCatalog catalog = new AcpAgentCatalog(config(true, "chosen", Map.of(
                "missing", entry("Missing", "absent", List.of()),
                "chosen", entry("Chosen", "agent", List.of("acp", "one argument")))), probe(false, ""),
                Map.of("PATH", directory.toString()), false);

        assertEquals("chosen", catalog.agents().getFirst().id());
        assertTrue(catalog.agents().getFirst().available());
        assertEquals(List.of("acp", "one argument"), catalog.require("chosen").args());
        assertEquals("Executable not found: absent", catalog.agents().getLast().reason());
        assertThrows(IllegalStateException.class, () -> catalog.require("missing"));
        assertThrows(IllegalArgumentException.class, () -> catalog.require("unknown"));
    }

    @Test
    void disabledAgentsRemainVisibleAndCannotStart() {
        AcpAgentCatalog catalog = new AcpAgentCatalog(config(false, "chosen", Map.of(
                "chosen", entry("Chosen", "agent", List.of()))), probe(false, ""), Map.of(), false);
        assertFalse(catalog.agents().getFirst().available());
        assertTrue(catalog.agents().getFirst().reason().contains("vintelli.acp.enabled"));
        assertThrows(IllegalStateException.class, () -> catalog.require("chosen"));
    }

    @Test
    void legacyProbeCommandRemainsTheDefaultWithoutEnablingOtherAgents() {
        String binary = System.getProperty("os.name", "").toLowerCase().contains("windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", binary).toString();
        AcpAgentCatalog catalog = new AcpAgentCatalog(config(false, "opencode", Map.of(
                "opencode", entry("OpenCode", "opencode", List.of("acp")))), probe(true, java), Map.of(), false);
        assertEquals("configured", catalog.defaultAgent());
        assertTrue(catalog.agents().getFirst().available());
        assertFalse(catalog.agents().getLast().available());
        assertEquals(java, catalog.require("configured").command());
    }

    @Test
    void windowsPathExtensionsAndExplicitPathsAreRecognized() throws Exception {
        Path adapter = Files.writeString(directory.resolve("adapter.cmd"), "@exit /b 0");
        assertTrue(AcpAgentCatalog.executable("adapter", Map.of("PATH", directory.toString(), "PATHEXT", ".EXE;.CMD"), true));
        assertTrue(AcpAgentCatalog.executable(adapter.toString(), Map.of(), true));
        assertTrue(AcpAgentCatalog.executable(directory.resolve("adapter").toString(), Map.of(), true));
        assertFalse(AcpAgentCatalog.executable(directory.toString(), Map.of(), true));
        assertFalse(AcpAgentCatalog.executable("bad\u0000path", Map.of(), false));
    }

    private static AcpAgentsConfig config(boolean enabled, String defaultAgent, Map<String, AcpAgentsConfig.Agent> agents) {
        return new AcpAgentsConfig() {
            public boolean enabled() { return enabled; }
            public String defaultAgent() { return defaultAgent; }
            public Map<String, Agent> agents() { return agents; }
        };
    }

    private static AcpAgentsConfig.Agent entry(String name, String command, List<String> args) {
        return new AcpAgentsConfig.Agent() {
            public String name() { return name; }
            public String command() { return command; }
            public Optional<List<String>> args() { return Optional.of(args); }
        };
    }

    private static AcpProbeConfig probe(boolean enabled, String command) {
        return new AcpProbeConfig() {
            public Optional<Boolean> enabled() { return Optional.of(enabled); }
            public Optional<String> agentCommand() { return Optional.of(command); }
            public Optional<String> agentArgs() { return Optional.empty(); }
        };
    }
}
