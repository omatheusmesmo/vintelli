package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CodeAction;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CommandRef;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.DocumentEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEditSink;
import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Runs the quick fix popup: asks the code action capability for the request off the render thread, shows
 * the answer through {@link QuickFixMenu}, and applies the chosen action on the thread that presses Enter.
 * An action with a command runs off the render thread; the workspace edits the server sends while it runs,
 * together with the action's own edit, are applied as one undoable step only when the command completes,
 * so a command that times out or loses its connection leaves the document unchanged. The capability is only
 * asked on an explicit request, never on caret moves.
 */
public final class QuickFixController {

    private final CapabilityRegistry registry;
    private final BuiltinEditor editor;
    private final Consumer<Runnable> deliver;
    private final Consumer<String> status;
    private final QuickFixMenu menu = new QuickFixMenu();
    private QuickFixRequest request;
    private volatile CodeActionCapability provider;

    /**
     * @param deliver hands the update of the popup to the render thread, which applies it on its next pass
     * @param status shows a line in the status bar; called on the render thread only
     */
    public QuickFixController(CapabilityRegistry registry, BuiltinEditor editor, Consumer<Runnable> deliver,
            Consumer<String> status) {
        this.registry = registry;
        this.editor = editor;
        this.deliver = deliver;
        this.status = status;
    }

    /**
     * Opens the popup in the loading state and asks the provider on a virtual thread.
     *
     * @param asked the active file, the range and the diagnostics on it
     */
    public void open(QuickFixRequest asked) {
        request = asked;
        provider = null;
        long ticket = menu.begin();
        Thread.ofVirtual().name("vintelli-quick-fix").start(() -> deliver.accept(answer(asked, ticket)));
    }

    public void close() {
        menu.close();
    }

    public boolean isOpen() {
        return menu.isOpen();
    }

    /**
     * Routes one key to the popup and applies the action Enter confirms.
     *
     * @return the status line the shell shows, empty when the key changed nothing to report
     */
    public String press(OverlayKey key) {
        return menu.press(key).map(this::apply).orElse("");
    }

    /**
     * Routes one pointer event to the popup.
     *
     * @return the status line the shell shows, empty when the event changed nothing to report
     */
    public String pointer(int x, int y, boolean click, int scroll, long nanos) {
        return menu.pointer(x, y, click, scroll, nanos).map(this::apply).orElse("");
    }

    QuickFixMenu.View view() {
        return menu.view();
    }

    void layout(OverlayRegion panel, OverlayRegion inner) {
        menu.layout(panel, inner);
    }

    private Runnable answer(QuickFixRequest asked, long ticket) {
        try {
            if (registry == null) {
                return () -> menu.showUnavailable(ticket, "capability registry is not configured");
            }
            CapabilitySelection<CodeActionCapability> chosen = registry.codeActions();
            return switch (chosen) {
                case CapabilitySelection.Unavailable<CodeActionCapability> unavailable ->
                        () -> menu.showUnavailable(ticket, unavailable.reason());
                case CapabilitySelection.Selected<CodeActionCapability> selected -> {
                    provider = selected.provider();
                    yield ask(selected.provider(), asked, ticket);
                }
            };
        } catch (RuntimeException failure) {
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return () -> menu.showFailure(ticket, reason);
        }
    }

    private Runnable ask(CodeActionCapability runner, QuickFixRequest asked, long ticket) {
        CapabilityResult<List<CodeAction>> result = runner.codeActions(asked.file(), asked.range(),
                asked.diagnostics());
        if (result instanceof CapabilityResult.Ok<List<CodeAction>> ok) {
            List<CodeAction> actions = ok.value();
            return () -> menu.showActions(ticket, actions);
        }
        String reason = result.failureOf()
                .map(failure -> failure.detail().isBlank() ? failure.reason()
                        : failure.reason() + ": " + failure.detail())
                .orElse("no answer from the language server");
        return () -> menu.showFailure(ticket, reason);
    }

