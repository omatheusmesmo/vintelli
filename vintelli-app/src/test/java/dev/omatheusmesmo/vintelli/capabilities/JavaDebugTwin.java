package dev.omatheusmesmo.vintelli.capabilities;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Maven wrapper twin that forwards the selected debug switch to a real JVM debuggee. */
public final class JavaDebugTwin {
    public static void main(String[] args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        List<String> arguments = Arrays.asList(args);
        String goal = arguments.getFirst();
        String debug;
        if (goal.equals("quarkus:dev")) {
            String port = arguments.stream().filter(a -> a.startsWith("-Ddebug=")).findFirst().orElseThrow().substring(8);
            if (!arguments.contains("-DdebugHost=127.0.0.1") || !arguments.contains("-Dsuspend=y")) {
                throw new IllegalArgumentException("Quarkus must suspend on loopback");
            }
            debug = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:" + port;
        } else {
            String prefix = goal.equals("test") ? "-Dmaven.surefire.debug=" : "-Dspring-boot.run.jvmArguments=";
            debug = arguments.stream().filter(a -> a.startsWith(prefix)).findFirst().orElseThrow().substring(prefix.length());
        }
        command.add(debug);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), Debuggee.class.getName()));
        System.exit(new ProcessBuilder(command).inheritIO().start().waitFor());
    }

    public static final class Debuggee {
        public static void main(String[] args) throws Exception {
            System.out.println("debuggee-started");
            System.out.println("debuggee-pid:" + ProcessHandle.current().pid());
            Thread.sleep(120_000);
        }
    }
}
