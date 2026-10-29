package dev.omatheusmesmo.vintelli.web;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.inject.Singleton;

/**
 * Gate of the browser terminal: the surface is closed until
 * {@code vintelli.web.enabled} is true, and the upgrade then only reaches the
 * authenticated endpoint itself. The flag is read on every upgrade so the
 * surface can be switched at runtime, and while the flag is disabled the
 * handshake fails with 404 as if the route did not exist rather than with an
 * authentication challenge that would leak that the endpoint is there.
 *
 * <p>The other two checks of the surface stay shared: the Host and Origin
 * headers are validated by the global loopback guard
 * ({@code McpEndpointGuard}, the single host policy of the application) and
 * basic authentication is enforced by {@code @Authenticated} on the endpoint,
 * so nothing here duplicates either policy. Checks of this extension run
 * before the security check, which is what keeps a disabled surface answering
 * 404 even for unauthenticated handshakes.
 */
@Singleton
@Priority(WebTerminalGate.BEFORE_SECURITY_UPGRADE_CHECK)
public final class WebTerminalGate implements HttpUpgradeCheck {

    /**
     * Priority that outranks the built-in security upgrade check of
     * websockets-next (2147483547), the one value that orders this gate
     * before authentication, so the disabled gate decides the status.
     */
    static final int BEFORE_SECURITY_UPGRADE_CHECK = 2147483600;

    static final String ENDPOINT_ID = "web-terminal";
    static final String ENABLED = "vintelli.web.enabled";

    @Override
    public boolean appliesTo(String endpointId) {
        return ENDPOINT_ID.equals(endpointId);
    }

    @Override
    public Uni<HttpUpgradeCheck.CheckResult> perform(HttpUpgradeCheck.HttpUpgradeContext context) {
        Config config = ConfigProvider.getConfig();
        if (!config.getOptionalValue(ENABLED, Boolean.class).orElse(Boolean.FALSE)) {
            return HttpUpgradeCheck.CheckResult.rejectUpgrade(404);
        }
        return HttpUpgradeCheck.CheckResult.permitUpgrade();
    }
}
