package dev.omatheusmesmo.vintelli.api.capability;

import java.nio.file.Path;

/**
 * Offers the documentation of the symbol at a position of a file: the hover the language server answers for
 * {@code textDocument/hover}, or any other provider that can name the documentation of a position. The provider
 * takes {@code content} as the current text of the file, so the documentation matches unsaved edits. Results carry
 * Vintelli-owned text only; rendering it is the job of the editor.
 */
public interface HoverCapability extends ProviderDescriptor {

    /**
     * Asks for the documentation of the symbol at a position.
     *
     * @param file file the position belongs to
     * @param content the current text of the file, including unsaved edits
     * @param line zero based line of the caret
     * @param column zero based column of the caret in UTF-16 code units
     * @return the documentation, empty text when the provider has none for the position, or a failed result when
     *         the language services cannot answer or answer malformed
     */
    CapabilityResult<Documentation> hover(Path file, String content, int line, int column);

    /**
     * The documentation text of a symbol as the provider sends it, markdown or HTML.
     *
     * @param text the documentation text, empty when the provider has none
     */
    record Documentation(String text) {

        public Documentation {
            text = text == null ? "" : text;
        }
    }
}
