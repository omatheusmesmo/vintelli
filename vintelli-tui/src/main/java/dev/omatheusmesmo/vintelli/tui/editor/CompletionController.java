package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.CapabilitySelection;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Replacement;
import dev.omatheusmesmo.vintelli.api.capability.CompletionCapability.Suggestion;
import dev.omatheusmesmo.vintelli.tui.Keys;
import dev.omatheusmesmo.vintelli.tui.OverlayInput;
import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs the completion popup. Ctrl+Space and a dot typed behind an identifier in a Java file ask the completion
 * capability on a virtual thread; the answer reaches the render thread through {@code deliver}. The editor keeps
 * the keyboard while the popup is open: typing narrows the list by the identifier before the caret, and the popup
 * closes when the caret leaves the text it was asked for or nothing matches. An answer that arrives after the
 * document or the caret changed is dropped, and the completion is asked again for the current text.
 *
 * <p>A suggestion without additional edits that the provider can resolve (such as an import the server sends
 * only on resolve) is resolved on a virtual thread with a short timeout when it is inserted. The insert waits for
 * that answer; when the answer fails, the name is inserted without the import and the status line says why.
 */
public final class CompletionController {

    private static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(1);

    private final CapabilityRegistry registry;
    private final BuiltinEditor editor;
    private final Consumer<Runnable> deliver;
    private final Consumer<String> status;
    private final CompletionMenu menu = new CompletionMenu();
    private CompletionSite asked;
    private int anchor;

    /**
     * @param deliver hands an update to the render thread, which applies it on its next pass
     * @param status shows a line in the status bar; called on the render thread only
     */
    public CompletionController(CapabilityRegistry registry, BuiltinEditor editor, Consumer<Runnable> deliver,
            Consumer<String> status) {
        this.registry = registry;
        this.editor = editor;
        this.deliver = deliver;
        this.status = status;
    }

    /**
     * Ctrl+Space: asks for the completions at the caret, replacing a popup that is open.
     *
     * @return the status line the shell shows
     */
    public String openAtCaret() {
        Optional<CompletionSite> site = editor.completionSite();
        if (site.isEmpty()) {
            return "Open a file to complete code in";
        }
        start(site.get());
        return "Looking for completions...";
    }

    public void close() {
        menu.close();
    }

    public boolean isOpen() {
        return menu.isOpen();
    }

    /**
     * @return whether the open popup takes the key: Enter, Tab, Escape, the arrow keys and the page keys. The
     *         editor leaves these keys to the shell while the popup is open, and every other key is typed text
     *         or a caret move the editor handles itself.
     */
    public boolean takesKey(KeyEvent key) {
        return menu.isOpen() && navigationKey(key) != null;
    }

    /**
     * Routes one key to the popup while it is open. Enter and Tab insert the selected suggestion, Escape closes
     * the popup, and the arrow and page keys move the selection; every other key goes on to the editor.
     *
     * @return the status line when the popup takes the key, empty status when it changed nothing to report,
     *         empty when the key is not the popup's
     */
    public Optional<String> press(KeyEvent key) {
        if (!menu.isOpen()) {
            return Optional.empty();
        }
        OverlayKey mapped = navigationKey(key);
        if (mapped == null) {
            return Optional.empty();
        }
        if (mapped == OverlayKey.ENTER) {
            return Optional.of(menu.press(OverlayKey.ENTER).map(this::apply).orElse(""));
        }
        menu.press(mapped);
        return Optional.of("");
    }

    /**
     * Routes one pointer event to the popup. Events outside the popup are not taken, so the caller can close the
     * popup and hand the event to the editor.
     *
     * @return the status line when the popup takes the event, empty when the event is outside it
     */
    public Optional<String> pointer(int x, int y, boolean click, int scroll) {
        if (!menu.isOpen() || !menu.contains(x, y)) {
            return Optional.empty();
        }
        if (scroll != 0) {
            menu.scroll(scroll);
            return Optional.of("");
        }
        if (!click) {
            return Optional.of("");
        }
        return Optional.of(menu.click(x, y).map(this::apply).orElse(""));
    }

    /**
     * Brings the popup in line with a key the editor changed the text or the caret with: the typed prefix is
     * narrowed, or the popup closes when the caret left the completed text. A dot typed behind an identifier in a
     * Java file opens the popup when none is open.
     */
    public void afterEditorKey(KeyEvent key) {
        refresh();
        if (!menu.isOpen() && isDot(key)) {
            openAfterDot();
        }
    }

