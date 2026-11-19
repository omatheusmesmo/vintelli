package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CodeAction;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.FormattingOptions;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.DocumentEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.TextEdit;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs the formatting of the active document and its organize imports action. The request asks the language
 * server with the text the editor holds, on a virtual thread, and the answer is applied on the thread that
 * delivers updates, as one undoable step. Reformat formats the selected lines when there is a selection and the
 * whole document otherwise; organize imports runs the server's {@code source.organizeImports} action. Every
 * outcome is said on the status line, including a server that is not there or cannot answer.
 */
public final class FormatController {

    private static final String ORGANIZE_IMPORTS_KIND = "source.organizeImports";
    private static final String UNCHANGED = "; nothing was changed";

    private final CapabilityRegistry registry;
    private final BuiltinEditor editor;
    private final Consumer<Runnable> deliver;
    private final Consumer<String> status;

    /**
     * @param deliver hands the outcome to the render thread, which applies it on its next pass
     * @param status shows a line in the status bar; called on the render thread only
     */
    public FormatController(CapabilityRegistry registry, BuiltinEditor editor, Consumer<Runnable> deliver,
            Consumer<String> status) {
        this.registry = registry;
        this.editor = editor;
        this.deliver = deliver;
        this.status = status;
    }

    /**
     * Starts the reformat of the active document, or of its selected lines.
     *
     * @return the status line the shell shows at once
     */
    public String reformat() {
        Optional<FormatSite> site = editor.formatSite();
        if (site.isEmpty()) {
            return "Open a file to reformat";
        }
        FormatSite asked = site.get();
        Thread.ofVirtual().name("vintelli-reformat").start(() -> {
            CapabilityResult<List<TextEdit>> outcome = formatted(asked);
            deliver.accept(() -> status.accept(finishReformat(asked, outcome)));
        });
        return "Reformatting...";
    }

    /**
     * Starts the organize imports of the active document.
     *
     * @return the status line the shell shows at once
     */
    public String optimizeImports() {
        Optional<FormatSite> site = editor.formatSite();
        if (site.isEmpty()) {
            return "Open a file to optimize imports in";
        }
        FormatSite asked = site.get();
        Thread.ofVirtual().name("vintelli-organize-imports").start(() -> {
            CapabilityResult<List<CodeAction>> outcome = organized(asked);
            deliver.accept(() -> status.accept(finishImports(asked, outcome)));
        });
        return "Optimizing imports...";
    }

    private CapabilityResult<List<TextEdit>> formatted(FormatSite asked) {
        return withProvider(provider -> {
            FormattingOptions options = EditorIndent.formattingOptions(asked.text());
            return asked.selection() == null
                    ? provider.formatDocument(asked.file(), asked.text(), options)
                    : provider.formatRange(asked.file(), asked.text(), asked.selection(), options);
        });
    }

    private CapabilityResult<List<CodeAction>> organized(FormatSite asked) {
        return withProvider(provider -> provider.organizeImports(asked.file(), asked.text()));
    }

    private <T> CapabilityResult<T> withProvider(
            Function<CodeActionCapability, CapabilityResult<T>> request) {
        if (registry == null) {
            return CapabilityResult.failed(new CapabilityFailure("unavailable", "capability registry is not configured"));
        }
        try {
            return switch (registry.codeActions()) {
                case CapabilitySelection.Unavailable<CodeActionCapability> unavailable ->
                        CapabilityResult.failed(new CapabilityFailure("unavailable", unavailable.reason()));
                case CapabilitySelection.Selected<CodeActionCapability> selected -> request.apply(selected.provider());
            };
        } catch (RuntimeException failure) {
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return CapabilityResult.failed(new CapabilityFailure("failed", reason));
        }
    }

    private String finishReformat(FormatSite asked, CapabilityResult<List<TextEdit>> outcome) {
        Optional<CapabilityFailure> failure = outcome.failureOf();
        if (failure.isPresent()) {
            return failureText("Reformat", failure.get());
        }
        if (!(outcome instanceof CapabilityResult.Ok<List<TextEdit>> ok) || ok.value().isEmpty()) {
            return "Already formatted";
        }
        WorkspaceEdit edit = new WorkspaceEdit(List.of(new DocumentEdit(asked.file(), ok.value())), List.of());
        return applied(editor.applyFormatting(asked, edit), "Reformat", "Reformatted", "Already formatted");
    }

    private String finishImports(FormatSite asked, CapabilityResult<List<CodeAction>> outcome) {
        Optional<CapabilityFailure> failure = outcome.failureOf();
        if (failure.isPresent()) {
            return failureText("Organize imports", failure.get());
        }
        Optional<CodeAction> action = outcome instanceof CapabilityResult.Ok<List<CodeAction>> ok
                ? ok.value().stream().filter(candidate -> candidate.kind().startsWith(ORGANIZE_IMPORTS_KIND)).findFirst()
                : Optional.empty();
        if (action.isEmpty()) {
            return "Imports already optimized";
        }
        if (action.get().edit() == null) {
            return "Organize imports needs a command Vintelli does not run yet";
        }
        return applied(editor.applyFormatting(asked, action.get().edit()), "Organize imports", "Imports optimized",
                "Imports already optimized");
    }

    private static String applied(WorkspaceEditApplier.Result result, String action, String changed,
            String unchanged) {
        if (result instanceof WorkspaceEditApplier.Rejected rejected) {
            return action + " not applied: " + rejected.reason() + UNCHANGED;
        }
        return ((WorkspaceEditApplier.Applied) result).editCount() == 0 ? unchanged : changed;
    }

    private static String failureText(String action, CapabilityFailure failure) {
        String detail = failure.detail().isBlank() ? failure.reason() : failure.detail();
        return action + " did not complete: " + detail + UNCHANGED;
    }
}
