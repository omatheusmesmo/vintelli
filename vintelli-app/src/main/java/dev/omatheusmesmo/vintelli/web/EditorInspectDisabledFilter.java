package dev.omatheusmesmo.vintelli.web;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.ConfigProvider;

import jakarta.inject.Singleton;

/**
 * Ensures the gated editor inspection surface answers 404 (not found) when it
 * is disabled, before any content-type negotiation or message-body reading
 * happens. Without this, a POST to {@code /inspect/editor/insert} without a
 * JSON content type would short-circuit to 415 (Unsupported Media Type) in
 * the native image, leaking that the endpoint exists.
 *
 * <p>The enabled flag is read at request time (not construction time) because
 * it is a runtime property that tests override per run; reading it during
 * static initialization would freeze the default value into the native image.
 */
@Provider
@PreMatching
@Singleton
public final class EditorInspectDisabledFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext requestContext) {
        boolean enabled;
        try {
            enabled = ConfigProvider.getConfig()
                    .getOptionalValue("vintelli.editor.inspect.enabled", Boolean.class)
                    .orElse(false);
        } catch (RuntimeException unavailable) {
            enabled = false;
        }
        if (enabled) {
            return;
        }
        String path = requestContext.getUriInfo().getPath();
        if (path != null && (path.startsWith("inspect/editor") || path.startsWith("/inspect/editor"))) {
            requestContext.abortWith(Response.status(Response.Status.NOT_FOUND).build());
        }
    }
}
