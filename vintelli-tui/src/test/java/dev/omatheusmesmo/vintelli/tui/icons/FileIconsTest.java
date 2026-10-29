package dev.omatheusmesmo.vintelli.tui.icons;

import dev.tamboui.style.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileIconsTest {

    private static final FileIcon DEFAULT_FILE = new FileIcon("\uF15B", "*", Color.GRAY);
    private static final FileIcon DEFAULT_FOLDER = new FileIcon("\uE5FF", "/", Color.LIGHT_BLUE);
    private static final FileIcon POM = new FileIcon("\uE82C", "M", Color.GREEN);
    private static final FileIcon JAVA = new FileIcon("\uE738", "J", Color.RED);
    private static final FileIcon GIT_FOLDER = new FileIcon("\uE5FB", "/", Color.LIGHT_BLUE);

    @Test
    void buildInResourceLoadsWithoutUserFile() {
        FileIcons icons = FileIcons.of("nerd", Map.of(), null);

        assertEquals(DEFAULT_FILE, icons.iconFor("notes.unknownext"));
    }

    @Test
    void exactFileNameWinsOverItsExtension() {
        FileIcons icons = FileIcons.of("nerd", Map.of(), null);

        assertEquals(POM, icons.iconFor("vintelli/pom.xml"));
    }

    @Test
    void extensionMatchesCaseInsensitively() {
        FileIcons icons = FileIcons.of("nerd", Map.of(), null);

        assertEquals(JAVA, icons.iconFor("Main.java"));
        assertEquals(JAVA, icons.iconFor("Main.JAVA"));
    }

    @Test
    void unknownExtensionOrNoExtensionFallsBackToDefaultFile() {
        FileIcons icons = FileIcons.of("nerd", Map.of(), null);

        assertEquals(DEFAULT_FILE, icons.iconFor("archive.zzzunknown"));
        assertEquals(DEFAULT_FILE, icons.iconFor("LICENSEX"));
        assertEquals(DEFAULT_FILE, icons.iconFor("trailing."));
    }

    @Test
    void dotfileIsNotSplitIntoAnExtension() {
        FileIcons icons = FileIcons.of("nerd", Map.of(), null);

        assertEquals(DEFAULT_FILE, icons.iconFor(".java"));
    }

    @Test
    void namesAreTakenFromTheLastSegmentOfUnixAndWindowsPaths() {
        FileIcons icons = FileIcons.of("nerd", Map.of(), null);

        assertEquals(JAVA, icons.iconFor("vintelli/src/Main.java"));
        assertEquals(POM, icons.iconFor("C:\\projects\\pom.xml"));
    }

    @Test
    void folderMatchesExactNameBeforeDefaultFolder() {
        FileIcons icons = FileIcons.of("nerd", Map.of(), null);

        assertEquals(GIT_FOLDER, icons.folderIconFor(".git"));
        assertEquals(GIT_FOLDER, icons.folderIconFor("repo/.git"));
        assertEquals(DEFAULT_FOLDER, icons.folderIconFor("src"));
    }

    @Test
    void folderIgnoresExtensionRules() {
        FileIcons icons = FileIcons.of("nerd", Map.of(), null);

        assertEquals(DEFAULT_FOLDER, icons.folderIconFor("classes.java"));
    }

    @Test
    void userFileOverridesOnlyTheFieldItNames(@TempDir Path dir) throws IOException {
        Path userFile = write(dir, "ext.java.ascii=Jv\n");

        FileIcons icons = FileIcons.of("ascii", Map.of(), userFile);

        assertEquals(new FileIcon("\uE738", "Jv", Color.RED), icons.iconFor("Main.java"));
    }

    @Test
    void userFileAddsAMappingForAnExtensionTheResourceDoesNotKnow(@TempDir Path dir) throws IOException {
        Path userFile = write(dir, "ext.zig.glyph=Z\next.zig.ascii=Z\next.zig.color=cyan\n");

        FileIcons icons = FileIcons.of("nerd", Map.of(), userFile);

        assertEquals(new FileIcon("Z", "Z", Color.CYAN), icons.iconFor("build.zig"));
    }

    @Test
    void userFileWithUnknownKeyGrammarFailsFastNamingTheFile(@TempDir Path dir) throws IOException {
        Path userFile = write(dir, "bogus=1\n");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> FileIcons.of("nerd", Map.of(), userFile));

        assertTrue(error.getMessage().contains("invalid icon mapping key 'bogus'"));
        assertTrue(error.getMessage().contains(userFile.toString()));
    }

    @Test
    void userFileWithUnknownColorNameFailsFast(@TempDir Path dir) throws IOException {
        Path userFile = write(dir, "ext.java.color=ultraviolet\n");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> FileIcons.of("nerd", Map.of(), userFile));

        assertTrue(error.getMessage().contains("unknown color name 'ultraviolet'"));
    }

    @Test
    void userFileOverridesTheNameEntryWhileTheExtensionStaysUntouched(@TempDir Path dir) throws IOException {
        Path userFile = write(dir, "name.pom.xml.glyph=X\nname.pom.xml.ascii=X\n");

        FileIcons icons = FileIcons.of("nerd", Map.of(), userFile);

        assertEquals(new FileIcon("X", "X", Color.GREEN), icons.iconFor("pom.xml"));
        assertEquals(JAVA, icons.iconFor("Main.java"));
    }

    @Test
    void userFileOverridesTheDefaultFileIcon(@TempDir Path dir) throws IOException {
        Path userFile = write(dir, "default.file.ascii=?\n");

        FileIcons icons = FileIcons.of("ascii", Map.of(), userFile);

        assertEquals(new FileIcon("\uF15B", "?", Color.GRAY), icons.iconFor("notes.unknownext"));
    }

    @Test
    void userKeyWrittenInUpperCaseStillOverridesTheBuiltInEntry(@TempDir Path dir) throws IOException {
        Path userFile = write(dir, "ext.JAVA.ascii=Jv\n");

        FileIcons icons = FileIcons.of("ascii", Map.of(), userFile);

        assertEquals("Jv", icons.iconFor("Main.java").ascii());
    }

    @Test
    void newUserEntryMissingItsAsciiMarkerFailsFast(@TempDir Path dir) throws IOException {
        Path userFile = write(dir, "ext.zig.glyph=Z\n");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> FileIcons.of("nerd", Map.of(), userFile));

        assertTrue(error.getMessage().contains("missing 'ascii' value"));
        assertTrue(error.getMessage().contains(userFile.toString()));
    }

    @Test
    void userFileThatDoesNotExistYetLeavesTheBuiltInMapping(@TempDir Path dir) {
        FileIcons icons = FileIcons.of("nerd", Map.of(), dir.resolve("absent.properties"));

        assertEquals(JAVA, icons.iconFor("Main.java"));
    }

    @Test
    void sessionLoadWithoutAUserConfigDirectoryDoesNotFail(@TempDir Path home) {
        String previousHome = System.getProperty("user.home");
        String previousFile = System.getProperty(FileIcons.USER_FILE_PROPERTY);
        System.clearProperty(FileIcons.USER_FILE_PROPERTY);
        System.setProperty("user.home", home.toString());
        try {
            assertEquals(JAVA, FileIcons.resolve().iconFor("Main.java"));
        } finally {
            System.setProperty("user.home", previousHome);
            if (previousFile != null) {
                System.setProperty(FileIcons.USER_FILE_PROPERTY, previousFile);
            }
        }
    }

    @Test
    void issueListedFileTypesHaveAnIconOfTheirOwn() {
        FileIcons icons = FileIcons.of("nerd", Map.of(), null);

        for (String name : new String[] {"A.java", "A.kt", "A.xml", "a.properties", "a.md", "pom.xml",
                "build.gradle", "build.gradle.kts", "Makefile", "Dockerfile"}) {
            assertNotEquals(DEFAULT_FILE, icons.iconFor(name), name);
        }
        assertNotEquals(DEFAULT_FOLDER, icons.folderIconFor(".github"));
    }

    private static Path write(Path dir, String content) throws IOException {
        Path file = dir.resolve("file-icons.properties");
        Files.writeString(file, content);
        return file;
    }
}
