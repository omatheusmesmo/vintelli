package dev.omatheusmesmo.vintelli.local;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;

import dev.omatheusmesmo.vintelli.api.capability.RunCapability.DebugSupport;

/** Known Maven debug switches; never enables debugging of the build tool itself. */
final class DebugLaunch {

    private DebugLaunch() { }

    static DebugSupport support(String identifier) {
        return switch (identifier) {
            case "maven:quarkus:dev", "maven:spring-boot:run", "maven:test" -> new DebugSupport(true, "");
            default -> new DebugSupport(false, "No known debug launch for '" + identifier + "'");
        };
    }

    static List<String> arguments(String identifier, int port) {
        String jdwp = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:" + port;
        return switch (identifier) {
            case "maven:quarkus:dev" -> List.of("-Ddebug=" + port, "-DdebugHost=127.0.0.1", "-Dsuspend=y");
            case "maven:spring-boot:run" -> List.of("-Dspring-boot.run.jvmArguments=" + jdwp);
            case "maven:test" -> List.of("-Dmaven.surefire.debug=" + jdwp);
            default -> throw new IllegalArgumentException(support(identifier).reason());
        };
    }

    static void checkPort(int port) throws IOException {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Debug port must be between 1 and 65535");
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        }
    }
}
