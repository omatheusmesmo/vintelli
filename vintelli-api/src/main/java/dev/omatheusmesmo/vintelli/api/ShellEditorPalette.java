package dev.omatheusmesmo.vintelli.api;

/**
 * Editor palette of the active shell theme: the colors an editor surface
 * must use for text, keywords, strings and comments. Separate from the UI
 * chrome palette by design so the editor always follows the editor color
 * scheme of the theme instead of darkening along with the chrome.
 *
 * <p>Values are truecolor hex strings as declared in the theme stylesheets
 * and validated there; consumers hand them to their own editing surfaces.
 */
public record ShellEditorPalette(String background,
                                 String text,
                                 String keyword,
                                 String string,
                                 String comment,
                                 String number,
                                 String constant,
                                 String selection,
                                 String selectionText) {
}
