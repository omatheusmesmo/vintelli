package dev.omatheusmesmo.vintelli.tui;

import java.util.Optional;

import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;

/**
 * Inline input of the value assigned to one variable of the suspended frame. It opens prefilled with
 * the value the variable shows, so Enter on an untouched prompt re-assigns the current value, and
 * Escape or an empty value closes it without assigning anything.
 */
final class DebugValuePrompt {

    private boolean active;
    private String variableName = "";
    private String text = "";

    boolean active() {
        return active;
    }

    String variableName() {
        return variableName;
    }

    String text() {
        return text;
    }

    void start(String name, String currentValue) {
        active = true;
        variableName = name;
        text = currentValue.length() > DebugCapability.MAX_SET_VALUE_LENGTH
                || currentValue.indexOf('\n') >= 0 || currentValue.indexOf('\r') >= 0 ? "" : currentValue;
    }

    void cancel() {
        active = false;
        variableName = "";
        text = "";
    }

    void type(int codePoint) {
        if (!active || codePoint < 32) {
            return;
        }
        if (text.codePointCount(0, text.length()) < DebugCapability.MAX_SET_VALUE_LENGTH) {
            text += Character.toString(codePoint);
        }
    }

    void backspace() {
        if (active && !text.isEmpty()) {
            text = text.substring(0, text.offsetByCodePoints(text.length(), -1));
        }
    }

    /** The stripped value to assign; the prompt closes either way, and a blank value yields nothing. */
    Optional<String> submit() {
        if (!active) {
            return Optional.empty();
        }
        String value = text.strip();
        cancel();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
