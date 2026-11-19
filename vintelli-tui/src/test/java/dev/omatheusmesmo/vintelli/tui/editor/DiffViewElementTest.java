package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.omatheusmesmo.vintelli.tui.editor.DiffModel.Kind;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffViewElementTest {

    private static final int ROWS = 15;

    @TempDir
    Path project;

    private Path file;
    private String head;
    private String working;
    private BuiltinEditor editor;
    private DefaultRenderContext context;

    /**
     * Twelve lines where line 2 is changed, a line is inserted after line 6 and line 10 is deleted, so
     * the side by side rows are: 1 modified, 6 inserted, 10 deleted, the rest equal.
     */
    @BeforeEach
    void openDiff() throws IOException {
        List<String> lines = IntStream.rangeClosed(1, 12).mapToObj(number -> "line " + number).toList();
        head = String.join("\n", lines) + "\n";
        working = lines.stream()
                .filter(line -> !line.equals("line 10"))
                .map(line -> line.equals("line 2") ? "line 2 changed" : line)
                .map(line -> line.equals("line 6") ? line + "\nadded" : line)
                .collect(Collectors.joining("\n")) + "\n";
        Path root = project.toRealPath();
        file = root.resolve("Notes.txt");
        Files.writeString(file, working);
        ShellThemes themes = new ShellThemes("darcula", (Path) null);
        editor = new BuiltinEditor(root, null, themes::editorPalette);
        context = DefaultRenderContext.createEmpty();
        context.setStyleEngine(themes.engine());
        editor.openDiff(file, Optional.of(head), Optional.of(working));
    }

    @Test
    void sideBySideAlignsChangesAgainstFillersWithAConnectorColoredByChangeKind() {
        Buffer buffer = render(120);
        int connector = (120 - 1) / 2;

        assertTrue(rowText(buffer, 0).contains("HEAD"), rowText(buffer, 0));
        assertTrue(rowText(buffer, 0).substring(connector).contains("Working"), rowText(buffer, 0));
        assertFalse(editor.activeDiffTab().unified(), "120 columns is side by side");

        int inserted = 1 + 6;
        assertEquals(bg(DiffViewElement.FILLER_ROLE), buffer.get(5, inserted).style().bg().orElse(null),
                "the head side of an inserted line is a filler row");
        assertEquals(bg(DiffViewElement.connectorRole(Kind.INSERTED)),
                buffer.get(connector, inserted).style().bg().orElse(null));
        assertEquals(" 7 added", rowText(buffer, inserted).substring(connector + 1, connector + 9));
        assertEquals(bg(DiffViewElement.kindRole(Kind.INSERTED)),
                buffer.get(connector + 10, inserted).style().bg().orElse(null));

        int deleted = 1 + 10;
        assertEquals("10 line 10", rowText(buffer, deleted).substring(0, 10));
        assertEquals(bg(DiffViewElement.kindRole(Kind.DELETED)), buffer.get(5, deleted).style().bg().orElse(null));
        assertEquals(bg(DiffViewElement.connectorRole(Kind.DELETED)),
                buffer.get(connector, deleted).style().bg().orElse(null));
        assertEquals(bg(DiffViewElement.FILLER_ROLE), buffer.get(connector + 5, deleted).style().bg().orElse(null));

        int modified = 1 + 1;
        int changedWord = rowText(buffer, modified).indexOf("changed");
        assertTrue(changedWord > connector, rowText(buffer, modified));
        assertEquals(bg(DiffViewElement.wordRole(Kind.MODIFIED)),
                buffer.get(changedWord, modified).style().bg().orElse(null), "the changed word takes the stronger shade");
        assertEquals(bg(DiffViewElement.kindRole(Kind.MODIFIED)), buffer.get(3, modified).style().bg().orElse(null));
        assertEquals(bg(DiffViewElement.connectorRole(Kind.MODIFIED)),
                buffer.get(connector, modified).style().bg().orElse(null));

        int afterInsert = 1 + 7;
        assertEquals(" 7 line 7", rowText(buffer, afterInsert).substring(0, 9), "the head numbering skips the filler");
        assertEquals(" 8 line 7", rowText(buffer, afterInsert).substring(connector + 1, connector + 10));
        assertTrue(Set.of("│", "|").contains(buffer.get(connector, afterInsert).symbol()),
                "an equal row draws a plain separator, not a change connector");
        assertEquals(bg(DiffViewElement.TEXT_ROLE), buffer.get(connector + 20, afterInsert).style().bg().orElse(null));
    }

    @Test
    void belowOneHundredColumnsTheDiffIsUnifiedAndAltDSwitchesTheLayout() {
        Buffer buffer = render(80);

        assertTrue(editor.activeDiffTab().unified(), "80 columns starts unified");
        assertTrue(rowText(buffer, 0).contains("unified"), rowText(buffer, 0));
        assertTrue(rowText(buffer, 1).startsWith("@@ -1,12 +1,12 @@"), rowText(buffer, 1));
        int removed = rowIndexOf(buffer, "-line 2");
        int added = rowIndexOf(buffer, "+line 2 changed");
        assertTrue(removed > 1 && added == removed + 1, "a change lists its removed line, then its added one");
        assertTrue(rowText(buffer, removed).startsWith(" 2    -line 2"), rowText(buffer, removed));
        assertTrue(rowText(buffer, added).startsWith("    2 +line 2 changed"), rowText(buffer, added));
        assertEquals(bg(DiffViewElement.kindRole(Kind.DELETED)), buffer.get(40, removed).style().bg().orElse(null));
        assertEquals(bg(DiffViewElement.kindRole(Kind.INSERTED)), buffer.get(40, added).style().bg().orElse(null));

        assertTrue(editor.handleKey(KeyEvent.ofChar('d', KeyModifiers.ALT)));
        Buffer toggled = render(80);
        assertFalse(editor.activeDiffTab().unified(), "Alt+D overrides the width rule");
        assertFalse(rowText(toggled, 0).contains("unified"), rowText(toggled, 0));

        render(60);
        assertFalse(editor.activeDiffTab().unified(), "the chosen layout holds when the pane narrows");
        assertTrue(editor.handleKey(KeyEvent.ofChar('d', KeyModifiers.ALT)));
        render(120);
        assertTrue(editor.activeDiffTab().unified(), "the chosen layout holds when the pane widens");
    }

    @Test
    void f7AndShiftF7WalkTheChangesAndEnterOpensTheWorkingLine() {
        DiffTab tab = editor.activeDiffTab();
        assertEquals(1, tab.cursor(), "the tab opens on the first change");

        assertTrue(editor.handleKey(KeyEvent.ofKey(KeyCode.F7)));
        assertEquals(6, tab.cursor());
        assertEquals("Difference 2 of 3", editor.status());
        assertTrue(editor.handleKey(KeyEvent.ofKey(KeyCode.F7)));
        assertEquals(10, tab.cursor());
        assertTrue(editor.handleKey(KeyEvent.ofKey(KeyCode.F7)));
        assertEquals(10, tab.cursor(), "F7 stops on the last change");
        assertEquals("No next difference", editor.status());

        assertTrue(editor.handleKey(KeyEvent.ofKey(KeyCode.F7, KeyModifiers.SHIFT)));
        assertEquals(6, tab.cursor(), "Shift+F7 goes back one change");

        assertTrue(editor.handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
        BuiltinEditor.DocumentView opened = editor.activeDocument().orElseThrow();
        assertEquals(file.toString(), opened.file());
        assertEquals(6, opened.line(), "Enter on the inserted row opens working line 7 (row 6)");
        assertEquals(working, opened.text());
    }

    @Test
    void theDiffIsATabThatClosesBackToTheDocuments() throws IOException {
        Path other = project.toRealPath().resolve("Other.txt");
        Files.writeString(other, "x\n");
        editor.open(new OpenRequest(other, 0));
        assertNull(editor.activeDiffTab(), "opening a document leaves the diff tab");

        editor.selectTab(1);
        assertEquals("Notes.txt (HEAD ↔ Working)", editor.activeTabLabel().replace("<->", "↔"));
        assertEquals(BuiltinEditor.CloseResult.CLOSED, editor.closeActive());
        assertNull(editor.activeDiffTab());
        assertEquals(other.toString(), editor.activeDocument().orElseThrow().file());
    }

    private Buffer render(int width) {
        Rect area = new Rect(0, 0, width, ROWS);
        Buffer buffer = Buffer.empty(area);
        new DiffViewElement(editor).render(Frame.forTesting(buffer), area, context);
        return buffer;
    }

    private Color bg(String role) {
        return EditorElement.roleStyle(context, DiffViewElement.TEXT_ROLE)
                .patch(EditorElement.roleStyle(context, role)).bg().orElse(null);
    }

    private static int rowIndexOf(Buffer buffer, String text) {
        for (int y = 0; y < buffer.height(); y++) {
            if (rowText(buffer, y).contains(text)) {
                return y;
            }
        }
        return -1;
    }

    private static String rowText(Buffer buffer, int y) {
        StringBuilder text = new StringBuilder();
        for (int x = 0; x < buffer.width(); x++) {
            text.append(buffer.get(x, y).symbol());
        }
        return text.toString();
    }
}
