package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * REST anchor for inspecting the TUI shell from outside the process. Renders
 * one frame of the live {@link VintelliShell} into an in-memory buffer,
 * entirely offscreen: no terminal is ever attached, so the endpoint is safe
 * inside integration tests, including against the native runner where the
 * whole render pipeline (layout, widgets, styles) must survive AOT
 * compilation. The frame is drawn under the shell lock, and no key event is
 * ever routed from HTTP.
 *
 * <p>Because the frame shows the live shell, the endpoint is off unless
 * {@code vintelli.inspect.enabled} is {@code true} and answers 404 otherwise;
 * it is meant for tests and local debugging. It never mutates the pushed
 * shell state, so a highlight or diff pushed through the shell ports shows up
 * here untouched.
 */
@Path("/tui")
@Singleton
public final class ShellInspectResource {

    private static final Rect VIEWPORT = new Rect(0, 0, 80, 24);
    private static final int TAB_ROW = 1;
    private static final int MAIN_AREA_TOP_ROW = 2;
    private static final int FIRST_EDITOR_ROW = 3;
    private static final int LAST_EDITOR_ROW = 12;
    private static final int STATUS_ROW = 23;

    private final VintelliShell shell;
    private final boolean enabled;

    @Inject
    public ShellInspectResource(VintelliShell shell,
            @ConfigProperty(name = "vintelli.inspect.enabled", defaultValue = "false") boolean enabled) {
        this.shell = shell;
        this.enabled = enabled;
    }

    @GET
    @Path("/render")
    @Produces(MediaType.APPLICATION_JSON)
    public ShellRenderView render() {
        if (!enabled) {
            throw new NotFoundException();
        }
        Buffer buffer = Buffer.empty(VIEWPORT);
        shell.render(Frame.forTesting(buffer));
        return new ShellRenderView(
                rowText(buffer, TAB_ROW),
                rowText(buffer, MAIN_AREA_TOP_ROW),
                editorRows(buffer),
                rowText(buffer, STATUS_ROW));
    }

    private static String editorRows(Buffer buffer) {
        StringBuilder text = new StringBuilder();
        for (int row = FIRST_EDITOR_ROW; row <= LAST_EDITOR_ROW; row++) {
            if (row > FIRST_EDITOR_ROW) {
                text.append('\n');
            }
            text.append(rowText(buffer, row));
        }
        return text.toString();
    }

    private static String rowText(Buffer buffer, int row) {
        StringBuilder text = new StringBuilder();
        for (int x = 0; x < buffer.width(); x++) {
            text.append(buffer.get(x, row).symbol());
        }
        return text.toString();
    }

    /**
     * Payload offered only while {@code vintelli.inspect.enabled} is
     * {@code true}: the plain text of the tool window tab row, the top border
     * row of the project list and editor blocks, the content rows of the
     * editor block joined by newlines, and the status row.
     */
    record ShellRenderView(String tabRow, String mainAreaTop, String editorArea, String status) {
    }
}
