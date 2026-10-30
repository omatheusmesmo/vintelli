package dev.omatheusmesmo.vintelli.web;

import dev.omatheusmesmo.vintelli.tui.web.WebTerminalSessions;
import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Capacity check for the browser terminal: runs after the security upgrade
 * check (so it only applies to authenticated handshakes) and rejects new
 * connections once the configured concurrent-session cap is reached, with a
 * 503 status. Keeping it after authentication avoids consuming a slot for
 * a handshake that fails basic auth.
 */
@Singleton
@Priority(WebTerminalCapacityCheck.AFTER_SECURITY_UPGRADE_CHECK)
public final class WebTerminalCapacityCheck implements HttpUpgradeCheck {

    static final int AFTER_SECURITY_UPGRADE_CHECK = 1000;

    private final WebTerminalSessions sessions;

    @Inject
    WebTerminalCapacityCheck(WebTerminalSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean appliesTo(String endpointId) {
        return WebTerminalGate.ENDPOINT_ID.equals(endpointId);
    }

    @Override
    public Uni<HttpUpgradeCheck.CheckResult> perform(HttpUpgradeCheck.HttpUpgradeContext context) {
        if (!sessions.tryReserve()) {
            return HttpUpgradeCheck.CheckResult.rejectUpgrade(503);
        }
        return HttpUpgradeCheck.CheckResult.permitUpgrade();
    }
}
