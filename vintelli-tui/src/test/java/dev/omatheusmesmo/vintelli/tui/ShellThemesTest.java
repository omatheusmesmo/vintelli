package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellThemesTest {

    @TempDir
    Path temp;

    @Test
    void activatesTheDefaultThemeAndShipsBothFlavors() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);

        assertEquals(ShellThemes.DEFAULT_THEME, themes.activeTheme());
        assertEquals(List.of("darcula", "dark"), themes.availableThemeNames());
        assertEquals("dark", themes.engine().getActiveStylesheet().orElse(null));
    }

    @Test
    void unknownDefaultThemeFailsFastWithAvailableNames() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ShellThemes("night", (Path) null));

        assertTrue(failure.getMessage().contains("unknown shell theme 'night'"), failure.getMessage());
        assertTrue(failure.getMessage().contains("darcula, dark"), failure.getMessage());
    }

    @Test
    void switchThemeActivatesAnotherStylesheetWithoutRestart() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);

        themes.switchTheme("darcula");

        assertEquals("darcula", themes.activeTheme());
        assertEquals("darcula", themes.engine().getActiveStylesheet().orElse(null));
        assertEquals(themes.variablesOf("darcula").get("editor-keyword").toUpperCase(), themes.editorPalette().keyword().toUpperCase());
    }

    @Test
    void unknownRuntimeThemeFailsFastWithAvailableNames() throws IOException {
        ShellThemes themes = new ShellThemes("dark", (Path) null);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> themes.switchTheme("night"));

        assertTrue(failure.getMessage().contains("unknown shell theme 'night'"), failure.getMessage());
        assertTrue(failure.getMessage().contains("darcula, dark"), failure.getMessage());
    }

    @Test
    void userFileWithoutDirectivePatchesEveryShippedTheme() throws IOException {
        ShellThemes themes = new ShellThemes("dark", writeUserFile("themes.tcss", ".menu-entry { color: #12345F; }\n"));

        for (String theme : themes.availableThemeNames()) {
            themes.switchTheme(theme);
            Style entry = themes.resolver("menu-entry").toStyle();
            assertEquals(Color.hex("#12345F"), entry.fg().orElse(null),
                    "the user patch must restyle the menu entry in '" + theme + "'");
        }
        assertEquals(List.of("darcula", "dark"), themes.availableThemeNames(),
                "a patch file adds no theme name");
        assertTrue(themes.resolver("tool-window").borderColor().isPresent(),
                "the shipped tool window rule survives the patch");
    }
    @Test
    void userFileWithDirectiveExtendsAShippedTheme() throws IOException {
        Path userFile = writeUserFile("midnight.tcss",
                "@theme midnight extends dark\n$panel: #101010;\ntool-window { color: $panel; }\n");
        ShellThemes themes = new ShellThemes("midnight", userFile);

        assertEquals(List.of("darcula", "dark", "midnight"), themes.availableThemeNames());
        assertEquals("midnight", themes.activeTheme());
        assertEquals("#101010", themes.variablesOf("midnight").get("panel"));
        assertTrue(themes.variablesOf("midnight").containsKey("editor-bg"),
                "an extended theme inherits the palette the base theme declares");
    }

    @Test
    void directiveNamingAShippedThemeReplacesIt() throws IOException {
        Path userFile = writeUserFile("overrides.tcss", "@theme dark extends darcula\n$panel: #101010;\n");
        ShellThemes themes = new ShellThemes("dark", userFile);

        assertEquals(List.of("darcula", "dark"), themes.availableThemeNames(), "the replacement keeps the name");
        assertEquals("#101010", themes.variablesOf("dark").get("panel"));
        assertEquals(themes.variablesOf("darcula").get("editor-bg"), themes.variablesOf("dark").get("editor-bg"),
                "the shipped editor palette survives under the replaced name through the extends base");
    }

    @Test
    void incompleteUserThemeFailsFastAtLoadWithTheMissingNames() throws IOException {
        Path userFile = writeUserFile("night.tcss", "@theme night\n$panel: #101010;\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ShellThemes("night", userFile));

        assertTrue(failure.getMessage().contains("user theme file '" + userFile + "'"), failure.getMessage());
        assertTrue(failure.getMessage().contains("does not declare variables"), failure.getMessage());
        assertTrue(failure.getMessage().contains("editor-bg"), failure.getMessage());
    }

    @Test
    void extendsWithAnUnknownBaseFailsFast() throws IOException {
        Path userFile = writeUserFile("night.tcss", "@theme night extends gruvbox\n$panel: #101010;\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ShellThemes("night", userFile));

        assertTrue(failure.getMessage().contains("extends unknown theme 'gruvbox'"), failure.getMessage());
        assertTrue(failure.getMessage().contains("darcula, dark"), failure.getMessage());
    }

    @Test
    void brokenUserFileFailsFastWithTheFileNameAndThePosition() throws IOException {
        Path userFile = writeUserFile("themes.tcss", "menu-entry { color: #101010;\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ShellThemes("dark", userFile));

        assertTrue(failure.getMessage().contains(userFile.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains("is invalid"), failure.getMessage());
        assertTrue(failure.getMessage().contains("line"), failure.getMessage());
    }

    @Test
    void moreThanOneDirectiveFailsFastWithTheFileName() throws IOException {
        Path userFile = writeUserFile("themes.tcss", "@theme night\n$panel: #101010;\n@theme midday\n$editor-bg: #202020;\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ShellThemes("dark", userFile));

        assertTrue(failure.getMessage().contains(userFile.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains("@theme directives"), failure.getMessage());
    }

    @Test
    void configDirectoryPropertyLoadsTheUserThemeFileWhenPresent() throws IOException {
        Path configured = temp.resolve("configured-root").resolve("vintelli");
        Files.createDirectories(configured);
        Files.writeString(configured.resolve("themes.tcss"), ".menu-entry { color: #12345F; }\n");

        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, Optional.empty(), Optional.of(temp.resolve("configured-root").toString()));

        assertEquals(Color.hex("#12345F"), themes.resolver("menu-entry").toStyle().fg().orElse(null),
                "the CDI shell picks the patch that the config directory carries");
        assertEquals(List.of("darcula", "dark"), themes.availableThemeNames(),
                "a patch file adds no theme name");
    }

    @Test
    void configDirectoryWithoutAUserFileLoadsOnlyTheShippedThemes() throws IOException {
        Path overridden = temp.resolve("empty-root").resolve("vintelli");
        Files.createDirectories(overridden);

        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, Optional.empty(), Optional.of(temp.resolve("empty-root").toString()));

        assertEquals(ShellThemes.DEFAULT_THEME, themes.activeTheme());
        assertEquals(Color.hex(themes.variablesOf("dark").get("chrome-fg")),
                themes.resolver("menu-entry").toStyle().fg().orElse(null),
                "without a user file the shipped palette rules still resolve");
    }

    @Test
    void configuredUserThemeFileWinsOverTheConfigDirectory() throws IOException {
        Path configured = temp.resolve("configured-root").resolve("vintelli");
        Files.createDirectories(configured);
        Files.writeString(configured.resolve("themes.tcss"), ".menu-entry { color: #12345F; }\n");
        Path explicit = writeUserFile("elsewhere.tcss", ".menu-entry-active { color: #0099AA; }\n");

        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, Optional.of(explicit.toString()),
                Optional.of(temp.resolve("configured-root").toString()));

        assertEquals(Color.hex(themes.variablesOf("dark").get("chrome-fg")),
                themes.resolver("menu-entry").toStyle().fg().orElse(null),
                "the explicit file opts out of the config directory lookup");
        assertEquals(Color.hex("#0099AA"), themes.resolver("menu-entry-active").toStyle().fg().orElse(null),
                "only the explicitly configured user file is applied");
    }

    @Test
    void userVariableOverridesReachTheRulesThatReferenceThem() throws IOException {
        ShellThemes patched = new ShellThemes("dark", writeUserFile("patch.tcss", "$chrome-fg: #123456;\n"));
        ShellThemes derived = new ShellThemes("midnight",
                writeUserFile("midnight.tcss", "@theme midnight extends dark\n$chrome-fg: #123456;\n"));

        assertEquals(Color.hex("#123456"), patched.resolver("menu-entry").toStyle().fg().orElse(null),
                "a patch variable restyles the shipped rule that reads it");
        assertEquals(Color.hex("#123456"), derived.resolver("menu-entry").toStyle().fg().orElse(null),
                "a derived theme variable restyles the inherited rule that reads it");
        assertEquals(Color.hex(derived.variablesOf("dark").get("border")),
                derived.resolver("tool-window").borderColor().orElse(null),
                "the derived theme keeps the rules of its base theme");
    }

    @Test
    void replacedShippedThemeIsStyledByTheReplacementInTheEngine() throws IOException {
        Path userFile = writeUserFile("replace.tcss", "@theme dark extends darcula\n");
        ShellThemes themes = new ShellThemes("dark", userFile);

        assertEquals(Color.hex(themes.variablesOf("darcula").get("chrome-fg")),
                themes.resolver("menu-entry").toStyle().fg().orElse(null),
                "the engine styles the replaced name from the replacement, not from the shipped sheet");
        assertEquals(List.of("darcula", "dark"), themes.availableThemeNames());
    }

    private Path writeUserFile(String name, String content) throws IOException {
        Path file = temp.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
