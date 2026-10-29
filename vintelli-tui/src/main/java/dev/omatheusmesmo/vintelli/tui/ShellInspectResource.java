package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.keymap.KeyAction;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapProfile;
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

import java.util.LinkedHashMap;
import java.util.Map;

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
    private final ToolWindowRegistry registry;
    private final boolean enabled;

    @Inject
    public ShellInspectResource(VintelliShell shell, ToolWindowRegistry registry,
            @ConfigProperty(name = "vintelli.inspect.enabled", defaultValue = "false") boolean enabled) {
        this.shell = shell;
        this.registry = registry;
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

    /**
     * Reports the keymap the inspection shells build: source and the shortcuts
     * of every named action. Loading the TamboUI binding presets is exercised
     * here in every integration test, including the native runner, so a missing
     * built-in bindings resource in the native image fails this endpoint
     * instead of staying silent. It is built from the default profile, not from
     * the live shell, so it stays deterministic and independent of the
     * developer's user keymap file.
     */
    @GET
    @Path("/keymap")
    @Produces(MediaType.APPLICATION_JSON)
    public KeymapView keymap() {
        Keymap keymap = Keymap.ofProfile(KeymapProfile.DEFAULT, registry.toolWindows());
        Map<String, String> shortcuts = new LinkedHashMap<>();
        for (KeyAction action : keymap.actions()) {
            shortcuts.put(action.id(), keymap.describeBindings(action.id()));
        }
        return new KeymapView(keymap.source(), shortcuts);
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

    /**
     * Payload of the keymap inspection: the keymap source plus one shortcut
     * string per action id.
     */
    record KeymapView(String source, Map<String, String> shortcuts) {
    }
}
