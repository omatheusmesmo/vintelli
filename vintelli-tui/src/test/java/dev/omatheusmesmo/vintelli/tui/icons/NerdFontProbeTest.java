package dev.omatheusmesmo.vintelli.tui.icons;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NerdFontProbeTest {

    @TempDir
    Path root;

    @Test
    void nerdFontInNestedDirectoryWithinTheDepthLimitIsFound() throws IOException {
        touch(root.resolve("fonts").resolve("a").resolve("b").resolve("c"), "HackNerdFont-Regular.ttf");

        assertTrue(NerdFontProbe.containsNerdFont(List.of(root.resolve("fonts"))));
    }

    @Test
    void nerdFontBeyondTheDepthLimitIsNotFound() throws IOException {
        touch(root.resolve("fonts").resolve("a").resolve("b").resolve("c").resolve("d"), "HackNerdFont-Regular.ttf");

        assertFalse(NerdFontProbe.containsNerdFont(List.of(root.resolve("fonts"))));
    }

    @Test
    void nerdFontShortFormsAreMatchedByName() throws IOException {
        String[] names = {"JetBrainsMonoNF-Regular.ttf", "HackNFM-Bold.ttf", "CaskaydiaCoveNFP.ttf"};
        for (int i = 0; i < names.length; i++) {
            Path directory = root.resolve("short" + i);
            touch(directory, names[i]);

            assertTrue(NerdFontProbe.containsNerdFont(List.of(directory)), names[i]);
        }
    }

    @Test
    void nerdFontNameMarkersMatchOnAnyFontExtension() throws IOException {
        String[] names = {"Iosevka Nerd Font.ttc", "fira-nerd-font.woff2", "SymbolsNerdFont-Regular.OTF"};
        for (int i = 0; i < names.length; i++) {
            Path directory = root.resolve("markers" + i);
            touch(directory, names[i]);

            assertTrue(NerdFontProbe.containsNerdFont(List.of(directory)), names[i]);
        }
    }

    @Test
    void falseFriendsAreNotNerdFonts() throws IOException {
        Path directory = root.resolve("friends");
        touch(directory, "Confluence-Regular.ttf");
        touch(directory, "inf.ttf");
        touch(directory, "notes-nerdfont.txt");
        touch(directory, "HackNerdFont-Regular.pdf");

        assertFalse(NerdFontProbe.containsNerdFont(List.of(directory)));
    }

    @Test
    void missingDirectoryMeansNotFound() {
        assertFalse(NerdFontProbe.containsNerdFont(List.of(root.resolve("absent"))));
    }

    @Test
    void unreadableDirectoryMeansNotFoundAndDoesNotHideOtherDirectories() throws IOException {
        Path locked = root.resolve("locked");
        touch(locked, "HackNerdFont-Regular.ttf");
        Path readable = root.resolve("readable");
        touch(readable, "HackNerdFont-Regular.ttf");

        setReadable(locked, false);
        try {
            assumeFalse(Files.isReadable(locked));

            assertFalse(NerdFontProbe.containsNerdFont(List.of(locked)));
            assertTrue(NerdFontProbe.containsNerdFont(List.of(locked, readable)));
        } finally {
            setReadable(locked, true);
        }
    }

    @Test
    void unreadableSubdirectoryDuringTheWalkMeansNotFound() throws IOException {
        Path fonts = root.resolve("fonts");
        Path locked = fonts.resolve("locked");
        touch(locked, "HackNerdFont-Regular.ttf");

        setReadable(locked, false);
        try {
            assumeFalse(Files.isReadable(locked));

            assertFalse(NerdFontProbe.containsNerdFont(List.of(fonts)));
        } finally {
            setReadable(locked, true);
        }
    }

    @Test
    void installedFindsAFontInTheXdgDataHome() throws IOException {
        Path data = root.resolve("data");
        touch(data.resolve("fonts"), "JetBrainsMonoNF-Regular.ttf");

        assertTrue(NerdFontProbe.installed(Map.of("XDG_DATA_HOME", data.toString()), "Linux",
                root.resolve("home").toString()));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "XDG directories are POSIX paths separated by colons")
    void linuxDirectoriesFollowXdgAndTheSystemLocations() {
        Path xdg = root.resolve("xdg");
        Path share = root.resolve("share");
        Path home = root.resolve("home");
        Map<String, String> env = Map.of("XDG_DATA_HOME", xdg.toString(),
                "XDG_DATA_DIRS", share + ":relative:/usr/share");

        assertEquals(List.of(xdg.resolve("fonts"), home.resolve(".local").resolve("share").resolve("fonts"),
                home.resolve(".fonts"), share.resolve("fonts"), Path.of("/usr/share/fonts"),
                Path.of("/usr/local/share/fonts")),
                NerdFontProbe.fontDirectories(env, "Linux", home.toString()));
    }

    @Test
    void macOsDirectoriesAreTheUserAndSystemFontFolders() {
        Path home = root.resolve("home");

        assertEquals(List.of(home.resolve("Library").resolve("Fonts"), Path.of("/Library/Fonts")),
                NerdFontProbe.fontDirectories(Map.of(), "Mac OS X", home.toString()));
    }

    @Test
    void windowsDirectoriesComeFromTheUserAndSystemFontLocations() {
        Map<String, String> env = Map.of("LOCALAPPDATA", "C:\\Users\\m\\AppData\\Local", "WINDIR", "C:\\Windows");

        assertEquals(List.of(Path.of("C:\\Users\\m\\AppData\\Local", "Microsoft", "Windows", "Fonts"),
                Path.of("C:\\Windows", "Fonts")),
                NerdFontProbe.fontDirectories(env, "Windows 11", "C:\\Users\\m"));
    }

    private static void touch(Path directory, String name) throws IOException {
        Files.createDirectories(directory);
        Files.createFile(directory.resolve(name));
    }

    private static void setReadable(Path directory, boolean readable) {
        boolean changed = new File(directory.toString()).setReadable(readable, false);
        assumeTrue(changed || readable, "the platform cannot change directory permissions");
    }
}
