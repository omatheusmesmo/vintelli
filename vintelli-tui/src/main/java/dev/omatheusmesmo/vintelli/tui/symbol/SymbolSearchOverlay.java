package dev.omatheusmesmo.vintelli.tui.symbol;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.WorkspaceSymbol;
import dev.omatheusmesmo.vintelli.tui.OverlayInput;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;

/**
 * Immediate-mode renderer and keyboard handler for Go to Class and Go to Symbol. A row shows the kind, the name and the
 * container of the symbol; a symbol outside the project files is drawn with the note that it cannot be opened.
 */
public final class SymbolSearchOverlay {

    private static final Style SELECTED = Style.create().reversed().bold();
    private static final Style DIM = Style.create().dim();
    private static final int KIND_WIDTH = 14;
    private static final String SEPARATOR = "  ";
    private static final String NOT_OPENABLE = "  (not openable)";

    private final SymbolSearchController controller;

    public SymbolSearchOverlay(SymbolSearchModel model) {
        this.controller = new SymbolSearchController(model);
    }

    /** @return the confirmed file and zero-based line, or empty when the key was consumed without confirming one */
    public Optional<Target> handleKey(KeyEvent key) {
        return controller.press(OverlayInput.key(key), key.codePoint())
                .map(target -> new Target(target.file(), target.line()));
    }

    public void render(Frame frame, Rect area) {
        Optional<SymbolSearchController.Placement> placed = controller.place(OverlayInput.region(area));
        if (placed.isEmpty()) {
            return;
        }
        SymbolSearchController.Placement placement = placed.get();
        SymbolSearchModel.View view = placement.view();
        Rect panel = OverlayInput.rect(placement.panel());
        frame.renderWidget(Clear.INSTANCE, panel);
        String title = view.scope() == SymbolSearchModel.Scope.TYPES ? "Go to Class" : "Go to Symbol";
        frame.renderWidget(Block.builder().title(title).borders(Borders.ALL).borderType(BorderType.ROUNDED).build(),
                panel);
        Rect inner = OverlayInput.rect(placement.inner());
        List<Line> lines = new ArrayList<>();
        lines.add(Line.from(Span.styled("> " + view.query(), Style.create().bold())));
        if (view.symbols().isEmpty()) {
            lines.add(Line.from(Span.styled(fit(emptyMessage(view), inner.width()), DIM)));
        } else {
            appendRows(lines, view, placement.first(), placement.visibleRows(), inner.width());
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(lines)).build(), inner);
    }

    private static String emptyMessage(SymbolSearchModel.View view) {
        if (view.error() != null) {
            return view.error();
        }
        if (view.searching()) {
            return "Searching…";
        }
        return view.query().isEmpty() ? "Type a name to search" : "No symbols";
    }

    private static void appendRows(List<Line> lines, SymbolSearchModel.View view, int first, int count, int width) {
        for (int row = 0; row < count && first + row < view.symbols().size(); row++) {
            int index = first + row;
            lines.add(rowLine(view.symbols().get(index), index == view.selected(), width));
        }
    }

    private static Line rowLine(WorkspaceSymbol symbol, boolean chosen, int width) {
        boolean openable = symbol.location().isPresent();
        String kind = fit(SymbolKinds.label(symbol.kind()), KIND_WIDTH);
        kind = kind + " ".repeat(KIND_WIDTH - CharWidth.of(kind));
        int nameWidth = Math.max(0, width - KIND_WIDTH - SEPARATOR.length());
        String name = fit(symbol.name(), nameWidth);
        int containerWidth = Math.max(0, nameWidth - CharWidth.of(name) - SEPARATOR.length());
        String container = symbol.container().isEmpty() ? "" : fit(symbol.container(), containerWidth);
        String note = openable ? "" : fit(NOT_OPENABLE, containerWidth - CharWidth.of(container));
        if (chosen) {
            return Line.from(Span.styled(kind, SELECTED), Span.styled(SEPARATOR, SELECTED),
                    Span.styled(name, SELECTED), Span.styled(SEPARATOR + container + note, SELECTED));
        }
        Style style = openable ? Style.create() : DIM;
        return Line.from(Span.styled(kind, DIM), Span.styled(SEPARATOR, style), Span.styled(name, style),
                Span.styled(SEPARATOR + container + note, DIM));
    }

    private static String fit(String value, int width) {
        if (width <= 0) {
            return "";
        }
        return CharWidth.of(value) <= width ? value : CharWidth.substringByWidth(value, width);
    }

    /** A confirmed location: the file, and the zero-based line the symbol starts on. */
    public record Target(Path file, int line) {
    }
}
