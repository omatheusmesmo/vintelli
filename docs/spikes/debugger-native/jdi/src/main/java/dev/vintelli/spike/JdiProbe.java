package dev.vintelli.spike;

import java.time.Duration;
import java.util.List;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.StepRequest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/** Isolated experiment; not part of Vintelli's runtime modules or public API. */
@Path("/probe")
@Singleton
@Blocking
public final class JdiProbe {
    private final int port;
    @Inject
    public JdiProbe(@ConfigProperty(name = "spike.target.port") int port) { this.port = port; }

    @GET
    public Result probe() throws Exception {
        var connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(candidate -> candidate.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var arguments = connector.defaultArguments();
        arguments.get("hostname").setValue("127.0.0.1");
        arguments.get("port").setValue(String.valueOf(port));
        arguments.get("timeout").setValue("5000");
        VirtualMachine vm = connector.attach(arguments);
        try {
            var prepare = vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter("dev.vintelli.spike.Debuggee");
            prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            prepare.enable();
            EventSet prepared = await(vm, ClassPrepareEvent.class);
            ClassPrepareEvent type = (ClassPrepareEvent) prepared.stream().filter(ClassPrepareEvent.class::isInstance).findFirst().orElseThrow();
            var method = type.referenceType().methodsByName("checkpoint").getFirst();
            var breakpoint = vm.eventRequestManager().createBreakpointRequest(method.allLineLocations().getFirst());
            breakpoint.enable();
            prepared.resume();
            EventSet stopped = await(vm, BreakpointEvent.class);
            BreakpointEvent hit = (BreakpointEvent) stopped.stream().filter(BreakpointEvent.class::isInstance).findFirst().orElseThrow();
            var thread = hit.thread();
            var frame = thread.frame(0);
            int value = ((IntegerValue) frame.getValue(frame.visibleVariableByName("value"))).value();
            String function = frame.location().method().name();
            int line = frame.location().lineNumber();
            var step = vm.eventRequestManager().createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
            step.addCountFilter(1);
            step.enable();
            stopped.resume();
            EventSet stepped = await(vm, StepEvent.class);
            StepEvent after = (StepEvent) stepped.stream().filter(StepEvent.class::isInstance).findFirst().orElseThrow();
            int doubled = ((IntegerValue) after.thread().frame(0).getValue(after.thread().frame(0).visibleVariableByName("doubled"))).value();
            return new Result(vm.name(), function, value, doubled, line, after.location().lineNumber(),
                    vm.allThreads().stream().map(t -> t.name()).toList());
        } finally { vm.dispose(); }
    }

    private static EventSet await(VirtualMachine vm, Class<? extends Event> expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            EventSet events = vm.eventQueue().remove(1000);
            if (events == null) continue;
            if (events.stream().anyMatch(expected::isInstance)) return events;
            events.resume();
        }
        throw new IllegalStateException("No " + expected.getSimpleName());
    }

    public record Result(String vm, String function, int value, int doubled, int breakpointLine, int stepLine, List<String> threads) { }
}
