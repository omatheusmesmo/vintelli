package dev.omatheusmesmo.vintelli.tui.icons;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileIconWidthTest {

    @Test
    void everyBuiltInMappingReservesTheSameCellWidthInBothFaces() {
        FileIcons icons = FileIcons.of("nerd", Map.of(), null);

        icons.nameEntries().values().forEach(this::assertSameWidthInBothFaces);
        icons.extensionEntries().values().forEach(this::assertSameWidthInBothFaces);
    }

    @Test
    void asciiMarkersOccupyOneCell() {
        FileIcons icons = FileIcons.of("nerd", Map.of(), null);

        icons.extensionEntries().values().forEach(icon ->
                assertEquals(1, icon.symbolWidth(IconMode.ASCII), icon.ascii()));
    }

    @Test
    void doubleWidthGlyphsReserveTwoCells() {
        FileIcon wide = new FileIcon("中", "W", null);

        assertEquals(2, wide.symbolWidth(IconMode.NERD));
        assertEquals(1, wide.symbolWidth(IconMode.ASCII));
    }

    private void assertSameWidthInBothFaces(FileIcon icon) {
        assertEquals(icon.symbolWidth(IconMode.ASCII), icon.symbolWidth(IconMode.NERD), icon.glyph());
    }
}
