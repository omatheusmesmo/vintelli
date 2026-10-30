package dev.omatheusmesmo.vintelli.core.acp;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import java.util.List;
import java.util.Optional;

/**
 * Probe-only answer for {@code session/request_permission}: the first
 * allow-once option wins, a reject-once option is the fallback, and a request
 * without any usable option is answered as cancelled. This is the unguarded
 * policy used by the ACP SDK probe (#145); it is NOT the production approval
 * policy that #6 will implement, which must add real permission semantics.
 */
public final class AcpProbePermissionPolicy {

    private AcpProbePermissionPolicy() {
    }

    public static AcpSchema.RequestPermissionResponse decide(AcpSchema.RequestPermissionRequest request) {
        List<AcpSchema.PermissionOption> options = request.options();
        Optional<AcpSchema.PermissionOption> choice = firstOfKind(options, AcpSchema.PermissionOptionKind.ALLOW_ONCE)
                .or(() -> firstOfKind(options, AcpSchema.PermissionOptionKind.REJECT_ONCE));
        return choice
                .map(option -> new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionSelected(option.optionId())))
                .orElseGet(() -> new AcpSchema.RequestPermissionResponse(new AcpSchema.PermissionCancelled()));
    }

    private static Optional<AcpSchema.PermissionOption> firstOfKind(List<AcpSchema.PermissionOption> options,
                                                                  AcpSchema.PermissionOptionKind kind) {
        return options.stream().filter(option -> kind.equals(option.kind())).findFirst();
    }
}
