package dev.omatheusmesmo.vintelli.tui.keymap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KeymapTableTest {

    private static final String HEADER = "action_id,active_chords,intellij_chords,status,reason\n";

    @Test
    void readsChordListsStatusAndAQuotedReasonWithCommas() throws IOException {
        KeymapTable table = parse(HEADER + "# a comment line\n"
                + "editor_nextTab,Alt+] | Alt+[,\"Alt+,\",different,\"Next, then previous tab\"\n");

        KeymapTable.Row row = table.rowOf("editor_nextTab");
        assertEquals(List.of("Alt+]", "Alt+["), row.activeChords());
        assertEquals(List.of("Alt+,"), row.intellijChords());
        assertEquals(KeymapTable.Status.DIFFERENT, row.status());
        assertEquals("Next, then previous tab", row.reason());
    }

    @Test
    void rejectsAnUnknownStatusADuplicateActionAndAWrongFieldCount() {
        assertThrows(IllegalArgumentException.class, () -> parse(HEADER + "a,Ctrl+A,,maybe,reason\n"));
        assertThrows(IllegalArgumentException.class, () -> parse(HEADER
                + "a,Ctrl+A,,same,\n" + "a,Ctrl+A,,same,\n"));
        assertThrows(IllegalArgumentException.class, () -> parse(HEADER + "a,Ctrl+A,same,reason\n"));
    }

    private static KeymapTable parse(String text) throws IOException {
        return KeymapTable.parse(new StringReader(text));
    }
}
