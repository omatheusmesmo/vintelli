package dev.omatheusmesmo.vintelli.tui.navigation;

import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavigationTargetsTest {

    private static final OverlayRegion PANEL = new OverlayRegion(10, 5, 60, 8);
    private static final OverlayRegion INNER = PANEL.inset(1);

    private final NavigationTargets targets = new NavigationTargets();

    @Test
    void enterConfirmsTheSelectedRowAndClosesThePopup() {
        targets.open("Usages of run", rows("Main.java", "Util.java", "Other.java"));
        targets.press(OverlayKey.DOWN, 0);

        Optional<NavigationTarget> confirmed = targets.press(OverlayKey.ENTER, 0);

        assertEquals("Util.java", confirmed.orElseThrow().name());
        assertFalse(targets.isOpen());
    }

    @Test
    void escapeClosesWithoutConfirming() {
        targets.open("Usages of run", rows("Main.java", "Util.java"));

        assertTrue(targets.press(OverlayKey.ESCAPE, 0).isEmpty());
        assertFalse(targets.isOpen());
    }

    @Test
    void typingNarrowsTheRowsAndBackspaceWidensThemAgain() {
        targets.open("Usages of run", rows("Main.java", "Util.java", "Other.java"));

        targets.press(OverlayKey.TEXT, 'u');
        targets.press(OverlayKey.TEXT, 't');
        assertEquals(List.of("Util.java"), names());
        assertEquals("Util.java", targets.press(OverlayKey.ENTER, 0).orElseThrow().name());

        targets.open("Usages of run", rows("Main.java", "Util.java", "Other.java"));
        targets.press(OverlayKey.TEXT, 'x');
        assertTrue(names().isEmpty());
        assertTrue(targets.press(OverlayKey.ENTER, 0).isEmpty(), "no row is selected, so Enter confirms nothing");
        targets.press(OverlayKey.BACKSPACE, 0);
        assertEquals(3, names().size());
    }

    @Test
    void theFilterAlsoMatchesTheSourceLineAndTheDirectory() {
        targets.open("Usages of run", List.of(target("Main.java", "src/main", "int a = run();"),
                target("Util.java", "src/util", "return other;")));

        "util".codePoints().forEach(codePoint -> targets.press(OverlayKey.TEXT, codePoint));
        assertEquals(List.of("Util.java"), names());

        targets.open("Usages of run", List.of(target("Main.java", "src/main", "int a = run();"),
                target("Util.java", "src/util", "return other;")));
        "other".codePoints().forEach(codePoint -> targets.press(OverlayKey.TEXT, codePoint));
        assertEquals(List.of("Util.java"), names());
    }

    @Test
    void aClickOnARowConfirmsItAndAClickOutsideClosesThePopup() {
        targets.open("Usages of run", rows("Main.java", "Util.java", "Other.java"));
        targets.layout(PANEL, INNER);

        Optional<NavigationTarget> clicked = targets.pointer(INNER.x() + 2, INNER.y() + 1, true, 0);
        assertEquals("Util.java", clicked.orElseThrow().name());
        assertFalse(targets.isOpen());

        targets.open("Usages of run", rows("Main.java", "Util.java"));
        targets.layout(PANEL, INNER);
        assertTrue(targets.pointer(0, 0, true, 0).isEmpty());
        assertFalse(targets.isOpen());
    }

    @Test
    void theSelectionScrollsThroughMoreRowsThanFit() {
        targets.open("Usages of run", IntStream.range(0, 30).mapToObj(index -> target("F" + index + ".java", "", "x"))
                .toList());
        targets.layout(PANEL, INNER);

        for (int step = 0; step < 12; step++) {
            targets.press(OverlayKey.DOWN, 0);
        }
        targets.layout(PANEL, INNER);

        NavigationTargets.View view = targets.view();
        assertEquals(12, view.selected());
        assertTrue(view.firstVisible() > 0 && view.firstVisible() <= 12);
        assertTrue(view.visible().stream().anyMatch(row -> row.name().equals("F12.java")));
    }

    private List<String> names() {
        return targets.view().rows().stream().map(NavigationTarget::name).toList();
    }

    private static List<NavigationTarget> rows(String... names) {
        return Arrays.stream(names).map(name -> target(name, "src", "run();")).toList();
    }

    private static NavigationTarget target(String name, String directory, String preview) {
        return new NavigationTarget(Path.of("/project/src").resolve(name), name, directory, 3, 8, preview, -1, -1);
    }
}
