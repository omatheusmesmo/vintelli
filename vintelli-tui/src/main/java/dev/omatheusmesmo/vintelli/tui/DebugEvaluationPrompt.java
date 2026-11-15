package dev.omatheusmesmo.vintelli.tui;

import java.util.Optional;

import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.EvaluationRequest;

/**
 * Input and confirmation state of one debugger expression. Running code in the debuggee needs a
 * second, separate key after the expression is typed, so Enter alone never executes anything.
 */
final class DebugEvaluationPrompt {

    enum Phase { IDLE, INPUT, CONFIRM }

    private Phase phase = Phase.IDLE;
    private String text = "";

    boolean active() {
        return phase != Phase.IDLE;
    }

    Phase phase() {
        return phase;
    }

    String text() {
        return text;
    }

    void start() {
        phase = Phase.INPUT;
        text = "";
    }

    void cancel() {
        phase = Phase.IDLE;
        text = "";
    }

    void type(int codePoint) {
        if (phase != Phase.INPUT || codePoint < 32) {
            return;
        }
        if (text.codePointCount(0, text.length()) < EvaluationRequest.MAX_EXPRESSION_LENGTH) {
            text += Character.toString(codePoint);
        }
    }

    void backspace() {
        if (phase == Phase.INPUT && !text.isEmpty()) {
            text = text.substring(0, text.offsetByCodePoints(text.length(), -1));
        }
    }

    /** Moves from typing to confirmation; a blank expression cancels the prompt instead. */
    boolean submit() {
        if (phase != Phase.INPUT) {
            return false;
        }
        if (text.isBlank()) {
            cancel();
            return false;
        }
        text = text.strip();
        phase = Phase.CONFIRM;
        return true;
    }

    /** The approved expression, present only once the prompt waits for confirmation; the prompt then closes. */
    Optional<String> confirm() {
        if (phase != Phase.CONFIRM) {
            return Optional.empty();
        }
        String approved = text;
        cancel();
        return Optional.of(approved);
    }
}
