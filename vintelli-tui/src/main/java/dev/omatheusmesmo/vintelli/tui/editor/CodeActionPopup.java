package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CodeAction;

import java.util.List;
import java.util.Set;

/**
 * The two popups that list the code actions at the caret, drawn by the same overlay: quick fix asks for every
 * action the server offers, generate asks for the {@code source} kind and keeps the generate actions. This enum
 * holds what differs between them: the title, the words of the popup states, the kinds requested and the
 * commands that need a field selection before they can run.
 */
public enum CodeActionPopup {

    QUICK_FIX("Quick Fix", "quick fix", "Quick fixes", "Looking for quick fixes...", "No quick fixes available",
            List.of(), null, Set.of()),
    GENERATE("Generate", "generate", "Generate", "Looking for generate actions...", "Nothing to generate here",
            List.of("source"), "source.generate", jdtlsFieldPrompts());

    private final String title;
    private final String statusName;
    private final String noun;
    private final String loadingText;
    private final String emptyText;
    private final List<String> requestKinds;
    private final String keptKind;
    private final Set<String> fieldPromptCommands;

    CodeActionPopup(String title, String statusName, String noun, String loadingText, String emptyText,
            List<String> requestKinds, String keptKind, Set<String> fieldPromptCommands) {
        this.title = title;
        this.statusName = statusName;
        this.noun = noun;
        this.loadingText = loadingText;
        this.emptyText = emptyText;
        this.requestKinds = requestKinds;
        this.keptKind = keptKind;
        this.fieldPromptCommands = fieldPromptCommands;
    }

    public String title() {
        return title;
    }

    /**
     * @return the name the status line uses for an applied action, such as {@code quick fix}
     */
    public String statusName() {
        return statusName;
    }

    /**
     * @return the plural noun the unavailable and failed states start with, such as {@code Quick fixes}
     */
    public String noun() {
        return noun;
    }

    public String loadingText() {
        return loadingText;
    }

    public String emptyText() {
        return emptyText;
    }

    /**
     * @return the kinds the language server is asked for through {@code context.only}, empty for every kind
     */
    public List<String> requestKinds() {
        return requestKinds;
    }

    /**
     * @return whether the popup lists the action; a server may answer kinds the request did not ask for
     */
    public boolean keeps(CodeAction action) {
        return keptKind == null || action.kind().equals(keptKind) || action.kind().startsWith(keptKind + ".");
    }

    /**
     * @return whether the action is a command that needs a field selection before the server can answer it,
     *         so the popup lists it dimmed and does not run it
     */
    public boolean needsFieldSelection(CodeAction action) {
        return action.command() != null && fieldPromptCommands.contains(action.command().identifier());
    }

    /**
     * The prompt commands jdtls answers with when a generate action needs the client to pick fields first.
     * Picking the fields and the follow-up requests are not implemented yet, so these actions are listed only.
     */
    private static Set<String> jdtlsFieldPrompts() {
        return Set.of("java.action.generateAccessorsPrompt", "java.action.generateConstructorsPrompt",
                "java.action.hashCodeEqualsPrompt", "java.action.generateToStringPrompt");
    }
}
