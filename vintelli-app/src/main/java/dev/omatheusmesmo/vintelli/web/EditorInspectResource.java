package dev.omatheusmesmo.vintelli.web;

import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.util.Map;

/** Gated headless inspection surface for editor behavior and native integration tests. */
@Path("/inspect/editor")
@Singleton
@Produces(MediaType.APPLICATION_JSON)
public final class EditorInspectResource {
    private static final int MAX_INSERT_CHARS = 16 * 1024;

    private final Instance<BuiltinEditor> editor;
    private final boolean enabled;

    @Inject
    public EditorInspectResource(Instance<BuiltinEditor> editor,
            @ConfigProperty(name = "vintelli.editor.inspect.enabled", defaultValue = "false") boolean enabled) {
        this.editor = editor;
        this.enabled = enabled;
    }

    @GET
    public BuiltinEditor.DocumentView document() {
        requireEnabled();
        return editor.get().activeDocument().orElseThrow(NotFoundException::new);
    }

    @POST
    @Path("/open")
    public Response open(@QueryParam("file") String file, @QueryParam("line") @DefaultValue("0") int line) {
        requireEnabled();
        try {
            File requested = new File(file == null ? "" : file);
            if (file == null || file.isBlank() || requested.isAbsolute()) {
                return rejected("The file must be a project-relative path");
            }
            var relative = requested.toPath().normalize();
            if (relative.getNameCount() > 0 && relative.getName(0).toString().equals("..")) {
                return rejected("The file must be a project-relative path");
            }
            var result = editor.get().open(new BuiltinEditor.OpenRequest(relative, line));
            if (result.isFailure()) {
                return rejected(result.failureOf().orElseThrow().reason());
            }
            return Response.ok(editor.get().activeDocument().orElseThrow()).build();
        } catch (InvalidPathException failure) {
            return rejected("Invalid file path");
        } catch (IllegalArgumentException failure) {
            return rejected(failure.getMessage());
        }
    }

    @POST
    @Path("/insert")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response insert(InsertRequest request) {
        requireEnabled();
        if (request == null || request.text() == null || request.text().indexOf('\0') >= 0
                || request.text().length() > MAX_INSERT_CHARS) {
            return rejected("Insert text must be valid and no longer than 16 KiB");
        }
        if (editor.get().activeDocument().isEmpty()) {
            return rejected("No file is open");
        }
        editor.get().insert(request.text());
        return Response.ok(editor.get().activeDocument().orElseThrow()).build();
    }

    @POST
    @Path("/undo")
    public Response undo() {
        requireEnabled();
        if (!editor.get().undo()) {
            return Response.status(Response.Status.CONFLICT).entity(Map.of("error", "No edit to undo")).build();
        }
        return Response.ok(editor.get().activeDocument().orElseThrow()).build();
    }

    @POST
    @Path("/save")
    public Response save() {
        requireEnabled();
        var result = editor.get().save();
        if (result.isFailure()) {
            var failure = result.failureOf().orElseThrow();
            String message = failure.detail().isBlank()
                    ? failure.reason()
                    : failure.reason() + ": " + failure.detail();
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", message))
                    .build();
        }
        return Response.ok(editor.get().activeDocument().orElseThrow()).build();
    }

    @POST
    @Path("/pointer")
    public Response pointer(@QueryParam("gesture") String gesture, @QueryParam("x") int x, @QueryParam("y") int y,
            @QueryParam("width") @DefaultValue("80") int width, @QueryParam("height") @DefaultValue("24") int height) {
        requireEnabled();
        BuiltinEditor.PointerGesture parsed;
        try {
            parsed = BuiltinEditor.PointerGesture.valueOf(gesture == null ? "" : gesture);
        } catch (IllegalArgumentException unknown) {
            return rejected("The gesture must be PRESS, DRAG, RELEASE, SCROLL_UP or SCROLL_DOWN");
        }
        if (width < 4 || width > 400 || height < 3 || height > 200) {
            return rejected("The viewport must be 4 to 400 columns and 3 to 200 rows");
        }
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return rejected("The pointer must lie inside the viewport");
        }
        if (editor.get().activeDocument().isEmpty()) {
            return rejected("No file is open");
        }
        return Response.ok(editor.get().routePointer(parsed, x, y, width, height).orElseThrow()).build();
    }

    @GET
    @Path("/pointer")
    public BuiltinEditor.PointerState pointerState() {
        requireEnabled();
        return editor.get().pointerState().orElseThrow(NotFoundException::new);
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new NotFoundException();
        }
    }

    private static Response rejected(String message) {
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", message)).build();
    }

    public record InsertRequest(String text) {}
}
