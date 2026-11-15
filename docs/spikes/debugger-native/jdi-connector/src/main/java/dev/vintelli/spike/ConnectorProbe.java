package dev.vintelli.spike;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.VirtualMachineManager;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.StepRequest;

public final class ConnectorProbe {

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "providers" -> providers();
            case "bootstrap" -> bootstrap();
            case "session" -> session(Path.of(args[1]), Path.of(args[2]));
            default -> throw new IllegalArgumentException("Unknown mode " + args[0]);
        }
    }

    static void providers() {
        try {
            for (ServiceLoader.Provider<Connector> provider : ServiceLoader.load(Connector.class).stream().toList()) {
                String type = provider.type().getName();
                try {
                    System.out.println("PROVIDER " + type + " status=instantiated name=" + provider.get().name());
                } catch (ServiceConfigurationError failure) {
                    System.out.println("PROVIDER " + type + " status=failed " + chain(failure));
                }
            }
        } catch (ServiceConfigurationError failure) {
            System.out.println("PROVIDERS lookup failed " + chain(failure));
        }
    }

    static void bootstrap() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            VirtualMachineManager manager = Bootstrap.virtualMachineManager();
            System.setErr(originalErr);
            System.out.println("BOOTSTRAP ok connectors=" + manager.allConnectors().stream().map(Connector::name).toList());
        } catch (Throwable failure) {
            System.setErr(originalErr);
            System.out.println("BOOTSTRAP failed " + chain(failure));
        }
        System.out.println("CAPTURED_STDERR " + captured.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), " | "));
    }

    static void session(Path java, Path classes) throws Exception {
        Process debuggee = new ProcessBuilder(java.toString(),
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0",
                "-cp", classes.toString(), Debuggee.class.getName()).redirectErrorStream(true).start();
        try {
            String line = new BufferedReader(new InputStreamReader(debuggee.getInputStream(), StandardCharsets.UTF_8)).readLine();
            int port = Integer.parseInt(line.substring(line.lastIndexOf(' ') + 1));
            System.out.println("SESSION_DEBUGGEE_PORT " + port);
            System.out.println(attachAndStep(port));
        } finally {
            debuggee.destroyForcibly();
        }
    }

    static String attachAndStep(int port) throws Exception {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(candidate -> candidate.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var arguments = connector.defaultArguments();
        arguments.get("hostname").setValue("127.0.0.1");
        arguments.get("port").setValue(String.valueOf(port));
        arguments.get("timeout").setValue("5000");
        VirtualMachine vm = connector.attach(arguments);
        try {
            var prepare = vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter(Debuggee.class.getName());
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
            int breakpointLine = frame.location().lineNumber();
            var step = vm.eventRequestManager().createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
            step.addCountFilter(1);
            step.enable();
            stopped.resume();
            EventSet stepped = await(vm, StepEvent.class);
            StepEvent after = (StepEvent) stepped.stream().filter(StepEvent.class::isInstance).findFirst().orElseThrow();
            var afterFrame = after.thread().frame(0);
            int doubled = ((IntegerValue) afterFrame.getValue(afterFrame.visibleVariableByName("doubled"))).value();
            return "SESSION vm=" + vm.name() + " function=" + function + " value=" + value + " doubled=" + doubled
                    + " breakpointLine=" + breakpointLine + " stepLine=" + after.location().lineNumber()
                    + " threads=" + vm.allThreads().stream().map(t -> t.name()).toList();
        } finally {
            vm.dispose();
        }
    }

    static EventSet await(VirtualMachine vm, Class<? extends Event> expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            EventSet events = vm.eventQueue().remove(1000);
            if (events == null) continue;
            if (events.stream().anyMatch(expected::isInstance)) return events;
            events.resume();
        }
        throw new IllegalStateException("No " + expected.getSimpleName());
    }

    static String chain(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (!text.isEmpty()) text.append(" <- ");
            text.append(current.getClass().getName()).append(": ").append(current.getMessage());
            if (current.getCause() == current) break;
        }
        return text.toString();
    }
}
