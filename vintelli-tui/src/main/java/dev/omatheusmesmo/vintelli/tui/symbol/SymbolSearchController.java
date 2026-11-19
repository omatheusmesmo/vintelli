package dev.omatheusmesmo.vintelli.tui.symbol;

import java.nio.file.Path;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.Location;
import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.WorkspaceSymbol;
import dev.omatheusmesmo.vintelli.tui.OverlayKey;
import dev.omatheusmesmo.vintelli.tui.OverlayRegion;

/**
 * Key rules and placement of the symbol overlay on top of {@link SymbolSearchModel}: Enter confirms the selected symbol
 * when it has a file to open, and a symbol outside the project files stays listed without opening. It holds no TamboUI
 * type; the overlay draws the placement it returns and forwards the keys.
 */
final class SymbolSearchController {

    /** A confirmed location: the file, and the zero-based line the symbol starts on. */
    record Target(Path file, int line) {
    }

    /** The panel the render pass placed, with the rows it shows from {@code first}. */
    record Placement(OverlayRegion panel, OverlayRegion inner, SymbolSearchModel.View view, int visibleRows,
            int first) {
    }

    private static final int MIN_WIDTH = 8;
    private static final int MIN_HEIGHT = 4;
    private static final int MAX_WIDTH = 90;
    private static final int MIN_PANEL_HEIGHT = 5;
    private static final int MAX_PANEL_HEIGHT = 20;

    private final SymbolSearchModel model;

    SymbolSearchController(SymbolSearchModel model) {
        this.model = model;
    }

    /** @return the confirmed location, or empty when the key was consumed without confirming one */
    Optional<Target> press(OverlayKey key, int codePoint) {
        if (!model.isOpen()) {
            return Optional.empty();
        }
        switch (key) {
            case ESCAPE -> model.close();
            case ENTER -> {
                WorkspaceSymbol symbol = model.selectedSymbol();
                if (symbol != null && symbol.location().isPresent()) {
                    model.close();
                    Location location = symbol.location().get();
                    return Optional.of(new Target(location.filePath(), location.line()));
                }
            }
            case UP -> model.move(-1);
            case DOWN -> model.move(1);
            case BACKSPACE -> model.backspace();
            case TEXT -> model.type(codePoint);
            default -> {
            }
        }
        return Optional.empty();
    }

    /**
     * Places the panel in {@code area}. An empty result means the overlay is closed or the area is too small.
     */
    Optional<Placement> place(OverlayRegion area) {
        if (!model.isOpen() || area.width() < MIN_WIDTH || area.height() < MIN_HEIGHT) {
            return Optional.empty();
        }
        SymbolSearchModel.View view = model.view();
        int width = Math.min(MAX_WIDTH, area.width() - 2);
        int rows = Math.max(1, view.symbols().size());
        int height = Math.min(area.height() - 2,
                Math.max(MIN_PANEL_HEIGHT, Math.min(rows + 3, MAX_PANEL_HEIGHT)));
        OverlayRegion panel = new OverlayRegion(area.x() + (area.width() - width) / 2,
                area.y() + (area.height() - height) / 2, width, height);
        OverlayRegion inner = panel.inset(1);
        int visibleRows = Math.max(0, inner.height() - 1);
        int first = visibleRows == 0 ? 0 : Math.max(0, view.selected() - visibleRows + 1);
        return Optional.of(new Placement(panel, inner, view, visibleRows, first));
    }
}
