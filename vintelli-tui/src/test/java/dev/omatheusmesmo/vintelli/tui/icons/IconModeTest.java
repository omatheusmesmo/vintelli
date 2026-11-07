package dev.omatheusmesmo.vintelli.tui.icons;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IconModeTest {

    private static final Map<String, String> PLAIN_TERMINAL = Map.of("TERM", "xterm-256color", "TERM_PROGRAM", "vscode");

    @TempDir
    Path home;

    @Test
    void detectedTerminalIsOnlyAHintAndExplicitChoiceWins() {
        assertEquals(IconMode.NERD, IconMode.AUTO.resolve(machine(Map.of(), "kitty", true)));
        assertEquals(IconMode.ASCII, IconMode.AUTO.resolve(machine(Map.of(), "alacritty", true)));
        assertEquals(IconMode.ASCII, IconMode.ASCII.resolve(machine(Map.of(), "kitty", true)));
        assertEquals(IconMode.NERD, IconMode.NERD.resolve(machine(Map.of(), "unknown", true)));
    }

    @Test
    void absentOrBlankConfigurationMeansAuto() {
        assertEquals(IconMode.AUTO, IconMode.fromConfig(null));
        assertEquals(IconMode.AUTO, IconMode.fromConfig("  "));
    }

    @Test
    void configurationValuesAreCaseAndWhitespaceInsensitive() {
        assertEquals(IconMode.NERD, IconMode.fromConfig(" NERD "));
        assertEquals(IconMode.ASCII, IconMode.fromConfig("ascii"));
    }

    @Test
    void unknownConfigurationFailsFastNamingPropertyAndAllowedValues() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> IconMode.fromConfig("emoji"));

        assertTrue(error.getMessage().contains("vintelli.icons"));
        assertTrue(error.getMessage().contains("auto, nerd, ascii"));
    }

    @Test
    void autoPicksNerdOnTerminalsThatBundleTheSymbolFont() {
        assertEquals(IconMode.NERD, IconMode.AUTO.resolve(Map.of("KITTY_WINDOW_ID", "1")));
        assertEquals(IconMode.NERD, IconMode.AUTO.resolve(Map.of("KITTY_PID", "4242")));
        assertEquals(IconMode.NERD, IconMode.AUTO.resolve(Map.of("GHOSTTY_RESOURCES_DIR", "/usr/share/ghostty")));
        assertEquals(IconMode.NERD, IconMode.AUTO.resolve(Map.of("WEZTERM_EXECUTABLE", "/usr/bin/wezterm")));
        assertEquals(IconMode.NERD, IconMode.AUTO.resolve(Map.of("TERM_PROGRAM", "WezTerm")));
    }

    @Test
    void autoFallsBackToAsciiOnAnyOtherTerminal() {
        assertEquals(IconMode.ASCII, IconMode.AUTO.resolve(PLAIN_TERMINAL));
        assertEquals(IconMode.ASCII, IconMode.AUTO.resolve(Map.of("KITTY_PID", " ")));
    }

    @Test
    void trustedTerminalSelectsNerdEvenWithoutAnAttachedTerminal() {
        for (String name : new String[] {"kitty", "ghostty", "WezTerm"}) {
            assertEquals(IconMode.NERD, IconMode.AUTO.resolve(machine(Map.of(), name, false)), name);
        }
        assertEquals(IconMode.NERD, IconMode.AUTO.resolve(machine(Map.of("KITTY_WINDOW_ID", "1"), null, false)));
    }

    @Test
    void noAttachedTerminalSelectsAsciiEvenWithANerdFontInstalled() throws IOException {
        installNerdFont();

        assertEquals(IconMode.ASCII, IconMode.AUTO.resolve(machine(PLAIN_TERMINAL, null, false)));
    }

    @Test
    void remoteSessionSelectsAsciiEvenWithANerdFontInstalled() throws IOException {
        installNerdFont();

        for (String variable : new String[] {"SSH_CONNECTION", "SSH_CLIENT", "SSH_TTY"}) {
            assertEquals(IconMode.ASCII, IconMode.AUTO.resolve(machine(Map.of(variable, "1"), null, true)), variable);
        }
        assertEquals(IconMode.NERD, IconMode.AUTO.resolve(machine(Map.of("SSH_TTY", " "), null, true)));
    }

    @Test
    void dumbOrLinuxTermSelectsAsciiEvenWithANerdFontInstalled() throws IOException {
        installNerdFont();

        for (String term : new String[] {"dumb", "DUMB", "linux", "Linux"}) {
            assertEquals(IconMode.ASCII, IconMode.AUTO.resolve(machine(Map.of("TERM", term), null, true)), term);
        }
        assertEquals(IconMode.NERD, IconMode.AUTO.resolve(machine(Map.of("TERM", "xterm-256color"), null, true)));
    }

    @Test
    void installedNerdFontSelectsNerdOnAPlainAttachedTerminal() throws IOException {
        installNerdFont();

        assertEquals(IconMode.NERD, IconMode.AUTO.resolve(machine(PLAIN_TERMINAL, null, true)));
    }

    @Test
    void attachedPlainTerminalWithoutANerdFontSelectsAscii() {
        assertEquals(IconMode.ASCII, IconMode.AUTO.resolve(machine(PLAIN_TERMINAL, null, true)));
    }

    @Test
    void explicitValuesIgnoreTheTerminalAndTheInstalledFonts() throws IOException {
        installNerdFont();

        assertEquals(IconMode.NERD, IconMode.NERD.resolve(machine(Map.of("SSH_TTY", "1"), null, false)));
        assertEquals(IconMode.ASCII, IconMode.ASCII.resolve(machine(PLAIN_TERMINAL, null, true)));
    }

    @Test
    void explicitConfigurationOverridesTerminalDetection() {
        assertEquals(IconMode.NERD, IconMode.NERD.resolve(PLAIN_TERMINAL));
        assertEquals(IconMode.ASCII, IconMode.ASCII.resolve(Map.of("KITTY_WINDOW_ID", "1")));
    }

    @Test
    void sessionFaceFollowsTheResolvedModeWhenBuiltFromEnvironment() {
        assertEquals(IconMode.NERD, FileIcons.of("auto", Map.of("KITTY_WINDOW_ID", "1"), null).mode());
        assertEquals(IconMode.ASCII, FileIcons.of("auto", PLAIN_TERMINAL, null).mode());
        assertEquals(IconMode.NERD, FileIcons.of("nerd", PLAIN_TERMINAL, null).mode());
    }

    /**
     * A Windows machine whose font directories live in the temporary home: Windows
     * reads them from the environment only, so the font walk never reaches the real
     * fonts of the machine running the tests.
     */
    private Map<String, String> isolatedEnv(Map<String, String> env) {
        Map<String, String> machineEnv = new HashMap<>(env);
        machineEnv.putIfAbsent("LOCALAPPDATA", home.resolve("local").toString());
        machineEnv.putIfAbsent("WINDIR", home.resolve("windows").toString());
        return machineEnv;
    }

    private IconMode.Surroundings machine(Map<String, String> env, String terminalName, boolean attached) {
        return new IconMode.Surroundings(isolatedEnv(env), terminalName, attached, "Windows 11", home.toString());
    }

    private void installNerdFont() throws IOException {
        Path fonts = Files.createDirectories(home.resolve("local").resolve("Microsoft").resolve("Windows")
                .resolve("Fonts"));
        Files.createFile(fonts.resolve("JetBrainsMonoNF-Regular.ttf"));
    }
}
