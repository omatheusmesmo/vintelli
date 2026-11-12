package dev.omatheusmesmo.vintelli.debug;

import java.util.List;

import org.eclipse.lsp4j.debug.SourceBreakpoint;

import dev.omatheusmesmo.vintelli.api.capability.DebugCapability;

/** Converts the public breakpoint model to the DAP request model. */
final class DapSourceBreakpoints {

    private DapSourceBreakpoints() {}

    static SourceBreakpoint[] fromApi(List<DebugCapability.SourceBreakpoint> breakpoints) {
        return breakpoints.stream().map(point -> {
            SourceBreakpoint breakpoint = new SourceBreakpoint();
            breakpoint.setLine(point.line());
            return breakpoint;
        }).toArray(SourceBreakpoint[]::new);
    }
}
