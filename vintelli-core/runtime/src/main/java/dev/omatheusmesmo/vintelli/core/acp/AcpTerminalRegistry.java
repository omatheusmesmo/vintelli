package dev.omatheusmesmo.vintelli.core.acp;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves the terminal methods an agent calls on the client (terminal/create,
 * output, wait_for_exit, kill, release). Commands run as plain child processes
 * through {@link ProcessBuilder}, in a directory inside the session root, with
 * their combined output kept up to the agent's byte limit.
 */
public final class AcpTerminalRegistry {

    static final long DEFAULT_OUTPUT_LIMIT = 64 * 1024;

    private final Path root;
    private final Map<String, Terminal> terminals = new ConcurrentHashMap<>();

    public AcpTerminalRegistry(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public AcpSchema.CreateTerminalResponse create(AcpSchema.CreateTerminalRequest request) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(request.command());
        command.addAll(request.args() == null ? List.of() : request.args());
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory(request.cwd()).toFile())
                .redirectErrorStream(true);
        if (request.env() != null) {
            request.env().forEach(variable -> builder.environment().put(variable.name(), variable.value()));
        }
        Process process = builder.start();
        long limit = request.outputByteLimit() == null ? DEFAULT_OUTPUT_LIMIT : request.outputByteLimit();
        Terminal terminal = new Terminal(process, limit);
        String terminalId = UUID.randomUUID().toString();
        terminals.put(terminalId, terminal);
        terminal.startCapture();
        return new AcpSchema.CreateTerminalResponse(terminalId);
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
        AcpSchema.TerminalExitStatus exit = terminal.process.isAlive()
                ? null
                : new AcpSchema.TerminalExitStatus(terminal.process.exitValue(), null);
        return new AcpSchema.TerminalOutputResponse(terminal.output(), terminal.truncated(), exit);
    }

    public AcpSchema.WaitForTerminalExitResponse waitForExit(String terminalId) throws InterruptedException {
        Process process = find(terminalId).process;
        process.waitFor();
        return new AcpSchema.WaitForTerminalExitResponse(process.exitValue(), null);
    }

    public AcpSchema.KillTerminalCommandResponse kill(String terminalId) {
        find(terminalId).process.destroyForcibly();
        return new AcpSchema.KillTerminalCommandResponse();
    }

    public AcpSchema.ReleaseTerminalResponse release(String terminalId) {
        Terminal terminal = terminals.remove(terminalId);
        if (terminal == null) {
            throw new IllegalArgumentException("unknown terminal " + terminalId);
        }
        terminal.process.destroyForcibly();
        return new AcpSchema.ReleaseTerminalResponse();
    }

    private Terminal find(String terminalId) {
        Terminal terminal = terminals.get(terminalId);
        if (terminal == null) {
            throw new IllegalArgumentException("unknown terminal " + terminalId);
        }
        return terminal;
    }

    private static final class Terminal {

        private final Process process;
        private final long limit;
        private final StringBuilder buffer = new StringBuilder();
        private boolean truncated;

        Terminal(Process process, long limit) {
            this.process = process;
            this.limit = limit;
        }

        void startCapture() {
            Thread.ofVirtual().start(() -> capture(process.getInputStream()));
        }

        private void capture(InputStream stream) {
            try (stream) {
                byte[] chunk = new byte[4096];
                int read;
                while ((read = stream.read(chunk)) != -1) {
                    append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                }
            } catch (IOException _) {
                return;
            }
        }

        private synchronized void append(String text) {
            int room = (int) Math.max(0, limit - buffer.length());
            if (text.length() > room) {
                buffer.append(text, 0, room);
                truncated = true;
            } else {
                buffer.append(text);
            }
        }

        synchronized String output() {
            return buffer.toString();
        }

        synchronized boolean truncated() {
            return truncated;
        }
    }
}
