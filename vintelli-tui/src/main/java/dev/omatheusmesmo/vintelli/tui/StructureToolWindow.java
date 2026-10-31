package dev.omatheusmesmo.vintelli.tui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.style.Style;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.paragraph.Paragraph;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

@Singleton
public final class StructureToolWindow implements ToolWindow {

    private final List<NavigationCapability> providers;
    private final BuiltinEditor editor;
    private final Provider<ShellThemes> themes;

    private final List<NavigationCapability.DocumentSymbol> roots = new ArrayList<>();
    private String activeFile = "";
    private String unavailableReason = null;
    private int selectedIndex = 0;
    private String filter = "";
    private int lastCaretLine = -1;
    private Consumer<NavigationCapability.DocumentSymbol> navigateHandler;

    @Inject
    public StructureToolWindow(jakarta.enterprise.inject.Instance<NavigationCapability> providers,
            BuiltinEditor editor, Provider<ShellThemes> themes) {
        this(toList(providers), editor, themes);
    }

    public StructureToolWindow(Provider<ShellThemes> themes) {
        this(List.of(), null, themes);
    }

    public StructureToolWindow(ShellThemes themes) {
        this(List.of(), null, () -> themes);
    }

    public StructureToolWindow(List<NavigationCapability> providers, BuiltinEditor editor, ShellThemes themes) {
        this(providers, editor, () -> themes);
    }

    public StructureToolWindow(List<NavigationCapability> providers, ShellThemes themes) {
        this(providers, null, () -> themes);
    }

    public StructureToolWindow(List<NavigationCapability> providers, Provider<ShellThemes> themes) {
        this(providers, null, themes);
    }

    public StructureToolWindow(NavigationCapability provider, BuiltinEditor editor, Provider<ShellThemes> themes) {
        this(provider == null ? List.of() : List.of(provider), editor, themes);
    }

