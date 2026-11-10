package dev.vintelli.spike;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Map;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public final class DebuggeeResource implements QuarkusTestResourceLifecycleManager {
    private Process process;
    @Override public Map<String, String> start() {
        try {
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            String classes = Path.of(Debuggee.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            process = new ProcessBuilder(java.toString(), "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0",
                    "-cp", classes, Debuggee.class.getName()).redirectErrorStream(true).start();
            var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            int port = Integer.parseInt(line.substring(line.lastIndexOf(' ') + 1));
            return Map.of("spike.target.port", String.valueOf(port));
        } catch (Exception failure) { stop(); throw new IllegalStateException(failure); }
    }
    @Override public void stop() {
        if (process != null) process.destroyForcibly();
    }
}
