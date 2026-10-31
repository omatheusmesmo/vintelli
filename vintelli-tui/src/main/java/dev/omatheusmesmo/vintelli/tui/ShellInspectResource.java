package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.keymap.KeyAction;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapProfile;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.omatheusmesmo.vintelli.tui.icons.FileIcon;
import dev.omatheusmesmo.vintelli.tui.icons.FileIcons;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * REST anchor for inspecting the TUI shell from outside the process. Renders
 * one frame of the live {@link VintelliShell} into an in-memory buffer with
 * the style engine of {@link ShellThemes}, entirely offscreen through
 * {@link VintelliShell#paint(Frame)}: no terminal is ever attached, so the
 * endpoint is safe inside integration tests, including against the native
 * runner where the whole render pipeline (layout, widgets, styles) must
 * survive AOT compilation. The frame is drawn under the shell lock, and no
 * key event is ever routed from HTTP. It never mutates the pushed shell
 * state, so a highlight or diff pushed through the shell ports shows up here
 * untouched.
 *
 * <p>Because the frame shows the live shell, the endpoint is off unless
 * {@code vintelli.inspect.enabled} is {@code true} and answers 404 otherwise;
 * it is meant for tests and local debugging. The shell and the theme engine
 * are resolved only when the endpoint is actually called, so headless and
 * MCP-only processes never load or validate a theme.
 *
 * <p>{@code POST /tui/theme?name=<name>} activates another registered theme
 * under the same gating. It is the reachable runtime switch of
 * {@link SwitchShellThemeAction} for the surfaces that have no keymap: the
 * integration tests that prove the switch (and the re-render it triggers)
 * also run against the native image, where the stylesheet swap and the next
 * render must both survive AOT compilation. The switch is the only mutation
 * HTTP can cause on the shell: no key event is ever routed from it.
 */
@Path("/tui")
@Singleton
public final class ShellInspectResource {

    private static final Rect VIEWPORT = new Rect(0, 0, 80, 24);
    private static final int TAB_ROW = 1;
    private static final int MAIN_AREA_TOP_ROW = 2;
    private static final int FIRST_EDITOR_ROW = 2;
    private static final int LAST_EDITOR_ROW = 12;
    private static final int STATUS_ROW = 23;

    private final Instance<VintelliShell> shell;
    private final Instance<ToolWindowRegistry> registry;
    private final boolean enabled;

    @Inject
    public ShellInspectResource(Instance<VintelliShell> shell, Instance<ToolWindowRegistry> registry,
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
        return renderView(shell.get());
    }

    /**
     * Switches the active theme by name and renders the shell again in the
     * same payload, so a caller sees the new name together with a frame that
     * actually resolved in the new theme. Unknown names get a JSON payload
     * naming the rejected name and the available ones, and the active theme
     * survives. The lock-protected switch on the shell serializes the
     * stylesheet swap with every render and key pass.
     */
    @POST
    @Path("/theme")
    @Produces(MediaType.APPLICATION_JSON)
    public Response switchTheme(@QueryParam("name") String name) {
        if (!enabled) {
            throw new NotFoundException();
        }
        VintelliShell liveShell = shell.get();
        try {
            liveShell.switchTheme(name);
            return Response.ok(renderView(liveShell)).build();
        } catch (RuntimeException failure) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ThemeRejected(failure.getMessage()))
                    .build();
        }
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

    private static ShellRenderView renderView(VintelliShell liveShell) {
        Buffer buffer = Buffer.empty(VIEWPORT);
        liveShell.paint(Frame.forTesting(buffer));
        return new ShellRenderView(
                rowText(buffer, TAB_ROW),
                rowText(buffer, MAIN_AREA_TOP_ROW),
                editorRows(buffer),
                rowText(buffer, STATUS_ROW).strip(),
                liveShell.activeTheme());
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
        if (!enabled) {
            throw new NotFoundException();
        }
        Keymap keymap = Keymap.ofProfile(KeymapProfile.DEFAULT, registry.get().toolWindows());
        Map<String, String> shortcuts = new LinkedHashMap<>();
        for (KeyAction action : keymap.actions()) {
            shortcuts.put(action.id(), keymap.describeBindings(action.id()));
        }
        return new KeymapView(keymap.source(), shortcuts);
    }

    /**
     * Reports the {@link FileIcon} the shell resolves for the requested file
     * names: the Nerd Font glyph and the pure ASCII marker of each entry, plus
     * the icon face ({@code vintelli.icons}, with {@code auto} resolved through
     * the terminal environment) the session renders. The built-in mapping
     * resource is loaded through {@link FileIcons#resolve()} on every call, so
     * every integration test, including the native runner, exercises that
     * load: a resource the image does not package makes the load (and this
     * call) fail instead of staying silent. The endpoint is off like the
     * render endpoint unless {@code vintelli.inspect.enabled} is {@code true}.
     */
    @GET
    @Path("/icons")
    @Produces(MediaType.APPLICATION_JSON)
    public FileIconsView icons(@QueryParam("files") List<String> files) {
        if (!enabled) {
            throw new NotFoundException();
        }
        FileIcons icons = FileIcons.resolve();
        Map<String, FileIconView> resolved = new LinkedHashMap<>();
        List<String> names = files == null ? List.<String>of() : files;
        for (String file : names) {
            FileIcon icon = icons.iconFor(file);
            resolved.put(file, new FileIconView(icon.glyph(), icon.ascii()));
        }
        return new FileIconsView(icons.mode().name().toLowerCase(Locale.ROOT), resolved);
    }

    private static String rowText(Buffer buffer, int row) {
        StringBuilder text = new StringBuilder();
        for (int x = 0; x < buffer.width(); x++) {
            text.append(buffer.get(x, row).symbol());
        }
        return text.toString().stripTrailing();
    }

    /**
     * Payload offered only while {@code vintelli.inspect.enabled} is
     * {@code true}: the plain text of the tool window tab row, the top border
     * row of the project and editor panels, the content rows of the editor
     * panel joined by newlines, the status row and the active theme name.
     * {@code POST /tui/theme} answers with the same shape after the switch,
     * so one payload carries both the new theme name and a frame painted in
     * it. Rows are returned without their trailing padding, and the status
     * line without the gutter the status bar paints around it, so the text
     * reads the same whatever the viewport width. Leading indentation of the
     * editor rows is kept, because diff context lines start with a space.
     */
    record ShellRenderView(String tabRow, String mainAreaTop, String editorArea, String status, String theme) {
    }

    /**
     * Payload of a rejected theme switch: the message of the fail fast
     * validation, which names the unknown theme and the available ones. It is
     * the entity of an untyped {@link Response}, which the native build cannot
     * see, so the deployment module registers it for reflection from the
     * Jandex index to stay serializable there.
     */
    record ThemeRejected(String error) {
    }

    /**
     * Payload of the keymap inspection: the keymap source plus one shortcut
     * string per action id.
     */
    record KeymapView(String source, Map<String, String> shortcuts) {
    }

    /**
     * Payload of the icon inspection: the resolved icon face and, per
     * requested file name, the two symbol faces.
     */
    record FileIconsView(String mode, Map<String, FileIconView> files) {
    }

    /**
     * One resolved icon: the Nerd Font glyph and the ASCII fallback marker.
     */
    record FileIconView(String glyph, String ascii) {
    }
}
