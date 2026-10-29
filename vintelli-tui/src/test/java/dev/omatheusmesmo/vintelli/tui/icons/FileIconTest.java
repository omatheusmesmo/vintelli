package dev.omatheusmesmo.vintelli.tui.icons;

import dev.tamboui.style.Color;
import dev.tamboui.text.Span;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileIconTest {

    private static final FileIcon JAVA = new FileIcon("", "J", Color.RED);

    @Test
    void asciiModeRendersTheMarkerAndNerdModeTheGlyph() {
        assertEquals("J", JAVA.symbol(IconMode.ASCII));
        assertEquals("", JAVA.symbol(IconMode.NERD));
    }

    @Test
    void spanCarriesTheSymbolOfTheModeAndTheMappingColor() {
        Span ascii = JAVA.span(IconMode.ASCII);

        assertEquals("J", ascii.content());
        assertEquals(Color.RED, ascii.style().fg().orElseThrow());
    }

    @Test
    void spanOfAnIconWithoutColorIsUnstyled() {
        Span span = new FileIcon("", "J", null).span(IconMode.NERD);

        assertEquals(true, span.style().fg().isEmpty());
    }

    @Test
    void iconWithoutGlyphOrAsciiMarkerIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FileIcon("", "J", null));
        assertThrows(IllegalArgumentException.class, () -> new FileIcon("", null, null));
    }

    @Test
    void everyBuiltInGlyphLivesInThePrivateUseAreaAndEveryMarkerIsPlainAscii() {
        FileIcons icons = FileIcons.of("nerd", Map.of(), null);

        icons.extensionEntries().values().forEach(icon -> {
            assertEquals(true, icon.glyph().codePoints().allMatch(cp -> cp >= 0xE000 && cp <= 0xF8FF), icon.glyph());
            assertEquals(true, icon.ascii().chars().allMatch(c -> c >= 0x20 && c < 0x7F), icon.ascii());
        });
    }
}
