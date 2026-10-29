package dev.omatheusmesmo.vintelli.idea;

import java.util.Map;

public record IdeaToolCall(String tool, String resultText, boolean isError) {

    public static IdeaToolCall ok(String tool, String resultText) {
        return new IdeaToolCall(tool, resultText, false);
    }

    public static IdeaToolCall failed(String tool, Map<String, Object> arguments, Exception cause) {
        String detail = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return new IdeaToolCall(tool, formattedFailure(arguments, detail), true);
    }

    private static String formattedFailure(Map<String, Object> arguments, String detail) {
        return "tool call failed: " + arguments.keySet() + " (" + detail + ")";
    }
}
