package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * REST anchor for inspecting the TUI shell from outside the process. Renders one
 * frame of {@link VintelliShell} into an in-memory buffer, entirely offscreen: no
 * terminal is ever attached, so the endpoint is safe inside integration tests,
 * including against the native runner where the whole render pipeline
 * (layout, widgets, styles) must survive AOT compilation.
 */
@Path("/tui")
@Singleton
public final class ShellInspectResource {

    private static final Rect VIEWPORT = new Rect(0, 0, 80, 24);
    private static final int TAB_ROW = 1;
    private static final int MAIN_AREA_TOP_ROW = 2;
    private static final int STATUS_ROW = 23;

    /**
     * Renders a single offscreen frame of a fresh shell, so the live TUI state is never touched,
     * and returns the tool window tab row, the main area top row and the status row as plain text.
     */
    @GET
    @Path("/render")
    @Produces(MediaType.APPLICATION_JSON)
    public ShellRenderView render() {
        Buffer buffer = Buffer.empty(VIEWPORT);
        new VintelliShell().render(Frame.forTesting(buffer));
        return new ShellRenderView(
                rowText(buffer, TAB_ROW),
                rowText(buffer, MAIN_AREA_TOP_ROW),
                rowText(buffer, STATUS_ROW));
    }

    private static String rowText(Buffer buffer, int row) {
        StringBuilder text = new StringBuilder();
        for (int x = 0; x < buffer.width(); x++) {
            text.append(buffer.get(x, row).symbol());
        }
        return text.toString();
    }

    /**
     * Payload of one offscreen render: the plain text of the tool window tab row, the top border row of the
     * project list and editor blocks, and the status row.
     */
    record ShellRenderView(String tabRow, String mainAreaTop, String status) {
    }
}
