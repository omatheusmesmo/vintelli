package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit;
import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.WorkspaceEdit.DocumentEdit;
import dev.omatheusmesmo.vintelli.api.capability.RenameCapability;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs Rename Symbol: asks the rename capability for the suggested name while the input is open, and for the edits
 * once the user confirms a name. Both requests run on a virtual thread; their answers reach the render thread
 * through {@code deliver}, which is the only place the input and the editor change. A position the server rejects
 * closes the input and says {@code Cannot rename this element}; any other failure says why.
 */
public final class RenameController {

    static final String NOT_RENAMEABLE = "Cannot rename this element";
    static final String UNCHANGED = "Rename cancelled: the name is unchanged";
    private static final String NOT_RESOLVABLE = "not-resolvable";

    private final CapabilityRegistry registry;
    private final BuiltinEditor editor;
    private final RenameOverlay overlay;
    private final Consumer<Runnable> deliver;
    private final Consumer<String> status;
    private RenameSite site;
    private long generation;

    /**
     * @param deliver hands an update to the render thread, which applies it on its next pass
     * @param status shows a line in the status bar; called on the render thread only
     */
    public RenameController(CapabilityRegistry registry, BuiltinEditor editor, RenameOverlay overlay,
            Consumer<Runnable> deliver, Consumer<String> status) {
        this.registry = registry;
        this.editor = editor;
        this.overlay = overlay;
        this.deliver = deliver;
        this.status = status;
    }

    /**
     * Asks the server for the name it suggests for the symbol, on the render thread that opened the input.
     */
    public void begin(RenameSite asked) {
        site = asked;
        long ticket = ++generation;
        Thread.ofVirtual().name("vintelli-rename").start(() -> {
            CapabilityResult<String> answer = suggest(asked);
            deliver.accept(() -> suggested(ticket, answer));
        });
    }

    /**
     * Drops the answers still on their way, for example when the input closes.
     */
    public void cancel() {
        generation++;
    }

    /**
     * Renames the symbol to the confirmed name, on the render thread that confirmed it.
     */
    public void submit(String name) {
        RenameSite asked = site;
        generation++;
        if (asked == null) {
            return;
        }
        if (name.equals(asked.word())) {
            status.accept(UNCHANGED);
            return;
        }
        status.accept("Renaming to '" + name + "'...");
        Thread.ofVirtual().name("vintelli-rename").start(() -> {
            CapabilityResult<WorkspaceEdit> answer = rename(asked, name);
            deliver.accept(() -> renamed(name, answer));
        });
    }

    private void suggested(long ticket, CapabilityResult<String> answer) {
        if (ticket != generation || !overlay.isOpen()) {
            return;
        }
        if (answer instanceof CapabilityResult.Ok<String> ok) {
            overlay.suggest(ok.value());
            return;
        }
        dismiss();
        status.accept(failureMessage(answer.failureOf().orElseThrow()));
    }

    private void renamed(String name, CapabilityResult<WorkspaceEdit> answer) {
        if (answer instanceof CapabilityResult.Ok<WorkspaceEdit> ok) {
            status.accept(applied(name, ok.value()));
            return;
        }
        status.accept(failureMessage(answer.failureOf().orElseThrow()));
    }

    private String applied(String name, WorkspaceEdit edit) {
        WorkspaceEditApplier.Result result = editor.applyRename(edit);
        if (result instanceof WorkspaceEditApplier.Rejected rejected) {
            return "Rename not applied: " + rejected.reason();
        }
        int occurrences = ((WorkspaceEditApplier.Applied) result).editCount();
        long files = edit.documents().stream().filter(change -> !change.edits().isEmpty())
                .map(DocumentEdit::file).distinct().count();
        if (occurrences == 0) {
            return "Rename changed nothing";
        }
        return "Renamed to '" + name + "': " + count(occurrences, "occurrence") + " in " + count(files, "file");
    }

    private void dismiss() {
        overlay.close();
        editor.overlayInputActive(false);
    }

    private CapabilityResult<String> suggest(RenameSite asked) {
        return withProvider(provider -> provider.prepareRename(asked.file(), asked.content(), asked.line(),
                asked.column()));
    }

    private CapabilityResult<WorkspaceEdit> rename(RenameSite asked, String name) {
        return withProvider(provider -> provider.rename(asked.file(), asked.content(), asked.line(), asked.column(),
                name));
    }

    private <T> CapabilityResult<T> withProvider(
            Function<RenameCapability, CapabilityResult<T>> call) {
        if (registry == null) {
            return CapabilityResult.failed(new CapabilityFailure("unavailable", "capability registry is not configured"));
        }
        try {
            CapabilitySelection<RenameCapability> chosen = registry.rename();
            return switch (chosen) {
                case CapabilitySelection.Unavailable<RenameCapability> unavailable ->
                        CapabilityResult.failed(new CapabilityFailure("unavailable", unavailable.reason()));
                case CapabilitySelection.Selected<RenameCapability> selected -> call.apply(selected.provider());
            };
        } catch (RuntimeException failure) {
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return CapabilityResult.failed(new CapabilityFailure("failed", reason));
        }
    }

    private static String failureMessage(CapabilityFailure failure) {
        if (NOT_RESOLVABLE.equals(failure.reason())) {
            return NOT_RENAMEABLE;
        }
        return "Rename failed: " + (failure.detail().isBlank() ? failure.reason() : failure.detail());
    }

    private static String count(long amount, String noun) {
        return amount + " " + noun + (amount == 1 ? "" : "s");
    }
}