    private String apply(CodeAction action) {
        CodeActionCapability runner = provider;
        if (action.command() != null && runner != null && runner.supportsCommands()) {
            return runCommand(action, runner);
        }
        if (!QuickFixMenu.appliesEdit(action)) {
            String notice = "This action needs a command Vintelli does not run yet; pick another action";
            menu.say(notice);
            return notice;
        }
        WorkspaceEditApplier.Result result = editor.applyQuickFix(request, action.edit());
        if (result instanceof WorkspaceEditApplier.Rejected rejected) {
            String notice = "Quick fix not applied: " + rejected.reason();
            menu.say(notice);
            return notice;
        }
        menu.close();
        int edits = ((WorkspaceEditApplier.Applied) result).editCount();
        String commandNote = action.command() == null ? "" : "; its follow-up command is not run yet";
        return edits == 0 ? "Quick fix changed nothing" + commandNote
                : "Quick fix applied: " + action.title() + " (" + edits + (edits == 1 ? " edit" : " edits") + ")"
                        + commandNote;
    }

    private String runCommand(CodeAction action, CodeActionCapability runner) {
        QuickFixRequest asked = request;
        List<WorkspaceEdit> collected = Collections.synchronizedList(new ArrayList<>());
        if (action.edit() != null) {
            String refusal = WorkspaceEditApplier.refusal(asked.file(), action.edit());
            if (refusal != null) {
                String notice = "Quick fix not applied: " + refusal;
                menu.say(notice);
                return notice;
            }
            collected.add(action.edit());
        }
        menu.close();
        Thread.ofVirtual().name("vintelli-quick-fix-command").start(() -> deliver.accept(
                finishCommand(action, asked, collected, execute(runner, action.command(), asked, collected))));
        return "Running " + action.title() + "...";
    }

    private static CapabilityResult<Integer> execute(CodeActionCapability runner, CommandRef command,
            QuickFixRequest asked, List<WorkspaceEdit> collected) {
        try {
            return runner.executeCommand(command, edit -> collect(asked, edit, collected));
        } catch (RuntimeException failure) {
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return CapabilityResult.failed(new CapabilityFailure("failed", reason));
        }
    }

    private static WorkspaceEditSink.Decision collect(QuickFixRequest asked, WorkspaceEdit edit,
            List<WorkspaceEdit> collected) {
        String refusal = WorkspaceEditApplier.refusal(asked.file(), edit);
        if (refusal != null) {
            return WorkspaceEditSink.Decision.refuse(refusal);
        }
        collected.add(edit);
        return WorkspaceEditSink.Decision.accept();
    }

    private Runnable finishCommand(CodeAction action, QuickFixRequest asked, List<WorkspaceEdit> collected,
            CapabilityResult<Integer> outcome) {
        List<WorkspaceEdit> received = List.copyOf(collected);
        return () -> status.accept(commandOutcome(action, asked, received, outcome));
    }

    private String commandOutcome(CodeAction action, QuickFixRequest asked, List<WorkspaceEdit> received,
            CapabilityResult<Integer> outcome) {
        Optional<CapabilityFailure> failure = outcome.failureOf();
        if (failure.isPresent()) {
            String detail = failure.get().detail().isBlank() ? failure.get().reason() : failure.get().detail();
            return "Quick fix '" + action.title() + "' did not complete: " + detail
                    + "; nothing was applied, the document is unchanged";
        }
        if (received.isEmpty()) {
            return "Quick fix '" + action.title() + "' ran and offered no change";
        }
        WorkspaceEditApplier.Result result = editor.applyQuickFix(asked, merged(received));
        if (result instanceof WorkspaceEditApplier.Rejected rejected) {
            return "Quick fix not applied: " + rejected.reason() + "; the document is unchanged";
        }
        int edits = ((WorkspaceEditApplier.Applied) result).editCount();
        return edits == 0 ? "Quick fix changed nothing"
                : "Quick fix applied: " + action.title() + " (" + edits + (edits == 1 ? " edit" : " edits") + ")";
    }

    private static WorkspaceEdit merged(List<WorkspaceEdit> edits) {
        List<DocumentEdit> documents = new ArrayList<>();
        for (WorkspaceEdit edit : edits) {
            documents.addAll(edit.documents());
        }
        return new WorkspaceEdit(documents, List.of());
    }
}
