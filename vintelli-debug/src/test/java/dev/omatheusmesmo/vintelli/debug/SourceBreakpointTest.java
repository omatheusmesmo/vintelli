package dev.omatheusmesmo.vintelli.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;
import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.SourceBreakpoint;

class SourceBreakpointTest {

    private static SourceBreakpoint breakpoint(String logMessage, String hitCondition) {
        return new SourceBreakpoint("/project/Main.java", 3, DebugCapability.BreakpointState.PENDING, "", null,
                null, "", logMessage, hitCondition);
    }

    @Test
    void aBreakpointWithALogMessageIsALogpointAndOneWithoutIsNot() {
        assertTrue(breakpoint("count={count}", "").isLogpoint());
        assertFalse(breakpoint("   ", "").isLogpoint());
        assertFalse(new SourceBreakpoint("/project/Main.java", 3).isLogpoint());
    }

    @Test
    void logMessageAndHitConditionAreTrimmedAndOptional() {
        SourceBreakpoint point = breakpoint("  count={count}  ", "  >= 3 ");

        assertEquals("count={count}", point.logMessage());
        assertEquals(">= 3", point.hitCondition());
        assertEquals("", new SourceBreakpoint("/project/Main.java", 3).logMessage());
        assertEquals("", new SourceBreakpoint("/project/Main.java", 3).hitCondition());
    }

    @Test
    void aLogMessageMustBeOneLineWithinTheLengthCap() {
        assertThrows(IllegalArgumentException.class, () -> breakpoint("first\nsecond", ""));
        assertThrows(IllegalArgumentException.class, () -> breakpoint("first\rsecond", ""));
        assertThrows(IllegalArgumentException.class,
                () -> breakpoint("x".repeat(SourceBreakpoint.MAX_LOG_MESSAGE_LENGTH + 1), ""));
        assertEquals(SourceBreakpoint.MAX_LOG_MESSAGE_LENGTH,
                breakpoint("x".repeat(SourceBreakpoint.MAX_LOG_MESSAGE_LENGTH), "").logMessage().length());
    }

    @ParameterizedTest
    @ValueSource(strings = { "3", "100", ">= 5", ">5", "== 7", "<=2", "< 9", "% 2" })
    void aHitConditionIsAPositiveNumberWithAnOptionalOperator(String value) {
        assertEquals(value.strip(), breakpoint("", value).hitCondition());
        assertTrue(SourceBreakpoint.isValidHitCondition(value));
    }

    @ParameterizedTest
    @ValueSource(strings = { "0", "-1", "three", "3.5", ">=", "=> 3", "3 4", "1234567890123", "% 0", "x > 2" })
    void aHitConditionThatIsNotACountIsRejected(String value) {
        assertThrows(IllegalArgumentException.class, () -> breakpoint("", value));
        assertFalse(SourceBreakpoint.isValidHitCondition(value));
    }

    @Test
    void withOutcomeKeepsConditionLogMessageAndHitCount() {
        SourceBreakpoint point = new SourceBreakpoint("/project/Main.java", 3, DebugCapability.BreakpointState.PENDING,
                "", null, null, "count > 1", "count={count}", "3");

        SourceBreakpoint verified = point.withOutcome(DebugCapability.BreakpointState.VERIFIED, "ok", 4,
                "/mapped/Main.java");

        assertEquals(DebugCapability.BreakpointState.VERIFIED, verified.state());
        assertEquals(4, verified.resolvedLine());
        assertEquals("count > 1", verified.condition());
        assertEquals("count={count}", verified.logMessage());
        assertEquals("3", verified.hitCondition());
        assertEquals(7, point.pendingAt(7, "").line());
        assertEquals("count={count}", point.pendingAt(7, "").logMessage());
    }
}