    /**
     * Closes an open popup after a key the editor did not handle, unless the key started a new request. Keys
     * that edit the text or move the caret leave the popup to the editor, which reports them through
     * {@link #afterEditorKey(KeyEvent)}.
     *
     * @param ticketBefore {@link #ticket()} before the key, so a key that reopened the popup is left alone
     */
    public void afterShellKey(KeyEvent key, long ticketBefore) {
        if (menu.isOpen() && menu.ticket() == ticketBefore && !isEditing(key)) {
            menu.close();
        }
    }

    /**
     * @return a number that changes whenever a request starts, so a key can tell whether it reopened the popup
     */
    public long ticket() {
        return menu.ticket();
    }

    CompletionMenu.View view() {
        return menu.view();
    }

    void layout(OverlayRegion panel, OverlayRegion inner) {
        menu.layout(panel, inner);
    }

    /**
     * Narrows the open popup to the identifier typed since the request, or closes it when the caret left that
     * text or no suggestion matches.
     */
    void refresh() {
        if (!menu.isOpen()) {
            return;
        }
        Optional<CompletionSite> now = editor.completionSite();
        if (now.isEmpty() || !continues(now.get())) {
            menu.close();
            return;
        }
        if (!menu.filter(typed(now.get()))) {
            menu.close();
        }
    }

    private void start(CompletionSite site) {
        asked = site;
        anchor = site.identifierStart();
        long ticket = menu.begin();
        Thread.ofVirtual().name("vintelli-completion").start(() -> {
            CapabilityResult<List<Suggestion>> result = fetch(site);
            deliver.accept(() -> receive(ticket, site, result));
        });
    }

    private CapabilityResult<List<Suggestion>> fetch(CompletionSite site) {
        return withProvider(provider -> provider.complete(site.file(), site.text(), site.row(), site.column()));
    }

    private CapabilityResult<List<Replacement>> resolveWith(String token) {
        return withProvider(provider -> provider.resolve(token, RESOLVE_TIMEOUT));
    }

    private <T> CapabilityResult<T> withProvider(Function<CompletionCapability, CapabilityResult<T>> call) {
        try {
            if (registry == null) {
                return CapabilityResult.failed(new CapabilityFailure("unavailable",
                        "capability registry is not configured"));
            }
            CapabilitySelection<CompletionCapability> chosen = registry.completion();
            return switch (chosen) {
                case CapabilitySelection.Unavailable<CompletionCapability> unavailable ->
                        CapabilityResult.failed(new CapabilityFailure("unavailable", unavailable.reason()));
                case CapabilitySelection.Selected<CompletionCapability> selected -> call.apply(selected.provider());
            };
        } catch (RuntimeException failure) {
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return CapabilityResult.failed(new CapabilityFailure("failed", reason));
        }
    }

