package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapProfile;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapTable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the IntelliJ reference column of the keymap table in docs/keymap.md against
 * the action catalog of the shell: the command palette and the help overlay
 * show the catalog value, so a reader who compares the table with the running
 * application sees the same reference. Reading the file instead of a second
 * copy of the table makes a stale row fail here.
 */
class ReadmeKeymapTableTest {

    private static final Path README = Path.of("..", "docs", "keymap.md");
    private static final int TABLE_ROWS = 9;
    private static final int COMPARABLE_ROWS = 4;
    private static final String NO_REFERENCE = "-";

    @Test
    void everyDocumentedIntellijReferenceIsTheOneInTheCatalog() {
        Keymap keymap = Keymap.ofProfile(KeymapProfile.DEFAULT, List.of());
        Map<String, String> documented = new LinkedHashMap<>();
        for (Row row : readmeRows()) {
            if (!isSingleActionId(row.action())) {
                continue;
            }
            documented.put(row.action(), row.reference());
        }
        assertEquals(COMPARABLE_ROWS, documented.size(),
                "the keymap table of " + README.toAbsolutePath().normalize()
                        + " has to document single actions the catalog can be compared with");

        for (Map.Entry<String, String> entry : documented.entrySet()) {
            String expected = NO_REFERENCE.equals(entry.getValue()) ? "" : entry.getValue();
            assertEquals(expected, intellijReference(keymap, entry.getKey()),
                    "docs/keymap.md documents the IntelliJ reference of '" + entry.getKey() + "'");
        }
    }

    /**
     * A row that documents several actions at once or derives its id from the
     * tool window list cannot be compared with one catalog entry, so only the
     * rows naming a single action id are read.
     */
    private static boolean isSingleActionId(String action) {
        return action.matches("[A-Za-z][A-Za-z0-9_]*");
    }

    private static String intellijReference(Keymap keymap, String actionId) {
        if (keymap.actions().stream().noneMatch(action -> action.id().equals(actionId))) {
            throw new AssertionError("docs/keymap.md documents '" + actionId + "', which is not in the action catalog");
        }
        KeymapTable.Row row = KeymapTable.load().rowOf(actionId);
        if (row == null) {
            throw new AssertionError("docs/keymap.md documents '" + actionId + "', which has no row in "
                    + KeymapTable.RESOURCE);
        }
        return String.join(" | ", row.intellijChords());
    }

    private static List<Row> readmeRows() {
        List<String> lines = lines();
        int header = headerLine(lines);
        List<Row> rows = new ArrayList<>();
        for (int index = header + 2; index < lines.size(); index++) {
            String[] cells = lines.get(index).split("\\|", -1);
            if (cells.length != 5) {
                break;
            }
            rows.add(new Row(clean(cells[1]), clean(cells[3])));
        }
        assertEquals(TABLE_ROWS, rows.size(),
                "the keymap table of " + README.toAbsolutePath().normalize() + " has to stay parseable");
        return rows;
    }

    /**
     * The table is read from its own header, not from the first three column
     * table of the file, so other tables with the same shape stay untouched.
     */
    private static int headerLine(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            String[] cells = lines.get(index).split("\\|", -1);
            if (cells.length == 5 && clean(cells[1]).equals("Action")
                    && clean(cells[2]).equals("Keys (active)")
                    && clean(cells[3]).equals("IntelliJ reference")) {
                return index;
            }
        }
        throw new AssertionError("docs/keymap.md has to document the keymap table with its three columns");
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

    private record Row(String action, String reference) {
    }
}
