package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.EvaluationRequest;
import org.junit.jupiter.api.Test;

class DebugEvaluationPromptTest {

    @Test
    void expressionRunsOnlyAfterTheSecondConfirmationAndEnterAloneNeverExecutes() {
        DebugEvaluationPrompt prompt = new DebugEvaluationPrompt();
        assertFalse(prompt.active());

        prompt.start();
        "  count + 1 ".codePoints().forEach(prompt::type);
        assertTrue(prompt.submit());
        assertEquals(DebugEvaluationPrompt.Phase.CONFIRM, prompt.phase());
        assertEquals("count + 1", prompt.text(), "the reviewed expression is stripped");

        assertEquals("count + 1", prompt.confirm().orElseThrow());
        assertFalse(prompt.active(), "a confirmed prompt closes so it cannot run twice");
        assertTrue(prompt.confirm().isEmpty());
    }

    @Test
    void blankExpressionCancelsInsteadOfReviewing() {
        DebugEvaluationPrompt prompt = new DebugEvaluationPrompt();
        prompt.start();
        " ".codePoints().forEach(prompt::type);

        assertFalse(prompt.submit());
        assertFalse(prompt.active());
        assertTrue(prompt.confirm().isEmpty());
    }

    @Test
    void cancellingAfterReviewDiscardsTheExpression() {
        DebugEvaluationPrompt prompt = new DebugEvaluationPrompt();
        prompt.start();
        "secret()".codePoints().forEach(prompt::type);
        assertTrue(prompt.submit());

        prompt.cancel();

        assertFalse(prompt.active());
        assertTrue(prompt.confirm().isEmpty());
    }

    @Test
    void typingIsBoundedToTheRequestLimitAndBackspaceRemovesWholeCodePoints() {
        DebugEvaluationPrompt prompt = new DebugEvaluationPrompt();
        prompt.start();
        "x".repeat(EvaluationRequest.MAX_EXPRESSION_LENGTH + 20).codePoints().forEach(prompt::type);
        assertEquals(EvaluationRequest.MAX_EXPRESSION_LENGTH, prompt.text().length());

        prompt.start();
        "a😀".codePoints().forEach(prompt::type);
        prompt.backspace();
        assertEquals("a", prompt.text());
    }
}
