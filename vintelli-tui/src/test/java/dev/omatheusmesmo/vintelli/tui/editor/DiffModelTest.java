package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.tui.editor.DiffModel.Hunk;
import dev.omatheusmesmo.vintelli.tui.editor.DiffModel.Kind;
import dev.omatheusmesmo.vintelli.tui.editor.DiffModel.LineKind;
import dev.omatheusmesmo.vintelli.tui.editor.DiffModel.Row;
import dev.omatheusmesmo.vintelli.tui.editor.DiffModel.UnifiedLine;
import dev.omatheusmesmo.vintelli.tui.editor.DiffModel.WordRange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffModelTest {

    private static final String METHOD = """
            class A {
                void run() {
                    int a = 1;
                    int b = 2;
                    int c = 3;
                    int d = 4;
                    int e = 5;
                }
            }
            """;

    @Test
    void insertedLinesAlignAgainstAFillerOnTheHeadSide() {
        DiffModel model = DiffModel.of(Optional.of("a\nb\n"), Optional.of("a\nnew\nb\n"), "notes.txt");

        assertEquals(List.of(Kind.EQUAL, Kind.INSERTED, Kind.EQUAL), kinds(model));
        Row inserted = model.rows().get(1);
        assertNull(inserted.left(), "the head side of an insertion is a filler row");
        assertEquals(2, inserted.right().line());
        assertEquals("new", inserted.right().text());
        Row after = model.rows().get(2);
        assertEquals(2, after.left().line(), "the row after the filler keeps both sides on the same text");
        assertEquals(3, after.right().line());
        assertEquals(List.of(new Hunk(Kind.INSERTED, 1, 2)), model.hunks());
    }

    @Test
    void deletedLinesAlignAgainstAFillerOnTheWorkingSide() {
        DiffModel model = DiffModel.of(Optional.of("a\nold\nolder\nb\n"), Optional.of("a\nb\n"), "notes.txt");

        assertEquals(List.of(Kind.EQUAL, Kind.DELETED, Kind.DELETED, Kind.EQUAL), kinds(model));
        assertNull(model.rows().get(1).right());
        assertNull(model.rows().get(2).right());
        assertEquals(3, model.rows().get(2).left().line());
        assertEquals(new Hunk(Kind.DELETED, 1, 3), model.hunks().getFirst());
        assertEquals(OptionalInt.of(2), model.workingLineOf(1),
                "Enter on a deleted row opens the working line that follows the deletion");
    }

    @Test
    void modifiedPairCarriesTheChangedWordsOnBothSides() {
        DiffModel model = DiffModel.of(Optional.of("keep\nint total = count + 1;\n"),
                Optional.of("keep\nint total = limit + 1;\n"), "A.java");

        Row modified = model.rows().get(1);
        assertEquals(Kind.MODIFIED, modified.kind());
        assertEquals(2, modified.left().line());
        assertEquals(2, modified.right().line());
        assertEquals(List.of(new WordRange(12, 17)), modified.left().words(), "only `count` changed on the left");
        assertEquals(List.of(new WordRange(12, 17)), modified.right().words(), "only `limit` changed on the right");
        assertEquals("count", modified.left().text().substring(12, 17));
        assertEquals("limit", modified.right().text().substring(12, 17));
    }

    @Test
    void unevenChangePairsWhatItCanAndFillsTheRest() {
        DiffModel model = DiffModel.of(Optional.of("a\nx = 1\ny = 2\nb\n"), Optional.of("a\nx = 3\nb\n"), "f.txt");

        assertEquals(List.of(Kind.EQUAL, Kind.MODIFIED, Kind.MODIFIED, Kind.EQUAL), kinds(model));
        assertEquals(List.of(new WordRange(4, 5)), model.rows().get(1).right().words());
        Row unpaired = model.rows().get(2);
        assertEquals("y = 2", unpaired.left().text());
        assertNull(unpaired.right(), "the extra head line of a change is aligned against a filler");
        assertTrue(unpaired.left().words().isEmpty(), "an unpaired line has no word level changes");
        assertEquals(List.of(new Hunk(Kind.MODIFIED, 1, 3)), model.hunks());
    }

    @Test
    void addedAndDeletedFilesKeepOneEmptySide() {
        DiffModel added = DiffModel.of(Optional.empty(), Optional.of("one\ntwo\n"), "New.java");
        DiffModel deleted = DiffModel.of(Optional.of("one\ntwo\n"), Optional.empty(), "Old.java");

        assertFalse(added.headPresent());
        assertEquals(List.of(Kind.INSERTED, Kind.INSERTED), kinds(added));
        assertTrue(added.rows().stream().allMatch(row -> row.left() == null));
        assertFalse(deleted.workingPresent());
        assertEquals(List.of(Kind.DELETED, Kind.DELETED), kinds(deleted));
        assertEquals(OptionalInt.empty(), deleted.workingLineOf(0), "a deleted file has no working line to open");
        assertEquals("@@ -0,0 +1,2 @@", added.unified().getFirst().text());
        assertEquals("@@ -1,2 +0,0 @@", deleted.unified().getFirst().text());
    }

    @Test
    void lineEndingsAndTabsAreNotReportedAsChanges() {
        DiffModel model = DiffModel.of(Optional.of("a\n\tb\n"), Optional.of("a\r\n\tb\r\n"), "f.txt");

        assertTrue(model.hunks().isEmpty(), "a CRLF checkout of an LF commit has no change");
        assertEquals("    b", model.rows().get(1).right().text(), "tabs are expanded to cells");
    }

    @Test
    void unifiedHunkHasContextHeaderAndTheEnclosingMethod() {
        DiffModel model = DiffModel.of(Optional.of(METHOD), Optional.of(METHOD.replace("d = 4", "d = 40")), "A.java");

        List<UnifiedLine> unified = model.unified();
        assertEquals("@@ -3,7 +3,7 @@ void run()", unified.getFirst().text());
        assertEquals(List.of(LineKind.HEADER, LineKind.CONTEXT, LineKind.CONTEXT, LineKind.CONTEXT,
                LineKind.REMOVED, LineKind.ADDED, LineKind.CONTEXT, LineKind.CONTEXT, LineKind.CONTEXT),
                unified.stream().map(UnifiedLine::kind).toList());
        UnifiedLine removed = unified.get(4);
        assertEquals(6, removed.oldLine());
        assertEquals(0, removed.newLine());
        assertEquals("4", removed.text().substring(removed.words().getFirst().start(), removed.words().getFirst().end()));
        assertEquals(6, unified.get(5).newLine());
        assertEquals(4, model.unifiedStartOf(0));
    }

    @Test
    void distantHunksGetTheirOwnHeaderAndNearOnesShareOne() {
        List<String> lines = IntStream.rangeClosed(1, 20).mapToObj(number -> "line " + number).toList();
        String head = String.join("\n", lines) + "\n";
        String working = String.join("\n", lines.stream()
                .map(line -> List.of("line 2", "line 4", "line 18").contains(line) ? line + " changed" : line)
                .toList()) + "\n";

        List<String> headers = DiffModel.of(Optional.of(head), Optional.of(working), "n.txt").unified().stream()
                .filter(line -> line.kind() == LineKind.HEADER).map(UnifiedLine::text).toList();

        assertEquals(List.of("@@ -1,7 +1,7 @@", "@@ -15,6 +15,6 @@"), headers,
                "a hunk within twice the context of the previous one joins its header; a hunk without a "
                        + "method around it has no name, as in a non Java file");
    }

    private static List<Kind> kinds(DiffModel model) {
        return model.rows().stream().map(Row::kind).toList();
    }
}
