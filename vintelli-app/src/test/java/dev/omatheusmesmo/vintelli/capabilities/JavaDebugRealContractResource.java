package dev.omatheusmesmo.vintelli.capabilities;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/** Configures a real jdt.ls process and Java Debug bundle for an explicit contract run. */
public final class JavaDebugRealContractResource implements QuarkusTestResourceLifecycleManager {

    private Path root;
    private static int attachPort;

    @Override
    public Map<String, String> start() {
        Path jdtlsHome = requiredDirectory("VINTELLI_JDTLS_HOME");
        Path bundle = requiredFile("VINTELLI_JAVA_DEBUG_BUNDLE");
        Path executable = jdtlsHome.resolve("bin").resolve(
                System.getProperty("os.name", "").startsWith("Windows") ? "jdtls.bat" : "jdtls");
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new IllegalStateException("VINTELLI_JDTLS_HOME must contain an executable bin/jdtls");
        }
        try (JarFile ignored = new JarFile(bundle.toFile())) {
            root = Files.createTempDirectory("vintelli-java-debug-real-contract-");
            Path workspace = Files.createDirectory(root.resolve("workspace"));
            Files.createDirectories(root.resolve("jdtls-data"));
            try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                attachPort = reservation.getLocalPort();
            }
            Files.writeString(workspace.resolve("pom.xml"), """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>contract</groupId><artifactId>debug-target</artifactId><version>1</version>
                    </project>
                    """);
            String options = "{\"bundles\":[\"" + json(bundle.toAbsolutePath().toString()) + "\"]}";
            return Map.ofEntries(
                    Map.entry("vintelli.inspect.enabled", "true"),
                    Map.entry("vintelli.debug.enabled", "true"),
                    Map.entry("vintelli.debug.probe.attach-port", Integer.toString(attachPort)),
                    Map.entry("vintelli.local.project-root", workspace.toString()),
                    Map.entry("vintelli.lsp.profile", "java-debug-real-contract"),
                    Map.entry("vintelli.lsp.profiles.java-debug-real-contract.command", executable.toString()),
                    Map.entry("vintelli.lsp.profiles.java-debug-real-contract.args",
                            "-data," + root.resolve("jdtls-data")),
                    Map.entry("vintelli.lsp.profiles.java-debug-real-contract.workspace-root", workspace.toString()),
                    Map.entry("vintelli.lsp.profiles.java-debug-real-contract.java.home", System.getProperty("java.home")),
                    Map.entry("vintelli.lsp.profiles.java-debug-real-contract.java.min-version", "25"),
                    Map.entry("vintelli.lsp.profiles.java-debug-real-contract.initialization-options", options));
        } catch (IOException failure) {
            delete(root);
            stop();
            throw new IllegalStateException("Could not prepare the real Java debug contract workspace", failure);
        }
    }

    static int attachPort() {
        if (attachPort == 0) {
            throw new IllegalStateException("The real Java debug contract resource has not started");
        }
        return attachPort;
    }

    private static Path requiredDirectory(String variable) {
        String value = System.getenv(variable);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variable + " is required for JavaDebugRealContract");
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException(variable + " must name an existing directory");
        }
        return path;
    }

    private static Path requiredFile(String variable) {
        String value = System.getenv(variable);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variable + " is required for JavaDebugRealContract");
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(variable + " must name an existing Java Debug bundle JAR");
        }
        return path;
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void stop() {
        if (root != null) {
            Path cleanupRoot = root;
            ProcessHandle languageServer = languageServer(cleanupRoot.resolve("jdtls-data"));
            if (languageServer != null) {
                Thread cleanup = new Thread(() -> cleanupAfterExit(cleanupRoot, languageServer),
                        "vintelli-java-debug-contract-cleanup");
                cleanup.start();
            } else {
                delete(cleanupRoot);
            }
            root = null;
        }
        attachPort = 0;
    }

    private static ProcessHandle languageServer(Path dataDirectory) {
        String expectedDataDirectory = dataDirectory.toAbsolutePath().normalize().toString();
        try (var descendants = ProcessHandle.current().descendants()) {
            return descendants.filter(JavaDebugRealContractResource::isJavaProcess)
                    .filter(process -> process.info().arguments().map(arguments -> {
                for (int index = 0; index + 1 < arguments.length; index++) {
                    if (arguments[index].equals("-data")
                            && Path.of(arguments[index + 1]).toAbsolutePath().normalize().toString()
                                    .equals(expectedDataDirectory)) {
                        return true;
                    }
                }
                return false;
            }).orElse(false)).findFirst().orElse(null);
        }
    }

    private static boolean isJavaProcess(ProcessHandle process) {
        return process.info().command()
                .map(command -> Path.of(command).getFileName().toString().startsWith("java"))
                .orElse(false);
    }

    private static void cleanupAfterExit(Path cleanupRoot, ProcessHandle languageServer) {
        try {
            languageServer.onExit().get(60, TimeUnit.SECONDS);
            delete(cleanupRoot);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.err.println("Retaining Java Debug contract workspace after interrupted jdt.ls shutdown: "
                    + cleanupRoot);
        } catch (Exception failure) {
            System.err.println("Retaining Java Debug contract workspace because jdt.ls did not exit cleanly: "
                    + cleanupRoot + " (" + failure.getMessage() + ")");
        }
    }

    private static void delete(Path directory) {
        if (directory != null && Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException ignored) {
            }
        }
    }
}
