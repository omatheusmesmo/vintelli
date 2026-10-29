package dev.omatheusmesmo.vintelli.core.acp;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import java.util.List;
import java.util.Optional;

/**
 * Answers session/request_permission: the first allow-once option wins, a
 * reject-once option is the fallback, and a request without any usable option
 * is answered as cancelled.
 */
public final class AcpPermissionPolicy {

    private AcpPermissionPolicy() {
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
