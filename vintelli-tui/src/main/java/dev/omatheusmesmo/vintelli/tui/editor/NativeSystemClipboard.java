package dev.omatheusmesmo.vintelli.tui.editor;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Clipboard through the first platform tool found on the PATH for each
 * direction: {@code wl-copy} and {@code wl-paste} on Wayland, {@code xclip} or
 * {@code xsel} on X11, {@code pbcopy} and {@code pbpaste} on macOS, and
 * {@code clip.exe}, which only writes, on Windows and WSL. Each call runs a
 * fresh process with a timeout.
 */
final class NativeSystemClipboard implements SystemClipboard {

    private static final long TIMEOUT_SECONDS = 2;
    private static final Executor EXCHANGE = task -> Thread.ofVirtual().name("vintelli-clipboard").start(task);
    private static final List<Tool> WRITE_TOOLS = List.of(
            new Tool("wl-copy", List.of()),
            new Tool("xclip", List.of("-selection", "clipboard")),
            new Tool("xsel", List.of("--clipboard", "--input")),
            new Tool("pbcopy", List.of()),
            new Tool("clip.exe", List.of()));
    private static final List<Tool> READ_TOOLS = List.of(
            new Tool("wl-paste", List.of("--no-newline")),
            new Tool("xclip", List.of("-selection", "clipboard", "-o")),
            new Tool("xsel", List.of("--clipboard", "--output")),
            new Tool("pbpaste", List.of()));

    private final List<String> writer;
    private final List<String> reader;

    NativeSystemClipboard(String path) {
        this.writer = command(path, WRITE_TOOLS);
        this.reader = command(path, READ_TOOLS);
    }

    @Override
    public boolean available() {
        return writer != null;
    }

    @Override
    public boolean write(String text) {
        return writer != null && run(writer, text.getBytes(StandardCharsets.UTF_8)) != null;
    }

    @Override
    public Optional<String> read() {
        if (reader == null) {
            return Optional.empty();
        }
        byte[] output = run(reader, null);
        return output == null ? Optional.empty() : Optional.of(new String(output, StandardCharsets.UTF_8));
    }

    private static List<String> command(String path, List<Tool> tools) {
        if (path == null) {
            return null;
        }
        for (Tool tool : tools) {
            for (String directory : path.split(File.pathSeparator)) {
                Path candidate = Path.of(directory, tool.name());
                if (!directory.isEmpty() && Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return tool.commandLine(candidate);
                }
            }
        }
        return null;
    }

    /**
     * Runs one tool, feeding the input to its standard input when present. The whole exchange
     * runs on a virtual thread so the timeout also covers a tool that never reads its input or
     * never closes its output, which would otherwise block the caller, the UI thread.
     *
     * @return the standard output, or null when the tool failed or timed out
     */
    private static byte[] run(List<String> command, byte[] input) {
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(input == null ? ProcessBuilder.Redirect.PIPE : ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException failure) {
            return null;
        }
        CompletableFuture<byte[]> exchange = CompletableFuture.supplyAsync(() -> exchange(process, input), EXCHANGE);
        try {
            byte[] output = exchange.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0 ? output : null;
        } catch (TimeoutException | ExecutionException failure) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            process.destroyForcibly();
        }
    }

    private static byte[] exchange(Process process, byte[] input) {
        try {
            try (var stdin = process.getOutputStream()) {
                if (input != null) {
                    stdin.write(input);
                }
            }
            return input == null ? process.getInputStream().readAllBytes() : new byte[0];
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private record Tool(String name, List<String> arguments) {

        List<String> commandLine(Path binary) {
            List<String> command = new ArrayList<>();
            command.add(binary.toString());
            command.addAll(arguments);
            return List.copyOf(command);
        }
    }
}
