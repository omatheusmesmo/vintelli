package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Color.Rgb;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the index column of the palette table in the README against
 * {@link PaletteDowngrade}: every row documents the hex value of a variable
 * for both shipped themes plus the index the shell serves it as when it runs
 * without truecolor, so a reader who compares the table with a 256 color
 * session sees the same numbers. Reading the file instead of a second copy of
 * the table makes a stale row fail here.
 */
class ReadmePaletteTableTest {

    private static final Path README = Path.of("..", "README.md");
    private static final int TABLE_ROWS = 12;

    @Test
    void everyDocumentedIndexIsTheIndexOfTheDowngrade() {
        for (Row row : readmeRows()) {
            assertEquals(indexOf(row.darculaHex()), row.darculaIndex(),
                    "the README documents the darcula index of '" + row.variable() + "'");
            assertEquals(indexOf(row.darkHex()), row.darkIndex(),
                    "the README documents the dark index of '" + row.variable() + "'");
        }
    }

    /**
     * The table documents hex values that a 16 color terminal would serve from
     * its own palette entries; the shell never uses those indices, so the table
     * must not name one.
     */
    @Test
    void noDocumentedIndexComesFromTheTerminalPalette() {
        for (Row row : readmeRows()) {
            assertTrue(row.darculaIndex() >= PaletteDowngrade.LOWEST_INDEX,
                    "the darcula index of '" + row.variable() + "' stays in the fixed palette");
            assertTrue(row.darkIndex() >= PaletteDowngrade.LOWEST_INDEX,
                    "the dark index of '" + row.variable() + "' stays in the fixed palette");
        }
    }

    private static List<Row> readmeRows() {
        List<Row> rows = new ArrayList<>();
        for (String line : lines()) {
            String[] cells = line.split("\\|", -1);
            if (cells.length != 8 || !clean(cells[1]).startsWith("$")) {
                continue;
            }
            rows.add(new Row(clean(cells[1]), clean(cells[2]), Integer.parseInt(cells[3].trim()),
                    clean(cells[4]), Integer.parseInt(cells[5].trim())));
        }
        assertEquals(TABLE_ROWS, rows.size(),
                "the palette table of " + README.toAbsolutePath().normalize() + " has to stay parseable");
        return rows;
    }

    private static List<String> lines() {
        try {
            return Files.readAllLines(README);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read " + README.toAbsolutePath().normalize(), failure);
        }
    }

    private static String clean(String cell) {
        return cell.trim().replace("`", "");
    }

    private static int indexOf(String hex) {
        Rgb rgb = Color.hex(hex).toRgb();
        return PaletteDowngrade.nearestIndex(rgb.r(), rgb.g(), rgb.b());
    }

    private record Row(String variable, String darculaHex, int darculaIndex, String darkHex, int darkIndex) {
    }
}
