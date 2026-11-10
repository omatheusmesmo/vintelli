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
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.paragraph.Paragraph;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

@Singleton
public final class StructureToolWindow implements ToolWindow {

    private static final String OUTLINE_GLYPH = "";

    private record VisibleSymbol(NavigationCapability.DocumentSymbol symbol, int depth) { }

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
    private Rect lastArea = Rect.ZERO;
    private int scrollOffset;
    private int symbolRowOffset;

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
    public String icon() {
        return OUTLINE_GLYPH;
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
    public boolean claimsQuitChord() {
        return false;
    }

    @Override
    public boolean handleKey(KeyEvent event) {
        if (event.isKey(KeyCode.BACKSPACE) && !filter.isEmpty()) {
            filter = filter.substring(0, filter.offsetByCodePoints(filter.length(), -1));
            selectedIndex = 0;
            scrollOffset = 0;
            return true;
        }
        if (event.code() == KeyCode.CHAR && !event.hasCtrl() && !event.hasAlt()
                && !Character.isISOControl(event.codePoint())) {
            filter += event.string();
            selectedIndex = 0;
            scrollOffset = 0;
            return true;
        }
        List<VisibleSymbol> flattened = visibleSymbols();
        if (flattened.isEmpty()) {
            return false;
        }
        if (event.isKey(KeyCode.UP)) {
            selectedIndex = Math.max(0, selectedIndex - 1);
            keepSelectionVisible(flattened.size());
            return true;
        }
        if (event.isKey(KeyCode.DOWN)) {
            selectedIndex = Math.min(flattened.size() - 1, selectedIndex + 1);
            keepSelectionVisible(flattened.size());
            return true;
        }
        if (event.isKey(KeyCode.ENTER)) {
            if (navigateHandler != null && selectedIndex >= 0 && selectedIndex < flattened.size()) {
                navigateHandler.accept(flattened.get(selectedIndex).symbol());
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean handleMouse(MouseEvent event) {
        if (!inside(lastArea, event.x(), event.y())) {
            return false;
        }
        List<VisibleSymbol> rows = visibleSymbols();
        if (event.isScroll() && (event.kind() == MouseEventKind.SCROLL_UP
                || event.kind() == MouseEventKind.SCROLL_DOWN)) {
            if (rows.isEmpty() || event.y() < lastArea.y() + symbolRowOffset) {
                return false;
            }
            int direction = event.kind() == MouseEventKind.SCROLL_UP ? -1 : 1;
            scrollOffset = clamp(scrollOffset + direction * 3, 0, maxScroll(rows.size()));
            int viewport = symbolViewportHeight();
            if (viewport > 0) {
                selectedIndex = Math.clamp(selectedIndex, scrollOffset,
                        Math.min(rows.size() - 1, scrollOffset + viewport - 1));
            }
            return true;
        }
        if ((!event.isPress() && !event.isClick()) || !event.isLeftButton()) {
            return false;
        }
        int row = event.y() - lastArea.y() - symbolRowOffset + scrollOffset;
        if (row < scrollOffset || row >= rows.size() || row >= scrollOffset + symbolViewportHeight()) {
            return false;
        }
        selectedIndex = row;
        return true;
    }

    @Override
    public void render(Frame frame, Rect area) {
        refreshIfNeeded();
        lastArea = area;
        ShellThemes activeThemes = themes == null ? null : themes.get();
        Style contentStyle = activeThemes == null ? Style.EMPTY
                : activeThemes.resolver("tool-window").toStyle();
        List<VisibleSymbol> rows = visibleSymbols();
        List<String> lines = new ArrayList<>();
        if (!filter.isEmpty()) {
            lines.add("Filter: " + filter);
            symbolRowOffset = 1;
        } else {
            symbolRowOffset = 0;
        }
        if (unavailableReason != null) {
            lines.add(unavailableReason);
        } else if (roots.isEmpty()) {
            lines.add("No symbols");
        } else if (rows.isEmpty()) {
            lines.add("No matching symbols");
        } else {
            selectedIndex = Math.clamp(selectedIndex, 0, rows.size() - 1);
            int viewport = symbolViewportHeight();
            scrollOffset = clamp(scrollOffset, 0, maxScroll(rows.size()));
            if (selectedIndex < scrollOffset) {
                scrollOffset = selectedIndex;
            } else if (selectedIndex >= scrollOffset + viewport) {
                scrollOffset = selectedIndex - viewport + 1;
            }
            for (int index = scrollOffset; index < Math.min(rows.size(), scrollOffset + viewport); index++) {
                VisibleSymbol row = rows.get(index);
                lines.add((index == selectedIndex ? "> " : "  ") + "  ".repeat(row.depth())
                        + row.symbol().name());
            }
        }
        Paragraph paragraph = Paragraph.builder()
                .text(Text.from(String.join("\n", lines)))
                .style(contentStyle)
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
        scrollOffset = 0;
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

    private List<VisibleSymbol> visibleSymbols() {
        List<VisibleSymbol> result = new ArrayList<>();
        String needle = filter.toLowerCase(Locale.ROOT);
        for (NavigationCapability.DocumentSymbol symbol : roots) {
            collectVisible(symbol, 0, needle, result);
        }
        return result;
    }

    private static void collectVisible(NavigationCapability.DocumentSymbol symbol, int depth, String needle,
            List<VisibleSymbol> result) {
        if (needle.isEmpty() || symbol.name().toLowerCase(Locale.ROOT).contains(needle)) {
            result.add(new VisibleSymbol(symbol, depth));
        }
        for (NavigationCapability.DocumentSymbol child : symbol.children()) {
            collectVisible(child, depth + 1, needle, result);
        }
    }

    private int symbolViewportHeight() {
        return Math.max(0, lastArea.height() - symbolRowOffset);
    }

    private int maxScroll(int size) {
        return Math.max(0, size - symbolViewportHeight());
    }

    private void keepSelectionVisible(int size) {
        int viewport = symbolViewportHeight();
        scrollOffset = clamp(scrollOffset, 0, maxScroll(size));
        if (selectedIndex < scrollOffset) {
            scrollOffset = selectedIndex;
        } else if (selectedIndex >= scrollOffset + viewport) {
            scrollOffset = Math.min(maxScroll(size), selectedIndex - viewport + 1);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static boolean inside(Rect area, int x, int y) {
        return x >= area.x() && x < area.x() + area.width()
                && y >= area.y() && y < area.y() + area.height();
    }

    private static void flatten(NavigationCapability.DocumentSymbol symbol,
            List<NavigationCapability.DocumentSymbol> result) {
        result.add(symbol);
        for (NavigationCapability.DocumentSymbol child : symbol.children()) {
            flatten(child, result);
        }
    }

}
