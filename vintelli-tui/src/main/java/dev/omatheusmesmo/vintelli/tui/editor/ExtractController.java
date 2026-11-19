package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CodeAction;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the extract refactorings on the selection, or the caret when nothing is selected. The language server is
 * asked for the {@code refactor.extract} kind of one extraction off the render thread; the one action of that kind
 * is applied on the render thread as one undoable step, and the caret goes to the first occurrence of the name the
 * edit declares. Nothing extractable, a missing language server and a failed request each end in a status line that
 * says why.
 */
public final class ExtractController {

    static final String NOTHING_TO_EXTRACT = "Nothing to extract here";

    private final CapabilityRegistry registry;
    private final BuiltinEditor editor;
    private final Consumer<Runnable> deliver;
    private final Consumer<String> status;

    /**
     * @param deliver hands the outcome of an extraction to the render thread, which applies it on its next pass
     * @param status shows a line in the status bar; called on the render thread only
     */
    public ExtractController(CapabilityRegistry registry, BuiltinEditor editor, Consumer<Runnable> deliver,
            Consumer<String> status) {
        this.registry = registry;
        this.editor = editor;
        this.deliver = deliver;
        this.status = status;
    }

    /**
     * Starts one extraction of the active document on a virtual thread.
     *
     * @return the status line the shell shows until the outcome arrives
     */
    public String run(Extraction extraction) {
        Optional<QuickFixRequest> asked = editor == null ? Optional.empty() : editor.quickFixRequest();
        if (asked.isEmpty()) {
            return "Open a file to extract from";
        }
        QuickFixRequest request = asked.get();
        Thread.ofVirtual().name("vintelli-extract").start(() -> deliver.accept(answer(extraction, request)));
        return "Looking for " + extraction.label() + "...";
    }

    /**
     * The kinds of the extract refactorings, the names of the variable or method the edit declares, and the
     * choice among several actions of one kind.
     */
    public enum Extraction {
        VARIABLE("refactor.extract.variable", "Extract Variable", Pattern.compile("([A-Za-z_$][\\w$]*)\\s*=(?!=)")),
        METHOD("refactor.extract.function", "Extract Method", Pattern.compile("([A-Za-z_$][\\w$]*)\\s*\\(")),
        CONSTANT("refactor.extract.constant", "Extract Constant", Pattern.compile("([A-Za-z_$][\\w$]*)\\s*=(?!=)"));

        private static final String ALL_OCCURRENCES = "all occurrences";

        private final String kind;
        private final String label;
        private final Pattern declaration;

        Extraction(String kind, String label, Pattern declaration) {
            this.kind = kind;
            this.label = label;
            this.declaration = declaration;
        }

        String kind() {
            return kind;
        }

        String label() {
            return label;
        }

        /**
         * Picks the action the extraction applies. A variable extraction prefers the action that replaces all
         * occurrences of the expression; the first action wins otherwise.
         */
        CodeAction choose(List<CodeAction> candidates) {
            if (this == VARIABLE) {
                return candidates.stream()
                        .filter(action -> action.title().toLowerCase(Locale.ROOT).contains(ALL_OCCURRENCES))
                        .findFirst()
                        .orElse(candidates.getFirst());
            }
            return candidates.getFirst();
        }

        /**
         * @return the name the first text edit that declares one introduces, empty when none does
         */
        Optional<String> declaredName(WorkspaceEdit edit) {
            for (WorkspaceEdit.DocumentEdit document : edit.documents()) {
                for (WorkspaceEdit.TextEdit text : document.edits()) {
                    Matcher matcher = declaration.matcher(text.newText());
                    if (matcher.find()) {
                        return Optional.of(matcher.group(1));
                    }
                }
            }
            return Optional.empty();
        }
    }

    private Runnable answer(Extraction extraction, QuickFixRequest request) {
        try {
            if (registry == null) {
                return unavailable(extraction, "capability registry is not configured");
            }
            CapabilitySelection<CodeActionCapability> chosen = registry.codeActions();
            return switch (chosen) {
                case CapabilitySelection.Unavailable<CodeActionCapability> unavailable ->
                        unavailable(extraction, unavailable.reason());
                case CapabilitySelection.Selected<CodeActionCapability> selected ->
                        ask(selected.provider(), extraction, request);
            };
        } catch (RuntimeException failure) {
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return () -> status.accept(extraction.label() + " failed: " + reason);
        }
    }

    private Runnable ask(CodeActionCapability provider, Extraction extraction, QuickFixRequest request) {
        CapabilityResult<List<CodeAction>> result = provider.codeActions(request.file(), request.range(),
                request.diagnostics(), List.of(extraction.kind()));
        if (result instanceof CapabilityResult.Ok<List<CodeAction>> ok) {
            List<CodeAction> matching = ok.value().stream()
                    .filter(action -> extraction.kind().equals(action.kind()))
                    .toList();
            if (matching.isEmpty()) {
                return () -> status.accept(NOTHING_TO_EXTRACT);
            }
            CodeAction chosen = extraction.choose(matching);
            return () -> status.accept(apply(extraction, chosen, request));
        }
        String reason = result.failureOf()
                .map(failure -> failure.detail().isBlank() ? failure.reason()
                        : failure.reason() + ": " + failure.detail())
                .orElse("no answer from the language server");
        return () -> status.accept(extraction.label() + " failed: " + reason);
    }

    private Runnable unavailable(Extraction extraction, String reason) {
        return () -> status.accept(extraction.label() + " unavailable: " + reason);
    }

    private String apply(Extraction extraction, CodeAction action, QuickFixRequest request) {
        if (action.edit() == null) {
            return extraction.label() + " needs a command Vintelli does not run for this language server yet";
        }
        WorkspaceEditApplier.Result result = editor.applyQuickFix(request, action.edit());
        if (result instanceof WorkspaceEditApplier.Rejected rejected) {
            return extraction.label() + " not applied: " + rejected.reason();
        }
        int edits = ((WorkspaceEditApplier.Applied) result).editCount();
        if (edits == 0) {
            return NOTHING_TO_EXTRACT;
        }
        extraction.declaredName(action.edit()).ifPresent(this::placeCaretOnFirstOccurrence);
        return extraction.label() + " applied: " + action.title() + " (" + edits + (edits == 1 ? " edit" : " edits")
                + ")";
    }

    private void placeCaretOnFirstOccurrence(String name) {
        String text = editor.activeDocument().orElseThrow().text();
        Matcher occurrence = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(text);
        if (!occurrence.find()) {
            return;
        }
        int offset = occurrence.start();
        int line = (int) text.substring(0, offset).chars().filter(character -> character == '\n').count() + 1;
        int column = offset - text.lastIndexOf('\n', offset - 1);
        editor.goToLine(line, column);
    }
}
