package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
import org.junit.jupiter.api.Test;

class DebugValuePromptTest {

    @Test
    void opensPrefilledEditsAndSubmitsTheStrippedValueOnce() {
        DebugValuePrompt prompt = new DebugValuePrompt();
        assertFalse(prompt.active());

        prompt.start("count", "3");
        assertEquals("3", prompt.text());
        prompt.backspace();
        " 10 ".codePoints().forEach(prompt::type);

        assertEquals("10", prompt.submit().orElseThrow());
        assertFalse(prompt.active(), "a submitted prompt closes so it cannot assign twice");
        assertTrue(prompt.submit().isEmpty());
    }

    @Test
    void blankValueClosesWithoutAssigningAndCancelDiscardsTheText() {
        DebugValuePrompt prompt = new DebugValuePrompt();
        prompt.start("count", "3");
        prompt.backspace();
        assertTrue(prompt.submit().isEmpty());
        assertFalse(prompt.active());

        prompt.start("count", "3");
        prompt.cancel();
        assertFalse(prompt.active());
        assertEquals("", prompt.text());
    }

    @Test
    void aMultiLineOrOversizedCurrentValueDoesNotPrefillAndTypingIsBounded() {
        DebugValuePrompt prompt = new DebugValuePrompt();
        prompt.start("text", "first\nsecond");
        assertEquals("", prompt.text());

        prompt.start("text", "x".repeat(DebugCapability.MAX_SET_VALUE_LENGTH + 1));
        assertEquals("", prompt.text());

        "y".repeat(DebugCapability.MAX_SET_VALUE_LENGTH + 20).codePoints().forEach(prompt::type);
        assertEquals(DebugCapability.MAX_SET_VALUE_LENGTH, prompt.text().length());

        prompt.start("emoji", "");
        "a😀".codePoints().forEach(prompt::type);
        prompt.backspace();
        assertEquals("a", prompt.text(), "backspace removes whole code points");
    }
}
