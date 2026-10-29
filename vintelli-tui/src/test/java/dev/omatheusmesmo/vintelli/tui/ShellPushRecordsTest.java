package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.omatheusmesmo.vintelli.api.ShellHighlightTarget;
import dev.omatheusmesmo.vintelli.api.ShellUnifiedDiff;
import org.junit.jupiter.api.Test;

/**
 * The validation contract of the plain-Java push records: every rule set by
 * the records must hold for any caller, not only the MCP tools.
 */
class ShellPushRecordsTest {

    @Test
    void highlightTargetRejectsBlankFileAndNonPositiveLine() {
        assertThrows(IllegalArgumentException.class, () -> new ShellHighlightTarget(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> new ShellHighlightTarget("a", 0));
        assertThrows(IllegalArgumentException.class, () -> new ShellHighlightTarget("a", -1));
        assertThrows(NullPointerException.class, () -> new ShellHighlightTarget(null, 1));
    }

    @Test
    void highlightTargetRejectsAbsoluteAndTraversingPaths() {
        assertThrows(IllegalArgumentException.class, () -> new ShellHighlightTarget("/etc/passwd", 1));
        assertThrows(IllegalArgumentException.class, () -> new ShellHighlightTarget("../pom.xml", 1));
        assertThrows(IllegalArgumentException.class, () -> new ShellHighlightTarget("vintelli/../..", 1));
    }

    @Test
    void highlightTargetCapsTheFileLength() {
        assertThrows(IllegalArgumentException.class, () -> new ShellHighlightTarget("a".repeat(4097), 1));
    }

    @Test
    void highlightTargetStripsEveryControlCharacterFromTheFile() {
        ShellHighlightTarget target = new ShellHighlightTarget("src/Ma\u0007in\u001Btab\t.java", 3);
        assertEquals("src/Ma in tab .java", target.file());
    }

    @Test
    void unifiedDiffRejectsBlankTitleAndBlankDiff() {
        assertThrows(IllegalArgumentException.class, () -> new ShellUnifiedDiff(" ", "@@"));
        assertThrows(IllegalArgumentException.class, () -> new ShellUnifiedDiff("t", "\n"));
        assertThrows(NullPointerException.class, () -> new ShellUnifiedDiff("t", null));
        assertThrows(NullPointerException.class, () -> new ShellUnifiedDiff(null, "@@"));
    }

    @Test
    void unifiedDiffCapsTitleAndDiff() {
        assertThrows(IllegalArgumentException.class, () -> new ShellUnifiedDiff("t".repeat(201), "@@"));
        assertThrows(IllegalArgumentException.class, () -> new ShellUnifiedDiff("t", "d".repeat(256 * 1024 + 1)));
    }

    @Test
    void unifiedDiffKeepsNewlineAndTabAndStripsOtherControlCharacters() {
        ShellUnifiedDiff pushed = new ShellUnifiedDiff("title\u0007", "a\tb\nc\u001Bd");
        assertEquals("title ", pushed.title());
        assertEquals("a\tb\nc d", pushed.unifiedDiff());
    }

    @Test
    void stripsC1ControlCharactersAndDeleteThatSomeTerminalsReadAsEscapeSequences() {
        ShellHighlightTarget target = new ShellHighlightTarget("src/a\u009Bb\u009Dc\u007Fd.java", 1);
        assertEquals("src/a b c d.java", target.file());

        ShellUnifiedDiff pushed = new ShellUnifiedDiff("t\u009B31m", "x\u009D0;owned\u0007y");
        assertEquals("t 31m", pushed.title());
        assertEquals("x 0;owned y", pushed.unifiedDiff());
    }
}