    public StructureToolWindow(List<NavigationCapability> providers, BuiltinEditor editor, Provider<ShellThemes> themes) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
        this.editor = editor;
        this.themes = themes;
    }

    private static List<NavigationCapability> toList(jakarta.enterprise.inject.Instance<NavigationCapability> instance) {
        List<NavigationCapability> result = new ArrayList<>();
        if (instance != null) {
            for (NavigationCapability provider : instance) {
                result.add(provider);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public String id() {
        return "structure";
    }

    @Override
    public String title() {
        return "Structure";
    }

    @Override
    public char shortcut() {
        return '6';
    }

    @Override
    public int ordinal() {
        return 4;
    }

    @Override
    public Anchor anchor() {
        return Anchor.RIGHT;
    }

    @Override
    public boolean capturesInput() {
        return true;
    }

    @Override
    public boolean handleKey(KeyEvent event) {
        if (event.isKey(KeyCode.BACKSPACE) && !filter.isEmpty()) {
            filter = filter.substring(0, filter.offsetByCodePoints(filter.length(), -1));
            selectedIndex = 0;
            return true;
        }
        if (event.code() == KeyCode.CHAR && !event.hasCtrl() && !event.hasAlt()
                && !Character.isISOControl(event.codePoint())) {
            filter += event.string();
            selectedIndex = 0;
            return true;
        }
        List<NavigationCapability.DocumentSymbol> flattened = flattened();
        if (flattened.isEmpty()) {
            return false;
        }
        if (event.isKey(KeyCode.UP)) {
            selectedIndex = Math.max(0, selectedIndex - 1);
            return true;
        }
        if (event.isKey(KeyCode.DOWN)) {
            selectedIndex = Math.min(flattened.size() - 1, selectedIndex + 1);
            return true;
        }
        if (event.isKey(KeyCode.ENTER)) {
            if (navigateHandler != null && selectedIndex >= 0 && selectedIndex < flattened.size()) {
                navigateHandler.accept(flattened.get(selectedIndex));
            }
            return true;
        }
        return false;
    }

    @Override
    public void render(Frame frame, Rect area) {
        refreshIfNeeded();
        ShellThemes activeThemes = themes == null ? null : themes.get();
        Style contentStyle = activeThemes == null ? Style.EMPTY
                : activeThemes.resolver("tool-window").toStyle();
        Title blockTitle = Title.from(Span.styled(title(),
                activeThemes == null ? Style.EMPTY : activeThemes.resolver("tool-window-title").toStyle()));
        Block.Builder blockBuilder = Block.builder()
                .title(blockTitle)
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .style(contentStyle);
        if (activeThemes != null) {
            activeThemes.resolver("tool-window").borderColor().ifPresent(blockBuilder::borderColor);
        }
        List<String> lines = new ArrayList<>();
        if (!filter.isEmpty()) {
            lines.add("Filter: " + filter);
        }
        if (unavailableReason != null) {
            lines.add(unavailableReason);
        } else if (roots.isEmpty()) {
            lines.add("No symbols");
        } else {
            int prefix = lines.size();
            appendSymbols(lines, roots, 0, selectedIndex, prefix, filter.toLowerCase(Locale.ROOT));
            if (lines.size() == prefix) {
                lines.add("No matching symbols");
            }
        }
        Paragraph paragraph = Paragraph.builder()
                .text(Text.from(String.join("\n", lines)))
                .style(contentStyle)
                .block(blockBuilder.build())
                .scroll(unavailableReason == null && !roots.isEmpty()
                        ? Math.max(0, selectedIndex + (filter.isEmpty() ? 0 : 1) - Math.max(0, area.height() - 3)) : 0)
                .build();
        frame.renderWidget(paragraph, area);
    }

    public List<NavigationCapability.DocumentSymbol> symbols() {
        return List.copyOf(roots);
    }

    public void setSymbols(List<NavigationCapability.DocumentSymbol> symbols) {
        roots.clear();
        if (symbols != null) {
            roots.addAll(symbols);
        }
        unavailableReason = null;
        selectedIndex = 0;
        filter = "";
    }

    public void setUnavailable(String reason) {
        roots.clear();
        unavailableReason = reason == null ? "LSP unavailable" : reason;
    }

    public void setActiveFile(Path file) {
        activeFile = file == null ? "" : file.toString();
        fetch();
    }

    public void setActiveFile(String file) {
        activeFile = file == null ? "" : file;
        fetch();
    }

    public void refresh() {
        String current = currentFile();
        if (!current.equals(activeFile)) {
            activeFile = current;
        }
        fetch();
    }

    public void setNavigateHandler(Consumer<NavigationCapability.DocumentSymbol> handler) {
        this.navigateHandler = handler;
    }

    public NavigationCapability.DocumentSymbol selectedSymbol() {
        List<NavigationCapability.DocumentSymbol> flattened = flattened();
        if (flattened.isEmpty() || selectedIndex < 0 || selectedIndex >= flattened.size()) {
            return null;
        }
        return flattened.get(selectedIndex);
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public String status() {
        if (unavailableReason != null) {
            return unavailableReason;
        }
        return roots.size() + " symbols";
    }

    private String currentFile() {
        if (editor != null) {
            return editor.activeDocument().map(BuiltinEditor.DocumentView::file).orElse("");
        }
        return activeFile;
    }

    private void refreshIfNeeded() {
        String current = currentFile();
        if (!current.equals(activeFile)) {
            activeFile = current;
            fetch();
            lastCaretLine = -1;
        }
        if (editor != null) {
            editor.activeDocument().ifPresent(document -> {
                int line = document.line();
                if (line != lastCaretLine) {
                    lastCaretLine = line;
                    List<NavigationCapability.DocumentSymbol> symbols = flattened();
                    for (int index = 0; index < symbols.size(); index++) {
                        NavigationCapability.DocumentSymbol symbol = symbols.get(index);
                        if (symbol.startLine() <= line && line <= symbol.endLine()) {
                            selectedIndex = index;
                        }
                    }
                }
            });
        }
    }

    private void fetch() {
        roots.clear();
        unavailableReason = null;
        if (activeFile.isBlank()) {
            unavailableReason = "No active file";
            return;
        }
        NavigationCapability provider = availableProvider();
        if (provider == null) {
            unavailableReason = providers.stream()
                    .map(NavigationCapability::health)
                    .filter(health -> health != null && !health.isAvailable())
                    .map(ProviderHealth::reason)
                    .findFirst().map(reason -> "LSP unavailable: " + reason)
                    .orElse("LSP unavailable: no navigation provider configured");
            return;
        }
        try {
            CapabilityResult<List<NavigationCapability.DocumentSymbol>> result = provider.documentSymbols(Path.of(activeFile));
            if (result instanceof CapabilityResult.Ok<List<NavigationCapability.DocumentSymbol>> ok) {
                roots.addAll(ok.value());
                selectedIndex = 0;
            } else {
                unavailableReason = result.failureOf()
                        .map(failure -> failure.reason() + ": " + failure.detail())
                        .orElse("LSP unavailable");
            }
        } catch (RuntimeException failure) {
            unavailableReason = "LSP unavailable: " + failure.getMessage();
        }
    }

    private NavigationCapability availableProvider() {
        for (NavigationCapability provider : providers) {
            if (provider != null && provider.health() != null
                    && provider.health().state() == ProviderHealth.State.AVAILABLE) {
                return provider;
            }
        }
        return null;
    }

    private List<NavigationCapability.DocumentSymbol> flattened() {
        List<NavigationCapability.DocumentSymbol> result = new ArrayList<>();
        for (NavigationCapability.DocumentSymbol symbol : roots) {
            flatten(symbol, result);
        }
        String needle = filter.toLowerCase(Locale.ROOT);
        return needle.isEmpty() ? result
                : result.stream().filter(symbol -> symbol.name().toLowerCase(Locale.ROOT).contains(needle)).toList();
    }

    private static void flatten(NavigationCapability.DocumentSymbol symbol,
            List<NavigationCapability.DocumentSymbol> result) {
        result.add(symbol);
        for (NavigationCapability.DocumentSymbol child : symbol.children()) {
            flatten(child, result);
        }
    }

    private static void appendSymbols(List<String> lines, List<NavigationCapability.DocumentSymbol> symbols, int depth,
            int selectedIndex, int prefix, String needle) {
        String indent = "  ".repeat(Math.max(0, depth));
        for (NavigationCapability.DocumentSymbol symbol : symbols) {
            if (needle.isEmpty() || symbol.name().toLowerCase(Locale.ROOT).contains(needle)) {
                lines.add((lines.size() - prefix == selectedIndex ? "> " : "  ") + indent + symbol.name());
            }
            if (symbol.children() != null && !symbol.children().isEmpty()) {
                appendSymbols(lines, symbol.children(), depth + 1, selectedIndex, prefix, needle);
            }
        }
    }
}
