package dev.omatheusmesmo.vintelli.web;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Answers 404 for the browser terminal page and its script while
 * {@code vintelli.web.enabled} is false. It runs before matching and before
 * authentication, so a disabled surface does not reveal itself with a 401
 * challenge. Mirrors {@link WebTerminalGate} for the WebSocket handshake.
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION - 100)
public final class WebTerminalPageGate implements ContainerRequestFilter {

    private static final String PAGE_PATH = "tui/terminal";

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (isPagePath(requestContext.getUriInfo().getPath()) && !WebTerminalGate.enabled()) {
            requestContext.abortWith(Response.status(Response.Status.NOT_FOUND).build());
        }
    }

    static boolean isPagePath(String path) {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        return relative.equals(PAGE_PATH) || relative.startsWith(PAGE_PATH + "/");
    }
}
