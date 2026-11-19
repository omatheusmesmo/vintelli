package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.CodeActionCapability.SourceRange;

import java.nio.file.Path;

/**
 * What one formatting or organize imports request is made for: the active file, the text the editor holds at the
 * time of the request, and the selection in it. The text is what the server formats, and the answer applies only
 * while the document still holds that text.
 *
 * @param file the open document the edits apply to
 * @param text the text of the document when the request was made
 * @param selection zero based range of the selection, or null when nothing is selected
 */
public record FormatSite(Path file, String text, SourceRange selection) {
}
