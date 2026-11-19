package dev.omatheusmesmo.vintelli.tui.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.FormattingOptions;
import org.junit.jupiter.api.Test;

class EditorIndentTest {

    @Test
    void fourSpaceCodeIgnoresTheSingleSpaceJavadocContinuations() {
        String text = "class Main {\n"
                + "    /**\n"
                + "     * Runs the shell.\n"
                + "     */\n"
                + "    void run() {\n"
                + "        int x = 1;\n"
                + "    }\n"
                + "}\n";

        assertEquals("4 spaces", EditorIndent.describe(text));
    }

    @Test
    void twoSpaceIndentIsReportedAsTwoSpaces() {
        assertEquals("2 spaces", EditorIndent.describe("root:\n  child: 1\n  other: 2\n"));
    }

    @Test
    void tabIndentedFileIsReportedAsTab() {
        assertEquals("Tab", EditorIndent.describe("class Main {\n\tvoid run() {\n\t}\n}\n"));
    }

    @Test
    void unindentedTextHasNoIndentToReport() {
        assertEquals("", EditorIndent.describe("first line\nsecond line\n"));
    }

    @Test
    void theFormattingRequestKeepsTheIndentTheDocumentAlreadyUses() {
        assertEquals(new FormattingOptions(2, true), EditorIndent.formattingOptions("root:\n  child: 1\n  other: 2\n"));
        assertEquals(new FormattingOptions(4, false),
                EditorIndent.formattingOptions("class Main {\n\tvoid run() {\n\t}\n}\n"));
        assertEquals(new FormattingOptions(4, true), EditorIndent.formattingOptions("first line\nsecond line\n"),
                "a document without an indent gets four spaces");
    }
}
