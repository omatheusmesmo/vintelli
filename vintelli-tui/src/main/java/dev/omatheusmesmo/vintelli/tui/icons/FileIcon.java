package dev.omatheusmesmo.vintelli.tui.icons;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Span;

/**
 * One file type icon: the Nerd Font glyph, the pure ASCII fallback marker and
 * the foreground color that keeps the faces readable when only colors remain
 * (the ASCII face prints colors only). Both symbol faces carry exactly the
 * same single-cell rendering width on the covered terminals, so a tree keeps
 * its column alignment when the patched font setting changes; the width is
 * computed per symbol through the TamboUI width table, which resolves double
 * width glyphs such as emoji to {@code 2} and variation selectors to {@code 0}.
 *
 * <p>Immutable and ready for immediate-mode rendering: the caller asks for the
 * {@link Span} of the mode the running session resolved.
 */
public record FileIcon(String glyph, String ascii, Color color) {

    public FileIcon {
        if (glyph == null || glyph.isEmpty()) {
            throw new IllegalArgumentException("icon mapping must define a glyph");
        }
        if (ascii == null || ascii.isEmpty()) {
            throw new IllegalArgumentException("icon mapping must define the ASCII fallback marker");
        }
    }

    /**
     * The symbol the given mode renders: the ASCII marker in
     * {@link IconMode#ASCII}, the Nerd Font glyph otherwise.
     */
    public String symbol(IconMode mode) {
        return mode == IconMode.ASCII ? ascii : glyph;
    }

    /**
     * Rendering width in terminal cells of the symbol for the mode. This is the
     * value a project tree, an editor tab or a search result row must reserve
     * for the icon so double width glyphs never shift the following text.
     */
    public int symbolWidth(IconMode mode) {
        return CharWidth.of(symbol(mode));
    }

    /**
     * Ready to paint render unit for the mode: the symbol with the foreground
     * color of the mapping, unstyled when the mapping defines no color.
     */
    public Span span(IconMode mode) {
        return Span.styled(symbol(mode), color == null ? Style.EMPTY : Style.create().fg(color));
    }
}