    private void receive(long ticket, CompletionSite requested, CapabilityResult<List<Suggestion>> result) {
        if (!menu.awaits(ticket)) {
            return;
        }
        Optional<CompletionSite> now = editor.completionSite();
        if (now.isEmpty()) {
            menu.close();
            return;
        }
        if (!now.get().equals(requested)) {
            start(now.get());
            return;
        }
        if (result instanceof CapabilityResult.Ok<List<Suggestion>> ok) {
            menu.showSuggestions(ticket, ok.value());
            refresh();
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

    private String apply(Suggestion suggestion) {
        Optional<CompletionSite> now = editor.completionSite();
        if (now.isEmpty() || !continues(now.get())) {
            menu.close();
            return "Completion dropped: the caret moved away from the completed text";
        }
        CompletionSite site = now.get();
        Insertion insertion = insertionOf(suggestion, site);
        if (insertion == null) {
            menu.close();
            return "Completion not applied: the suggested range does not match the caret line";
        }
        menu.close();
        if (suggestion.resolveToken() != null) {
            return resolveThenInsert(site, insertion, suggestion);
        }
        return insert(site, insertion, suggestion.additionalEdits(), "Completion inserted: " + suggestion.label());
    }

    /**
     * Asks the provider for the edits of the suggestion on a virtual thread; the insert follows on the render
     * thread once the answer or the timeout arrives.
     *
     * @return the status line while the answer is awaited
     */
    private String resolveThenInsert(CompletionSite site, Insertion insertion, Suggestion suggestion) {
        String label = suggestion.label();
        String token = suggestion.resolveToken();
        Thread.ofVirtual().name("vintelli-completion-resolve").start(() -> {
            CapabilityResult<List<Replacement>> resolved = resolveWith(token);
            deliver.accept(() -> status.accept(finishResolve(site, insertion, label, resolved)));
        });
        return "Resolving the import of " + label + "...";
    }

    /**
     * Inserts the suggestion once its edits arrived. The text and the caret must still be the ones the insert was
     * computed for; otherwise the insert is dropped, because its range would name other text.
     */
    private String finishResolve(CompletionSite requested, Insertion insertion, String label,
            CapabilityResult<List<Replacement>> resolved) {
        Optional<CompletionSite> now = editor.completionSite();
        if (now.isEmpty() || !now.get().equals(requested)) {
            return "Completion dropped: the text changed while its import was resolved";
        }
        CompletionSite site = now.get();
        if (resolved instanceof CapabilityResult.Ok<List<Replacement>> ok) {
            List<Replacement> imports = ok.value();
            return insert(site, insertion, imports, imports.isEmpty() ? "Completion inserted: " + label
                    : "Completion inserted with its import: " + label);
        }
        CapabilityFailure failure = resolved.failureOf().orElseThrow();
        String reason = failure.detail().isBlank() ? failure.reason() : failure.detail();
        return insert(site, insertion, List.of(), "Inserted without the import: " + reason);
    }

    private String insert(CompletionSite site, Insertion insertion, List<Replacement> additional, String inserted) {
        Optional<String> refused = editor.applyCompletion(site.file(), insertion.edit(), additional,
                insertion.caret());
        return refused.map(reason -> "Completion not applied: " + reason).orElse(inserted);
    }

    /**
     * The range the insert replaces and the text it inserts. A text edit replaces its own range, extended to the
     * caret when the user typed past the range the server computed; without one the typed identifier prefix is
     * replaced.
     *
     * @return the insertion, or null when the text edit names a range on another line
     */
    private Insertion insertionOf(Suggestion suggestion, CompletionSite site) {
        Replacement edit = suggestion.textEdit();
        int row = site.row();
        int caret = site.column();
        int startColumn = anchor;
        int endColumn = caret;
        if (edit != null) {
            if (edit.startLine() != row) {
                return null;
            }
            startColumn = edit.startColumn();
            if (edit.endLine() == row) {
                endColumn = Math.max(caret, edit.endColumn());
            }
        }
        String text = edit != null ? edit.newText()
                : suggestion.insertText() == null || suggestion.insertText().isEmpty()
                ? suggestion.label() : suggestion.insertText();
        SnippetText.Expansion expansion = suggestion.snippet() ? SnippetText.expand(text)
                : new SnippetText.Expansion(text, text.length());
        return new Insertion(new Replacement(row, startColumn, row, endColumn, expansion.text()), expansion.caret());
    }

    /**
     * Whether the site is still the text the popup was asked for: the same file and caret row, the caret at or
     * after the start of the typed identifier, the text before that start unchanged, and only identifier
     * characters typed since.
     */
    private boolean continues(CompletionSite site) {
        if (!site.file().equals(asked.file()) || site.row() != asked.row() || site.column() < anchor) {
            return false;
        }
        String line = site.line();
        String askedLine = asked.line();
        if (line.length() < site.column() || askedLine.length() < anchor
                || !line.startsWith(askedLine.substring(0, anchor))) {
            return false;
        }
        return typed(site).chars().allMatch(Character::isJavaIdentifierPart);
    }

    private String typed(CompletionSite site) {
        String line = site.line();
        return line.substring(Math.min(anchor, site.column()), site.column());
    }

    private void openAfterDot() {
        Optional<CompletionSite> site = editor.completionSite();
        if (site.isEmpty() || !isJavaFile(site.get().file())) {
            return;
        }
        CompletionSite current = site.get();
        String line = current.line();
        int dot = current.column() - 1;
        if (dot < 1 || dot >= line.length() || line.charAt(dot) != '.') {
            return;
        }
        int start = dot;
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) {
            start--;
        }
        if (start < dot && Character.isJavaIdentifierStart(line.charAt(start))) {
            start(current);
        }
    }

    private static OverlayKey navigationKey(KeyEvent key) {
        OverlayKey mapped = Keys.is(key, KeyCode.TAB) ? OverlayKey.ENTER : OverlayInput.key(key);
        return switch (mapped) {
            case ENTER, ESCAPE, UP, DOWN, PAGE_UP, PAGE_DOWN -> mapped;
            default -> null;
        };
    }

    private static boolean isDot(KeyEvent key) {
        return key.isTyping() && key.isCharIgnoreCase('.');
    }

    private static boolean isEditing(KeyEvent key) {
        return key.isTyping() || Keys.is(key, KeyCode.BACKSPACE) || Keys.is(key, KeyCode.DELETE)
                || Keys.is(key, KeyCode.LEFT) || Keys.is(key, KeyCode.RIGHT) || Keys.is(key, KeyCode.HOME)
                || Keys.is(key, KeyCode.END);
    }

    private static boolean isJavaFile(Path file) {
        Path name = file.getFileName();
        return name != null && name.toString().endsWith(".java");
    }

    /**
     * @param edit the range the insert replaces and the text it inserts
     * @param caret characters into the inserted text where the caret goes
     */
    private record Insertion(Replacement edit, int caret) {
    }
}
