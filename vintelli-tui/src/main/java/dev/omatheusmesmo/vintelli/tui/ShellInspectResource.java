package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.keymap.KeyAction;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.omatheusmesmo.vintelli.tui.keymap.KeymapProfile;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Color.Indexed;
import dev.tamboui.style.Color.Rgb;
import dev.tamboui.style.Modifier;
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
import dev.omatheusmesmo.vintelli.tui.maven.MavenModule;
import dev.omatheusmesmo.vintelli.tui.maven.MavenProjectLoad;
import dev.omatheusmesmo.vintelli.tui.maven.MavenProjectReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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

    /**
     * The palette variables the render payload reports: one chrome
     * background, foreground and accent plus an editor background, keyword
     * and comment, so a caller sees both palettes of the active theme
     * without the payload carrying the whole stylesheet.
     */
    private static final List<String> INSPECTED_VARIABLES = List.of(
            "panel", "chrome-fg", "tab-selected-bg", "editor-bg", "editor-keyword", "editor-comment");

    private static final Rect VIEWPORT = new Rect(0, 0, 80, 24);
    private static final int TOOLBAR_ROW = 0;
    private static final int TAB_ROW = 1;
    private static final int MAIN_AREA_TOP_ROW = 2;
    private static final int FIRST_EDITOR_ROW = 2;
    private static final int LAST_EDITOR_ROW = 12;
    private static final int STATUS_ROW = 23;

    private final Instance<VintelliShell> shell;
    private final Instance<ToolWindowRegistry> registry;
    private final boolean enabled;
    private final String projectRoot;

    @Inject
    public ShellInspectResource(Instance<VintelliShell> shell, Instance<ToolWindowRegistry> registry,
            @ConfigProperty(name = "vintelli.inspect.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "vintelli.local.project-root", defaultValue = ".") String projectRoot) {
        this.shell = shell;
        this.registry = registry;
        this.enabled = enabled;
        this.projectRoot = projectRoot;
    }

    @GET
    @Path("/render")
    @Produces(MediaType.APPLICATION_JSON)
    public ShellRenderView render(@QueryParam("focus") String focus) {
        if (!enabled) {
            throw new NotFoundException();
        }
        return renderView(shell.get(), focus);
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
            return Response.ok(renderView(liveShell, null)).build();
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

    /**
     * The foreground color runs of the editor rows, in row order: one entry
     * per maximal run of neighboring cells that share a foreground color, so a
     * caller sees which cells carry which palette color without the payload
     * carrying a style per cell. A row contributes no run past its last
     * non-blank cell, matching the stripped text of {@link #editorRows(Buffer)}.
     * Package visible so the payload can be exercised without a live shell.
     */
    static List<EditorRunView> editorRuns(Buffer buffer) {
        List<EditorRunView> runs = new ArrayList<>();
        for (int row = FIRST_EDITOR_ROW; row <= LAST_EDITOR_ROW; row++) {
            runs.addAll(rowRuns(buffer, row));
        }
        return runs;
    }

    private static List<EditorRunView> rowRuns(Buffer buffer, int row) {
        int last = -1;
        for (int x = 0; x < buffer.width(); x++) {
            if (!buffer.get(x, row).symbol().isBlank()) {
                last = x;
            }
        }
        List<EditorRunView> runs = new ArrayList<>();
        int start = 0;
        while (start <= last) {
            String fg = colorText(buffer.get(start, row).style().fg().orElse(null));
            StringBuilder text = new StringBuilder(buffer.get(start, row).symbol());
            int end = start + 1;
            while (end <= last && Objects.equals(fg, colorText(buffer.get(end, row).style().fg().orElse(null)))) {
                text.append(buffer.get(end, row).symbol());
                end++;
            }
            runs.add(new EditorRunView(row, text.toString(), fg));
            start = end;
        }
        return runs;
    }

    /**
     * A foreground color in the notation of the palette: the authored hex
     * value for a truecolor cell and {@code indexed(n)} for a cell the active
     * depth rewrote, so the caller compares it against a
     * {@code colors.editor-*} entry of the same payload whatever the color
     * depth is. A cell without a foreground color reports {@code null}, and a
     * color that is neither representation falls back to its own text.
     */
    private static String colorText(Color color) {
        if (color instanceof Rgb rgb) {
            return String.format(Locale.ROOT, "#%02X%02X%02X", rgb.r(), rgb.g(), rgb.b());
        }
        if (color instanceof Indexed indexed) {
            return "indexed(" + indexed.index() + ")";
        }
        return color == null ? null : color.toString();
    }

    /**
     * The reversed cells of the editor rows, right of the project panel: the
     * caret cell while an editor focus is painted, plus any selected cells.
     * Package visible so the payload can be exercised without a live shell.
     */
    static List<CaretCellView> editorCarets(Buffer buffer) {
        List<CaretCellView> carets = new ArrayList<>();
        for (int row = FIRST_EDITOR_ROW; row <= LAST_EDITOR_ROW; row++) {
            for (int x = VintelliShell.TOOL_STRIP_WIDTH + VintelliShell.PROJECT_PANEL_WIDTH + 1; x < buffer.width(); x++) {
                Cell cell = buffer.get(x, row);
                if (cell.style().addModifiers().contains(Modifier.REVERSED)) {
                    carets.add(new CaretCellView(x, row, cell.symbol()));
                }
            }
        }
        return carets;
    }

    private static ShellRenderView renderView(VintelliShell liveShell, String focus) {
        Buffer buffer = Buffer.empty(VIEWPORT);
        liveShell.paint(Frame.forTesting(buffer), focus);
        ShellThemes themes = liveShell.themes();
        return new ShellRenderView(
                rowText(buffer, TOOLBAR_ROW),
                rowText(buffer, TAB_ROW),
                rowText(buffer, MAIN_AREA_TOP_ROW),
                mainAreaRows(buffer),
                editorRows(buffer),
                editorRuns(buffer),
                editorCarets(buffer),
                rowText(buffer, STATUS_ROW).strip(),
                liveShell.activeTheme(),
                themes.colorLevel().name().toLowerCase(Locale.ROOT),
                inspectedPalette(themes));
    }

    /**
     * The inspected variables of the active theme as the active color depth
     * serves them: the authored hex value on truecolor, the rewritten index
     * on 256 colors. The payload therefore tells the two depths apart without
     * a terminal, which is what the integration tests assert on the native
     * runner, where the environment would otherwise decide.
     */
    private static Map<String, String> inspectedPalette(ShellThemes themes) {
        Map<String, String> variables = themes.variablesOf(themes.activeTheme());
        Map<String, String> palette = new LinkedHashMap<>();
        for (String name : INSPECTED_VARIABLES) {
            palette.put(name, variables.get(name));
        }
        return palette;
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

    /**
     * Reads the Maven project of the configured project root the way the Maven
     * tool window does and reports the outcome: the state, the reason when
     * there is no tree, and the module selectors in tree order. The pom is read
     * with the JDK XML parser, so every integration test, including the native
     * runner, exercises that parser; a parser the image cannot start makes this
     * call fail instead of staying silent. The endpoint is off like the render
     * endpoint unless {@code vintelli.inspect.enabled} is {@code true}.
     */
    @GET
    @Path("/maven")
    @Produces(MediaType.APPLICATION_JSON)
    public MavenView maven() {
        if (!enabled) {
            throw new NotFoundException();
        }
        return switch (MavenProjectReader.read(projectRoot)) {
            case MavenProjectLoad.Loaded loaded -> new MavenView("loaded", "", selectors(loaded.root()));
            case MavenProjectLoad.Missing missing -> new MavenView("missing", missing.reason(), List.of());
            case MavenProjectLoad.Unreadable unreadable -> new MavenView("unreadable", unreadable.reason(), List.of());
        };
    }

    private static List<String> selectors(MavenModule module) {
        List<String> found = new ArrayList<>();
        found.add(module.selector());
        for (MavenModule child : module.modules()) {
            found.addAll(selectors(child));
        }
        return found;
    }

    private static String mainAreaRows(Buffer buffer) {
        StringBuilder text = new StringBuilder();
        for (int row = MAIN_AREA_TOP_ROW; row < STATUS_ROW; row++) {
            if (row > MAIN_AREA_TOP_ROW) {
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
        return text.toString().stripTrailing();
    }

    /**
     * Payload offered only while {@code vintelli.inspect.enabled} is
     * {@code true}: the plain text of the main toolbar row (the menu button,
     * the project and branch widgets and the run widget), the plain text of the
     * tool window tab row, the top border
     * row of the project and editor panels, the whole main area (every row from
     * that top border down to the row above the status bar, joined by newlines,
     * so the project pane footer and the bottom tool windows are visible), the content rows of the editor
     * panel joined by newlines, the {@link EditorRunView} foreground color
     * runs of those rows, the {@link CaretCellView} reversed cells of the editor
     * rows, the status row, the active theme name, the color
     * depth the active theme renders with and the {@link #INSPECTED_VARIABLES}
     * of that theme as the depth serves them. {@code POST /tui/theme} answers
     * with the same shape after the switch, so one payload carries both the
     * new theme name and a frame painted in it. Rows are returned without
     * their trailing padding, and the status line without the gutter the
     * status bar paints around it, so the text reads the same whatever the
     * viewport width. Leading indentation of the editor rows is kept, because
     * diff context lines start with a space.
     */
    record ShellRenderView(String toolbar, String tabRow, String mainAreaTop, String mainArea, String editorArea,
            List<EditorRunView> editorRuns, List<CaretCellView> editorCarets,
            String status, String theme, String colorLevel, Map<String, String> colors) {
    }

    /**
     * One reversed cell of the editor rows: its column and row in the frame and
     * the symbol drawn in it. The caret of the focused editor is the reversed
     * cell at the cursor, so a caller asserts its position and character.
     */
    record CaretCellView(int x, int y, String symbol) {
    }

    /**
     * One maximal run of neighboring editor cells that share a foreground
     * color: the logical row, the text of the run and the color in the
     * notation of the palette ({@code #RRGGBB} for a truecolor cell,
     * {@code indexed(n)} for a cell the active depth rewrote), so a caller
     * compares it against a {@code colors.editor-*} entry of the same payload
     * whatever the color depth is. The color of a cell without a foreground
     * color is {@code null}.
     */
    record EditorRunView(int row, String text, String fg) {
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

    /**
     * The Maven project as the window reads it: {@code loaded}, {@code missing}
     * or {@code unreadable}, the reason for the last two, and the module
     * selectors in tree order with the empty root selector first.
     */
    record MavenView(String state, String reason, List<String> selectors) {
    }
}
