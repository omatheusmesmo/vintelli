package dev.omatheusmesmo.vintelli.core.acp;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Serves the terminal methods an agent calls on the client (terminal/create,
 * output, wait_for_exit, kill, release). Commands run as plain child processes
 * through {@link ProcessBuilder}, in a directory inside the session root, with
 * their combined output kept up to a server-capped byte limit. The child
 * inherits only an allow-listed subset of the environment.
 *
 * <p>UNGUARDED probe handler: this registry executes arbitrary commands
 * supplied by a foreign ACP agent with no permission gate, so it must not be
 * exposed outside the probe. The working-directory check is lexical and does
 * not follow symbolic links, so it is not a confinement boundary. Dangerous
 * agent-supplied environment variable names (LD_PRELOAD, LD_LIBRARY_PATH,
 * DYLD_*, PATH, JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS) are dropped before the
 * child is spawned, but the child still inherits the host's allow-listed PATH
 * and can exec anything.
 */
public final class AcpTerminalRegistry {

    static final long DEFAULT_OUTPUT_LIMIT = 64 * 1024;
    static final long MAX_OUTPUT_LIMIT = 1024 * 1024;
    static final int MAX_UTF8_CHARACTER_BYTES = 4;
    static final Set<String> ALLOWED_ENVIRONMENT = Set.of(
            "PATH", "HOME", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "TMP", "TEMP", "USER");

    private final Path root;
    private final Map<String, String> baseEnvironment;
    private final Map<String, Terminal> terminals = new ConcurrentHashMap<>();

    public AcpTerminalRegistry(Path root) {
        this(root, System.getenv());
    }

    AcpTerminalRegistry(Path root, Map<String, String> baseEnvironment) {
        this.root = root.toAbsolutePath().normalize();
        this.baseEnvironment = baseEnvironment;
    }

