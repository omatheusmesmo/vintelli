package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.CodeAction;
import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;

import java.util.List;
import java.util.function.Consumer;

/**
 * Runs the quick fix popup: asks the code action capability for the request off the render thread, shows
 * the answer through {@link QuickFixMenu}, and applies the chosen action's edit on the thread that presses
 * Enter. The capability is only asked on an explicit request, never on caret moves.
 */
public final class QuickFixController {

    private final CapabilityRegistry registry;
    private final BuiltinEditor editor;
    private final Consumer<Runnable> deliver;
    private final QuickFixMenu menu = new QuickFixMenu();
    private QuickFixRequest request;

    /**
     * @param deliver hands the update of the popup to the render thread, which applies it on its next pass
     */
    public QuickFixController(CapabilityRegistry registry, BuiltinEditor editor, Consumer<Runnable> deliver) {
        this.registry = registry;
        this.editor = editor;
        this.deliver = deliver;
    }

    /**
     * Opens the popup in the loading state and asks the provider on a virtual thread.
     *
     * @param asked the active file, the range and the diagnostics on it
     */
    public void open(QuickFixRequest asked) {
        request = asked;
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
                case CapabilitySelection.Selected<CodeActionCapability> selected ->
                        ask(selected.provider(), asked, ticket);
            };
        } catch (RuntimeException failure) {
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return () -> menu.showFailure(ticket, reason);
        }
    }

    private Runnable ask(CodeActionCapability provider, QuickFixRequest asked, long ticket) {
        CapabilityResult<List<CodeAction>> result = provider.codeActions(asked.file(), asked.range(),
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
}
