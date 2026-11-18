package dev.omatheusmesmo.vintelli.tui.editor;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.HoverCapability;
import dev.omatheusmesmo.vintelli.api.capability.HoverCapability.Documentation;
import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;

/**
 * Runs the quick documentation popup: asks the hover capability for the symbol at the caret on a virtual thread and
 * hands the answer to the render thread through {@code deliver}, which draws it through {@link QuickDocumentationMenu}.
 * While the popup is open the editor leaves the keys it takes to the shell, see {@link BuiltinEditor}. An answer that
 * arrives after the caret or the text moved is dropped: the popup closes and nothing is shown for the old position.
 */
public final class QuickDocumentationController {

    private final CapabilityRegistry registry;
    private final BuiltinEditor editor;
    private final Consumer<Runnable> deliver;
    private final QuickDocumentationMenu menu = new QuickDocumentationMenu();

    /**
     * @param deliver hands an update to the render thread, which applies it on its next pass
     */
    public QuickDocumentationController(CapabilityRegistry registry, BuiltinEditor editor, Consumer<Runnable> deliver) {
        this.registry = registry;
        this.editor = editor;
        this.deliver = deliver;
    }

    /**
     * Opens the popup in the loading state for the document site and asks the provider off the render thread.
     */
    public void open(CompletionSite site) {
        long ticket = menu.begin();
        Thread.ofVirtual().name("vintelli-quick-documentation").start(() -> {
            CapabilityResult<List<HoverText.Row>> result = fetch(site);
            deliver.accept(() -> receive(ticket, site, result));
        });
    }

    public void close() {
        menu.close();
    }

    public boolean isOpen() {
        return menu.isOpen();
    }

    /**
     * @return whether the open popup takes the key; a key it does not take closes the popup and reaches the editor
     */
    public boolean takes(OverlayKey key) {
        return menu.takes(key);
    }

    public void press(OverlayKey key) {
        menu.press(key);
    }

    public void wheel(int direction) {
        menu.wheel(direction);
    }

    QuickDocumentationMenu.View view() {
        return menu.view();
    }

    void layout(OverlayRegion inner) {
        menu.layout(inner);
    }

    private CapabilityResult<List<HoverText.Row>> fetch(CompletionSite site) {
        try {
            if (registry == null) {
                return CapabilityResult.failed(new CapabilityFailure("unavailable",
                        "capability registry is not configured"));
            }
            CapabilitySelection<HoverCapability> chosen = registry.hover();
            return switch (chosen) {
                case CapabilitySelection.Unavailable<HoverCapability> unavailable ->
                        CapabilityResult.failed(new CapabilityFailure("unavailable", unavailable.reason()));
                case CapabilitySelection.Selected<HoverCapability> selected ->
                        documentationOf(selected.provider().hover(site.file(), site.text(), site.row(), site.column()));
            };
        } catch (RuntimeException failure) {
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return CapabilityResult.failed(new CapabilityFailure("failed", reason));
        }
    }

    private static CapabilityResult<List<HoverText.Row>> documentationOf(CapabilityResult<Documentation> result) {
        if (result instanceof CapabilityResult.Ok<Documentation> ok) {
            return CapabilityResult.ok(HoverText.parse(ok.value().text()));
        }
        return CapabilityResult.failed(result.failureOf().orElseThrow());
    }

    private void receive(long ticket, CompletionSite requested, CapabilityResult<List<HoverText.Row>> result) {
        if (!menu.awaits(ticket)) {
            return;
        }
        if (!isCurrent(requested)) {
            close();
            return;
        }
        if (result instanceof CapabilityResult.Ok<List<HoverText.Row>> ok) {
            menu.showDocumentation(ticket, ok.value());
            return;
        }
        CapabilityFailure failure = result.failureOf().orElseThrow();
        String reason = failure.detail().isBlank() ? failure.reason() : failure.detail();
        if ("unavailable".equals(failure.reason())) {
            menu.showUnavailable(ticket, reason);
        } else {
            menu.showFailure(ticket, reason);
        }
    }

    private boolean isCurrent(CompletionSite requested) {
        Optional<CompletionSite> now = editor == null ? Optional.empty() : editor.completionSite();
        return now.isPresent() && now.get().equals(requested);
    }
}