    public AcpSchema.CreateTerminalResponse create(AcpSchema.CreateTerminalRequest request) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(request.command());
        command.addAll(request.args() == null ? List.of() : request.args());
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory(request.cwd()).toFile())
                .redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.clear();
        for (String key : ALLOWED_ENVIRONMENT) {
            String value = baseEnvironment.get(key);
            if (value != null) {
                environment.put(key, value);
            }
        }
        if (request.env() != null) {
            request.env().forEach(variable -> {
                if (!isDangerousEnvironmentVariable(variable.name())) {
                    environment.put(variable.name(), variable.value());
                }
            });
        }
        Process process = builder.start();
        long limit = clampOutputLimit(request.outputByteLimit());
        Terminal terminal = new Terminal(process, limit);
        String terminalId = UUID.randomUUID().toString();
        terminals.put(terminalId, terminal);
        terminal.startCapture();
        return new AcpSchema.CreateTerminalResponse(terminalId);
    }

    static long clampOutputLimit(Long requested) {
        if (requested == null) {
            return DEFAULT_OUTPUT_LIMIT;
        }
        if (requested < 0) {
            return 0;
        }
        return Math.min(requested, MAX_OUTPUT_LIMIT);
    }

    private Path workingDirectory(String requested) {
        Path candidate = requested == null ? root : Path.of(requested);
        Path resolved = (candidate.isAbsolute() ? candidate : root.resolve(candidate)).normalize();
        if (!resolved.startsWith(root)) {
            throw new AcpAccessDeniedException("terminal working directory is outside the session root: " + requested);
        }
        return resolved;
    }

    public AcpSchema.TerminalOutputResponse output(String terminalId) {
        Terminal terminal = find(terminalId);
        if (!terminal.process.isAlive()) {
            terminal.awaitCapture();
        }
        AcpSchema.TerminalExitStatus exit = terminal.process.isAlive()
                ? null
                : new AcpSchema.TerminalExitStatus(terminal.process.exitValue(), null);
        return new AcpSchema.TerminalOutputResponse(terminal.output(), terminal.truncated(), exit);
    }

    public AcpSchema.WaitForTerminalExitResponse waitForExit(String terminalId) throws InterruptedException {
        Terminal terminal = find(terminalId);
        Process process = terminal.process;
        process.waitFor();
        terminal.awaitCapture();
        return new AcpSchema.WaitForTerminalExitResponse(process.exitValue(), null);
    }

    public AcpSchema.KillTerminalCommandResponse kill(String terminalId) {
        destroyProcessTree(find(terminalId).process);
        return new AcpSchema.KillTerminalCommandResponse();
    }

    public AcpSchema.ReleaseTerminalResponse release(String terminalId) {
        Terminal terminal = terminals.remove(terminalId);
        if (terminal == null) {
            throw new IllegalArgumentException("unknown terminal " + terminalId);
        }
        destroyProcessTree(terminal.process);
        return new AcpSchema.ReleaseTerminalResponse();
    }

    public void releaseAll() {
        for (String terminalId : List.copyOf(terminals.keySet())) {
            try {
                release(terminalId);
            } catch (IllegalArgumentException _) {
                terminals.remove(terminalId);
            }
        }
    }

    private static boolean isDangerousEnvironmentVariable(String name) {
        if (name == null) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.equals("LD_PRELOAD") || upper.equals("LD_LIBRARY_PATH")
                || upper.equals("PATH") || upper.equals("JAVA_TOOL_OPTIONS")
                || upper.equals("JDK_JAVA_OPTIONS")) {
            return true;
        }
        return upper.startsWith("DYLD_");
    }

    private void destroyProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        for (ProcessHandle descendant : process.descendants().toList()) {
            try {
                descendant.onExit().get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
    }

    public int size() {
        return terminals.size();
    }

    Process process(String terminalId) {
        return find(terminalId).process;
    }

    private Terminal find(String terminalId) {
        Terminal terminal = terminals.get(terminalId);
        if (terminal == null) {
            throw new IllegalArgumentException("unknown terminal " + terminalId);
        }
        return terminal;
    }

    static final class Terminal {

        private final Process process;
        private final long limit;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean truncated;

        Terminal(Process process, long limit) {
            this.process = process;
            this.limit = limit;
        }

        private volatile Thread captureThread;

        void startCapture() {
            captureThread = Thread.ofVirtual().start(() -> capture(process.getInputStream()));
        }

        void awaitCapture() {
            Thread thread = captureThread;
            if (thread != null) {
                try {
                    thread.join(5000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void capture(InputStream stream) {
            try (stream) {
                byte[] chunk = new byte[4096];
                int read;
                while ((read = stream.read(chunk)) != -1) {
                    appendBytes(chunk, 0, read);
                }
            } catch (IOException _) {
                return;
            }
        }

        synchronized void appendBytes(byte[] chunk, int off, int len) {
            if (truncated || len <= 0) {
                if (len > 0) {
                    truncated = true;
                }
                return;
            }
            long room = limit - buffer.size();
            if (len <= room) {
                buffer.write(chunk, off, len);
                return;
            }
            buffer.write(chunk, off, (int) Math.max(room, 0));
            truncated = true;
            dropIncompleteTrailingCharacter();
        }

        private void dropIncompleteTrailingCharacter() {
            byte[] bytes = buffer.toByteArray();
            if (bytes.length == 0) {
                return;
            }
            int lead = bytes.length - 1;
            while (lead > 0 && bytes.length - lead < MAX_UTF8_CHARACTER_BYTES && isContinuationByte(bytes[lead])) {
                lead--;
            }
            if (bytes.length - lead < utf8CharLength(bytes[lead])) {
                buffer.reset();
                buffer.write(bytes, 0, lead);
            }
        }

        synchronized String output() {
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }

        synchronized boolean truncated() {
            return truncated;
        }
    }

    private static boolean isContinuationByte(int value) {
        return (value & 0xC0) == 0x80;
    }

    private static int utf8CharLength(int lead) {
        int value = lead & 0xFF;
        if ((value & 0x80) == 0) {
            return 1;
        }
        if ((value & 0xE0) == 0xC0) {
            return 2;
        }
        if ((value & 0xF0) == 0xE0) {
            return 3;
        }
        if ((value & 0xF8) == 0xF0) {
            return 4;
        }
        return 1;
    }
}
