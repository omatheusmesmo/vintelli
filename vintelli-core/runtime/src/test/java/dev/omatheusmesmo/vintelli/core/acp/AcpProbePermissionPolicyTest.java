package dev.omatheusmesmo.vintelli.core.acp;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AcpProbePermissionPolicyTest {

    private static final AcpSchema.PermissionOption ALLOW = new AcpSchema.PermissionOption("allow", "Allow", AcpSchema.PermissionOptionKind.ALLOW_ONCE);
    private static final AcpSchema.PermissionOption ALWAYS = new AcpSchema.PermissionOption("always", "Always", AcpSchema.PermissionOptionKind.ALLOW_ALWAYS);
    private static final AcpSchema.PermissionOption REJECT = new AcpSchema.PermissionOption("reject", "Reject", AcpSchema.PermissionOptionKind.REJECT_ONCE);

    @Test
    void choosesAllowOnceOverAlwaysAndReject() {
        assertSelected(List.of(REJECT, ALWAYS, ALLOW), "allow");
    }

    @Test
    void fallsBackToRejectOnceWhenNothingIsAllowed() {
        assertSelected(List.of(ALWAYS, REJECT), "reject");
    }

    @Test
    void answersCancelledWhenNoUsableOptionIsOffered() {
        AcpSchema.RequestPermissionResponse response = AcpProbePermissionPolicy.decide(request(List.of(ALWAYS)));

        assertInstanceOf(AcpSchema.PermissionCancelled.class, response.outcome());
    }

    private static void assertSelected(List<AcpSchema.PermissionOption> options, String expectedOptionId) {
        AcpSchema.RequestPermissionResponse response = AcpProbePermissionPolicy.decide(request(options));

        AcpSchema.PermissionSelected selected = assertInstanceOf(AcpSchema.PermissionSelected.class, response.outcome());
        assertEquals(expectedOptionId, selected.optionId());
    }

    private static AcpSchema.RequestPermissionRequest request(List<AcpSchema.PermissionOption> options) {
        AcpSchema.ToolCallUpdate toolCall = new AcpSchema.ToolCallUpdate("call-1", "write notes.txt", AcpSchema.ToolKind.EDIT, AcpSchema.ToolCallStatus.PENDING);
        return new AcpSchema.RequestPermissionRequest("session-1", toolCall, options);
    }
}
