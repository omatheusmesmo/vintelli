package dev.omatheusmesmo.vintelli.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import io.quarkus.security.Authenticated;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Serves the xterm.js client of the browser terminal. The page is a classpath
 * resource rather than a static file so that it sits behind the same gate and
 * basic authentication as the {@code /tui/ws} upgrade: a browser that loads the
 * page answers the basic challenge once, and the WebSocket handshake to the
 * same origin then carries the cached credentials. The xterm.js library itself
 * is served from the mvnpm artifact under {@code /_static/}.
 */
@Path("/tui/terminal")
@Singleton
public final class WebTerminalPageResource {

    private static final String PAGE = "web-terminal/index.html";
    private static final String SCRIPT = "web-terminal/terminal.js";

    @GET
    @Authenticated
    @Produces(MediaType.TEXT_HTML + ";charset=UTF-8")
    public String page() {
        return read(PAGE);
    }

    @GET
    @Path("terminal.js")
    @Authenticated
    @Produces("text/javascript;charset=UTF-8")
    public String script() {
        return read(SCRIPT);
    }

    private static String read(String resource) {
        try (InputStream in = WebTerminalPageResource.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new NotFoundException();
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
