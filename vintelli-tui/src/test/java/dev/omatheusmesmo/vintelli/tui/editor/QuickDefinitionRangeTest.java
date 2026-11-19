package dev.omatheusmesmo.vintelli.tui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.DocumentSymbol;

/**
 * Which lines the quick definition popup shows: the innermost declaration of the outline that contains the target,
 * a bounded window when no declaration does, and never a line outside the file.
 */
class QuickDefinitionRangeTest {

    private static DocumentSymbol symbol(String name, int startLine, int startColumn, int endLine, int endColumn,
            List<DocumentSymbol> children) {
        return new DocumentSymbol(name, 6, startLine, startColumn, endLine, endColumn, startLine, startColumn,
                startLine, startColumn + name.length(), children);
    }

    @Test
    void theInnermostDeclarationAroundTheTargetIsShownNotItsEnclosingClass() {
        DocumentSymbol method = symbol("run", 4, 4, 9, 5, List.of());
        DocumentSymbol other = symbol("stop", 11, 4, 13, 5, List.of());
        DocumentSymbol type = symbol("Util", 0, 0, 20, 1, List.of(method, other));

        QuickDefinitionRange range = QuickDefinitionRange.of(List.of(type), 4, 9, 21);

        assertEquals(new QuickDefinitionRange(4, 9), range);
    }

    @Test
    void aTargetOnATypeNameShowsTheWholeType() {
        DocumentSymbol method = symbol("run", 4, 4, 9, 5, List.of());
        DocumentSymbol type = symbol("Util", 2, 0, 12, 1, List.of(method));

        assertEquals(new QuickDefinitionRange(2, 12), QuickDefinitionRange.of(List.of(type), 2, 6, 20));
    }

    @Test
    void aDeclarationThatEndsAtColumnZeroDoesNotTakeTheNextLine() {
        DocumentSymbol method = symbol("run", 4, 4, 9, 0, List.of());

        assertEquals(new QuickDefinitionRange(4, 8), QuickDefinitionRange.of(List.of(method), 4, 9, 20));
    }

    @Test
    void aHugeDeclarationIsCappedSoThePopupStaysCheap() {
        DocumentSymbol type = symbol("Huge", 0, 0, 5000, 1, List.of());

        QuickDefinitionRange range = QuickDefinitionRange.of(List.of(type), 0, 6, 6000);

        assertEquals(new QuickDefinitionRange(0, QuickDefinitionRange.MAX_LINES - 1), range);
    }

    @Test
    void withoutAnOutlineTheTwentyLinesFromTheTargetAreShown() {
        assertEquals(new QuickDefinitionRange(10, 29), QuickDefinitionRange.of(List.of(), 10, 0, 100));
    }

    @Test
    void theFallbackNeverRunsPastTheEndOfTheFile() {
        assertEquals(new QuickDefinitionRange(3, 5), QuickDefinitionRange.of(List.of(), 3, 0, 6));
    }

    @Test
    void aTargetLineBeyondTheFileIsClampedToItsLastLine() {
        assertEquals(new QuickDefinitionRange(5, 5), QuickDefinitionRange.of(List.of(), 40, 0, 6));
    }
}
