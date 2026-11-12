package dev.omatheusmesmo.vintelli.tui.keymap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The table that pins the keymap catalog against IntelliJ's default keymap:
 * one row per catalog action, with the chords the {@code intellij} profile
 * binds (empty when unbound), the IntelliJ chords, a status and a reason. The
 * table ships as the classpath resource {@value #RESOURCE} next to this class
 * and is read with the JDK only.
 */
public record KeymapTable(List<Row> rows) {

    public static final String RESOURCE = "intellij-keymap.csv";
    static final List<String> HEADER = List.of("action_id", "active_chords", "intellij_chords", "status", "reason");

    /**
     * How a row relates to IntelliJ: the chords are equal, differ with a reason,
     * cannot be delivered by a terminal, or the action has no IntelliJ chord.
     */
    public enum Status {
        SAME("same"),
        DIFFERENT("different"),
        NOT_DELIVERABLE("not-deliverable"),
        VINTELLI_ONLY("vintelli-only");

        private final String wireName;

        Status(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        static Status fromWireName(String name) {
            for (Status status : values()) {
                if (status.wireName.equals(name)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("unknown status '" + name + "'");
        }
    }

    /**
     * One action of the table.
     *
     * @param actionId        the action id as the keymap names it (binding names keep their dots)
     * @param activeChords    the chords the profile binds today
     * @param intellijChords  the IntelliJ Windows/Linux default chords of the action, empty when none is recorded
     * @param status          how the active and IntelliJ chords relate
     * @param reason          the explanation required for every status except {@link Status#SAME}
     */
    public record Row(String actionId, List<String> activeChords, List<String> intellijChords, Status status,
            String reason) {

        public Row {
            activeChords = List.copyOf(activeChords);
            intellijChords = List.copyOf(intellijChords);
        }
    }

    public KeymapTable {
        rows = List.copyOf(rows);
    }

    /**
     * The table packaged with the module.
     */
    public static KeymapTable load() {
        try (InputStream stream = KeymapTable.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("keymap table resource '" + RESOURCE + "' is missing from the classpath");
            }
            return parse(new StringReader(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read keymap table resource '" + RESOURCE + "'", e);
        }
    }

    /**
     * Parses the table from CSV text: a header row, then one row per action.
     * Fields may be double quoted to carry commas; lines starting with {@code #}
     * are comments. Duplicate action ids and unknown statuses fail fast.
     */
    public static KeymapTable parse(Reader reader) throws IOException {
        Map<String, Row> byId = new LinkedHashMap<>();
        boolean headerSeen = false;
        int lineNumber = 0;
        try (BufferedReader lines = new BufferedReader(reader)) {
            String line;
            while ((line = lines.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                List<String> fields = splitFields(trimmed, lineNumber);
                if (!headerSeen) {
                    if (!HEADER.equals(fields)) {
                        throw new IllegalArgumentException("line " + lineNumber + ": expected header " + HEADER);
                    }
                    headerSeen = true;
                    continue;
                }
                Row row = toRow(fields, lineNumber);
                if (byId.putIfAbsent(row.actionId(), row) != null) {
                    throw new IllegalArgumentException("line " + lineNumber + ": duplicate action '" + row.actionId() + "'");
                }
            }
        }
        if (!headerSeen) {
            throw new IllegalArgumentException("keymap table has no header row");
        }
        return new KeymapTable(List.copyOf(byId.values()));
    }

    /**
     * The row of one action, or {@code null} when the table has none.
     */
    public Row rowOf(String actionId) {
        for (Row row : rows) {
            if (row.actionId().equals(actionId)) {
                return row;
            }
        }
        return null;
    }

    private static Row toRow(List<String> fields, int lineNumber) {
        if (fields.size() != HEADER.size()) {
            throw new IllegalArgumentException("line " + lineNumber + ": expected " + HEADER.size()
                    + " fields but found " + fields.size());
        }
        return new Row(fields.get(0), chords(fields.get(1)), chords(fields.get(2)),
                Status.fromWireName(fields.get(3)), fields.get(4));
    }

    private static List<String> chords(String cell) {
        return Arrays.stream(cell.split("\\|"))
                .map(String::trim)
                .filter(chord -> !chord.isEmpty())
                .toList();
    }

    private static List<String> splitFields(String line, int lineNumber) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char character : line.toCharArray()) {
            if (character == '"') {
                quoted = !quoted;
            } else if (character == ',' && !quoted) {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("line " + lineNumber + ": unterminated quoted field");
        }
        fields.add(current.toString().trim());
        return fields;
    }
}
