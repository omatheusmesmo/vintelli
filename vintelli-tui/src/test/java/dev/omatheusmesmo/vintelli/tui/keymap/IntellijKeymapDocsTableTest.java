package dev.omatheusmesmo.vintelli.tui.keymap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the IntelliJ comparison table of docs/keymap.md against the CSV the
 * keymap reads: the same rows in the same order, with the same chords, status
 * and reason. A row changed in one place and not the other fails here.
 */
class IntellijKeymapDocsTableTest {

    private static final Path DOCS = Path.of("..", "docs", "keymap.md");
    private static final String HEADING = "## IntelliJ comparison table";
    private static final List<String> HEADER = List.of("Action ID", "Keys (active)", "IntelliJ", "Status", "Reason");
    private static final String EMPTY = "-";

    @Test
    void theDocumentedTableStatesEveryRowOfTheComparisonTable() throws IOException {
        List<String> documented = documentedRows();
        List<KeymapTable.Row> rows = KeymapTable.load().rows();

        assertEquals(rows.size(), documented.size(), "docs/keymap.md states " + documented.size()
                + " rows but " + KeymapTable.RESOURCE + " has " + rows.size());
        for (int index = 0; index < rows.size(); index++) {
            assertEquals(expectedCells(rows.get(index)), cells(documented.get(index)),
                    "docs/keymap.md row " + (index + 1) + " differs from " + KeymapTable.RESOURCE);
        }
    }

    private static List<String> expectedCells(KeymapTable.Row row) {
        return List.of(row.actionId(), orDash(String.join(" | ", row.activeChords())),
                orDash(String.join(" | ", row.intellijChords())), row.status().wireName(), row.reason());
    }

    private static String orDash(String value) {
        return value.isEmpty() ? EMPTY : value;
    }

    private static List<String> cells(String line) {
        String trimmed = line.trim();
        String inner = trimmed.substring(1, trimmed.length() - 1);
        return Arrays.stream(inner.split("(?<!\\\\)\\|", -1))
                .map(cell -> cell.trim().replace("\\|", "|"))
                .toList();
    }

    private static List<String> documentedRows() throws IOException {
        List<String> lines = Files.readAllLines(DOCS);
        int heading = lines.indexOf(HEADING);
        assertTrue(heading >= 0, "docs/keymap.md has to state the comparison table under '" + HEADING + "'");
        int header = -1;
        for (int index = heading + 1; index < lines.size(); index++) {
            if (lines.get(index).startsWith("## ")) {
                break;
            }
            if (lines.get(index).startsWith("|") && cells(lines.get(index)).equals(HEADER)) {
                header = index;
                break;
            }
        }
        assertTrue(header >= 0, "the comparison table under '" + HEADING + "' has to start with the columns " + HEADER);
        List<String> rows = new ArrayList<>();
        for (int index = header + 2; index < lines.size() && lines.get(index).startsWith("|"); index++) {
            rows.add(lines.get(index));
        }
        return rows;
    }
}
