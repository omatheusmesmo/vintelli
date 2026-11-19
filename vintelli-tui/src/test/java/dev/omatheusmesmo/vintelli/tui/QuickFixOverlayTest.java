package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CodeAction;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.SourceRange;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.editor.QuickFixController;
import dev.omatheusmesmo.vintelli.tui.editor.QuickFixOverlay;
import dev.omatheusmesmo.vintelli.tui.editor.QuickFixRequest;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The quick fix popup drawn into a buffer: a title measured by terminal cells keeps the popup border around
 * it, and a title that is cut ends with an ellipsis and never with half of a character.
 */
class QuickFixOverlayTest {

    private static final Rect AREA = new Rect(0, 0, 100, 30);
    private static final String KIND = "quickfix";
    private static final String ELLIPSIS = "…";

    @TempDir
    Path project;

    @Test
    void aWideTitleFitsInsideThePopupBorder() throws Exception {
        String title = "名前を変更する".repeat(3);

        String inner = popupRow(render(title));

        assertTrue(inner.contains(title + "  " + KIND), "the whole title and its kind are drawn: " + inner);
    }

    @Test
    void aCutWideTitleEndsWithAnEllipsisInsideThePopup() throws Exception {
        String title = "名前".repeat(50);

        String inner = popupRow(render(title));

        assertTrue(inner.stripTrailing().endsWith(ELLIPSIS), "a cut title says it is cut: " + inner);
    }

    @Test
    void aCharacterOutsideTheBmpIsNeverSplitWhenTheTitleIsCut() throws Exception {
        String title = "x".repeat(68) + "🔧" + "y".repeat(40);

        String inner = popupRow(render(title));

        assertTrue(inner.stripTrailing().endsWith(ELLIPSIS), "the cut title says it is cut: " + inner);
        assertEquals(inner, new String(inner.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8),
                "no lone surrogate is drawn: " + inner);
    }

    private Buffer render(String title) throws Exception {
        Path file = Files.writeString(project.resolve("Fix.java"), "class Fix {}\n");
        CodeAction action = new CodeAction(title, KIND, true, new WorkspaceEdit(List.of(), List.of()), null);
        BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        BuiltinEditor editor = new BuiltinEditor(project, null, themes::editorPalette);
        QuickFixController controller = new QuickFixController(
                QuickFixFixtures.registryWith(QuickFixFixtures.answering(path -> CapabilityResult.ok(List.of(action)))),
                editor, deliveries::add, status -> { });
        controller.open(new QuickFixRequest(file, new SourceRange(0, 0, 0, 0), List.of()));
        Objects.requireNonNull(deliveries.poll(10, TimeUnit.SECONDS), "no answer reached the render thread").run();

        Buffer buffer = Buffer.empty(AREA);
        new QuickFixOverlay(controller).render(Frame.forTesting(buffer), AREA, Optional.empty());
        return buffer;
    }

    /**
     * The title row inside the popup, from the cell after its left border to the cell before its right border.
     * Fails when anything is drawn past the right border.
     */
    private static String popupRow(Buffer buffer) {
        int top = -1;
        for (int y = 0; y < AREA.height() && top < 0; y++) {
            if (cells(buffer, y).contains("╭")) {
                top = y;
            }
        }
        assertTrue(top >= 0, "the popup is drawn");
        List<String> row = cells(buffer, top + 1);
        int left = row.indexOf("│");
        int right = row.lastIndexOf("│");
        assertTrue(left >= 0 && right > left, "the title row is framed by the popup border: " + row);
        assertTrue(row.subList(right + 1, row.size()).stream().allMatch(" "::equals),
                "nothing is drawn past the popup border: " + row);
        return String.join("", row.subList(left + 1, right));
    }

    private static List<String> cells(Buffer buffer, int y) {
        List<String> cells = new ArrayList<>();
        for (int x = 0; x < AREA.width(); x++) {
            cells.add(buffer.get(x, y).symbol());
        }
        return cells;
    }
}
