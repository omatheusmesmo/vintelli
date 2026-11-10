package dev.omatheusmesmo.vintelli.tui.icons;

import dev.tamboui.style.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileIconsJavaKindTest {

    private static final FileIcon JAVA = new FileIcon("", "J", Color.hex("#E76F00"));
    private static final FileIcon KOTLIN = new FileIcon("", "K", Color.hex("#7F52FF"));

    private static final String RESOURCE_GLYPH_CLASS = "";
    private static final String RESOURCE_GLYPH_INTERFACE = "";
    private static final String RESOURCE_GLYPH_ENUM = "";
    private static final String RESOURCE_GLYPH_RECORD = "";
    private static final String RESOURCE_GLYPH_ANNOTATION = "";

    private record Expected(JavaTypeKind kind, String glyph, String letter, String color) {
        FileIcon icon() {
            return new FileIcon(glyph, letter, Color.hex(color));
        }
    }

    private static final Expected[] EXPECTED = {
        new Expected(JavaTypeKind.CLASS, RESOURCE_GLYPH_CLASS, "C", "#4A9FE0"),
        new Expected(JavaTypeKind.INTERFACE, RESOURCE_GLYPH_INTERFACE, "I", "#6CBF6C"),
        new Expected(JavaTypeKind.ENUM, RESOURCE_GLYPH_ENUM, "E", "#E8873A"),
        new Expected(JavaTypeKind.RECORD, RESOURCE_GLYPH_RECORD, "R", "#3CB8A8"),
        new Expected(JavaTypeKind.ANNOTATION, RESOURCE_GLYPH_ANNOTATION, "@", "#D6B24A"),
    };

    @TempDir
    Path dir;

    @Test
    void eachKindHasItsOwnGlyphLetterAndColourInBothFaces() {
        FileIcons nerd = FileIcons.of("nerd", Map.of(), null);
        FileIcons ascii = FileIcons.of("ascii", Map.of(), null);

        for (Expected expected : EXPECTED) {
            assertEquals(expected.icon(), nerd.iconFor("Shape.java", expected.kind()), expected.kind().name());
            assertEquals(expected.icon(), ascii.iconFor("Shape.java", expected.kind()), expected.kind().name());
            assertEquals(expected.glyph(), nerd.iconFor("Shape.java", expected.kind()).symbol(IconMode.NERD));
            assertEquals(expected.letter(), ascii.iconFor("Shape.java", expected.kind()).symbol(IconMode.ASCII));
            assertEquals(expected.letter(), ascii.iconFor("Shape.java", expected.kind()).symbol(ascii.mode()));
        }
    }

    @Test
    void theFiveKindsAreDistinguishableInBothFaces() {
        FileIcons nerd = FileIcons.of("nerd", Map.of(), null);
        FileIcons ascii = FileIcons.of("ascii", Map.of(), null);

        Set<String> glyphs = kindSymbols(nerd, IconMode.NERD);
        Set<String> letters = kindSymbols(ascii, IconMode.ASCII);
        Set<Color> colours = Arrays.stream(EXPECTED).map(expected -> expected.icon().color())
                .collect(Collectors.toSet());

        assertEquals(EXPECTED.length, glyphs.size());
        assertEquals(EXPECTED.length, letters.size());
        assertEquals(EXPECTED.length, colours.size());
    }

    @Test
    void unknownKindKeepsTheExtensionIcon() {
        FileIcons icons = FileIcons.of("nerd", Map.of(), null);

        assertEquals(JAVA, icons.iconFor("Shape.java", null));
        assertEquals(JAVA, icons.iconFor("Shape.java"));
    }

    @Test
    void kindOnlyChangesJavaSources() {
        FileIcons icons = FileIcons.of("nerd", Map.of(), null);

        assertEquals(KOTLIN, icons.iconFor("Shape.kt", JavaTypeKind.INTERFACE));
    }

    @Test
    void userNameEntryWinsOverTheKind() throws IOException {
        Path userFile = write("name.Shape.java.glyph=S\nname.Shape.java.ascii=S\n");

        FileIcons icons = FileIcons.of("nerd", Map.of(), userFile);

        assertEquals(new FileIcon("S", "S", null), icons.iconFor("Shape.java", JavaTypeKind.INTERFACE));
        assertEquals(new Expected(JavaTypeKind.INTERFACE, RESOURCE_GLYPH_INTERFACE, "I", "#6CBF6C").icon(),
                icons.iconFor("Other.java", JavaTypeKind.INTERFACE));
    }

    @Test
    void userExtensionEntryWinsOverTheKind() throws IOException {
        Path userFile = write("ext.java.ascii=Jv\n");

        FileIcons icons = FileIcons.of("ascii", Map.of(), userFile);

        assertEquals(new FileIcon("", "Jv", Color.hex("#E76F00")),
                icons.iconFor("Point.java", JavaTypeKind.RECORD));
    }

    @Test
    void userKindEntryReplacesOnlyTheFieldItNames() throws IOException {
        Path userFile = write("type.record.ascii=Rc\n");

        FileIcons icons = FileIcons.of("ascii", Map.of(), userFile);

        assertEquals(new FileIcon(RESOURCE_GLYPH_RECORD, "Rc", Color.hex("#3CB8A8")),
                icons.iconFor("Point.java", JavaTypeKind.RECORD));
        assertEquals("C", icons.iconFor("Shape.java", JavaTypeKind.CLASS).ascii());
    }

    @Test
    void unknownKindNameInTheUserFileFailsFastNamingTheFile() throws IOException {
        Path userFile = write("type.widget.glyph=w\ntype.widget.ascii=w\n");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> FileIcons.of("nerd", Map.of(), userFile));

        assertTrue(error.getMessage().contains("invalid type 'widget'"), error.getMessage());
        assertTrue(error.getMessage().contains(userFile.toString()), error.getMessage());
    }

    @Test
    void kindGlyphsLiveInThePrivateUseAreaAndKindMarkersArePlainAscii() {
        FileIcons icons = FileIcons.of("nerd", Map.of(), null);

        icons.typeEntries().values().forEach(icon -> {
            assertTrue(icon.glyph().codePoints().allMatch(cp -> cp >= 0xE000 && cp <= 0xF8FF), icon.glyph());
            assertTrue(icon.ascii().chars().allMatch(c -> c >= 0x20 && c < 0x7F), icon.ascii());
        });
    }

    private static Set<String> kindSymbols(FileIcons icons, IconMode mode) {
        return Arrays.stream(EXPECTED)
                .map(expected -> icons.iconFor("Shape.java", expected.kind()).symbol(mode))
                .collect(Collectors.toSet());
    }

    private Path write(String content) throws IOException {
        Path file = dir.resolve("file-icons.properties");
        Files.writeString(file, content);
        return file;
    }
}
