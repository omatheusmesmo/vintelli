package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.DebugVariable;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

class DebugToolWindowTest {

    @Test
    void unavailableStateRendersWithoutTriggeringInspectionAndKeyboardNavigationIsSafe() {
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        RunToolWindow run = new RunToolWindow(() -> themes);
        UiWorkQueue work = new UiWorkQueue();
        DebugToolWindow debug = new DebugToolWindow(() -> themes, run, work);
        assertTrue(debug.capturesInput(), "keyboard navigation should own focus while Debug is active");
        assertFalse(debug.claimsQuitChord(), "Ctrl+C remains available to quit from a list-style debugger window");

        Buffer small = Buffer.empty(Rect.of(40, 4));
        debug.render(Frame.forTesting(small), Rect.of(40, 4));
        String shortFrame = text(small);
        assertTrue(shortFrame.contains("Unavailable"), shortFrame);
        assertFalse(shortFrame.contains("Loading"), shortFrame);

        for (int index = 0; index < 50; index++) {
            debug.handleKey(KeyEvent.ofKey(KeyCode.DOWN));
            Buffer resized = Buffer.empty(Rect.of(80 + index, 8 + index % 3));
            debug.render(Frame.forTesting(resized), Rect.of(80 + index, 8 + index % 3));
        }
        assertFalse(text(Buffer.empty(Rect.of(1, 1))).contains("Loading"));
    }

    @Test
    void expandableVariablesBoundCyclesDepthAndTotalRowsWithMarkers() {
        List<String> cycleLines = new ArrayList<>();
        DebugToolWindow.appendVariables(cycleLines, new ArrayList<>(), new ArrayList<>(), 1, 1,
                Map.of(1, List.of(new DebugVariable("self", "{}", "Object", 1, true))),
                Set.of(1), new HashSet<>(), new int[] {10});
        assertTrue(cycleLines.stream().anyMatch(line -> line.contains("cycle to reference 1")), cycleLines.toString());

        Map<Integer, List<DebugVariable>> deep = new HashMap<>();
        Set<Integer> expanded = new HashSet<>();
        for (int reference = 1; reference <= 20; reference++) {
            deep.put(reference, List.of(new DebugVariable("next", "{}", "Object", reference + 1, true)));
            expanded.add(reference);
        }
        deep.put(21, List.of(new DebugVariable("end", "1", "int", 0, false)));
        expanded.add(21);
        List<String> deepLines = new ArrayList<>();
        DebugToolWindow.appendVariables(deepLines, new ArrayList<>(), new ArrayList<>(), 1, 1,
                deep, expanded, new HashSet<>(), new int[] {100});
        assertTrue(deepLines.stream().anyMatch(line -> line.contains("maximum depth reached")), deepLines.toString());

        List<String> limitedLines = new ArrayList<>();
        List<DebugVariable> many = IntStream.range(0, 20)
                .mapToObj(index -> new DebugVariable("v" + index, "0", "int", 0, false)).toList();
        DebugToolWindow.appendVariables(limitedLines, new ArrayList<>(), new ArrayList<>(), 1, 1,
                Map.of(1, many), Set.of(1), new HashSet<>(), new int[] {5});
        assertEquals(6, limitedLines.size());
        assertTrue(limitedLines.getLast().contains("variable limit reached"), limitedLines.toString());
    }

    private static String text(Buffer buffer) {
        StringBuilder value = new StringBuilder();
        for (int y = 0; y < buffer.area().height(); y++) {
            for (int x = 0; x < buffer.area().width(); x++) value.append(buffer.get(x, y).symbol());
            value.append('\n');
        }
        return value.toString();
    }
}
