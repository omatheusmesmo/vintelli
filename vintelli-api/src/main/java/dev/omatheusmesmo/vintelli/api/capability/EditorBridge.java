package dev.omatheusmesmo.vintelli.api.capability;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Opens a project file at a line in the editor the shell relies on, and
 * reports the file the editor shows right now when the editor can tell us.
 * Editing is a capability: the project tree, tool windows and the
 * {@code vintelli_highlight} port open files through this bridge instead of
 * knowing a concrete editor.
 *
 * <p>Adapters ship in the documented preference order: the builtin text area
 * editor is the default and needs no external process, no tmux and no Neovim
 * installation; a generic adapter starts {@code $EDITOR +line file} in a new
 * pane, window or terminal; an embedded Neovim over msgpack-RPC is a separate
 * editor. A registry selection falls back down this order when the adapter
 * ranked first is unavailable.
 */
public interface EditorBridge extends ProviderDescriptor {

    /**
     * Opens the file and places the cursor on its line. The call happens on
     * the caller supplied thread context and must not block on external
     * devices: opening is a state change of the selected editor, not a
     * terminal session.
     *
     * @param request file to open and the zero based line to place the cursor on
     * @return the file and the line the adapter actually placed the cursor on,
     *         possibly clamped when the file is shorter, or a failed result
     *         when the adapter cannot show the file, for example because it no
     *         longer exists or the editor backend stopped
     */
    CapabilityResult<Opened> open(OpenRequest request);

    /**
     * @return the file the editor presents right now, empty when it cannot
     *         tell, for example a generic external editor has no file channel
     *         back into the shell
     */
    Optional<Path> currentFile();

    record OpenRequest(Path file, int line) {

        public OpenRequest {
            Objects.requireNonNull(file, "file");
            if (line < 0) {
                throw new IllegalArgumentException("A line is always zero based and never negative");
            }
        }
    }

    record Opened(Path file, int line) {

        public Opened {
            Objects.requireNonNull(file, "file");
            if (line < 0) {
                throw new IllegalArgumentException("A line is always zero based and never negative");
            }
        }
    }
}
