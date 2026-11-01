package dev.omatheusmesmo.vintelli.local;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** Resolves a configured shell id or executable path to fixed launch arguments. */
@Singleton
final class TerminalShellResolver {

    private final TerminalShell selected;
    private final List<TerminalShell> available;

    @Inject
    TerminalShellResolver(@ConfigProperty(name = "vintelli.local.terminal.shell", defaultValue = "auto") String shell) {
        this(shell, System.getProperty("os.name", ""), System.getenv());
    }

    TerminalShellResolver(String requested, String osName, Map<String, String> environment) {
        boolean windows = osName.toLowerCase(Locale.ROOT).contains("win");
        available = discover(windows, environment);
        selected = select(requested, windows, environment, available);
    }

    TerminalShell selected() {
        return selected;
    }

    List<TerminalShell> available() {
        return available;
    }

    private static TerminalShell select(String requested, boolean windows, Map<String, String> environment,
            List<TerminalShell> available) {
        String id = requested == null || requested.isBlank() ? "auto" : requested.strip().toLowerCase(Locale.ROOT);
        if ("auto".equals(id)) {
            if (windows) {
                return find(available, "cmd").orElseThrow(() -> unavailable("auto"));
            }
            String preferred = env(environment, "SHELL").orElse("");
            Optional<String> preferredPath = preferred.isBlank() ? Optional.empty()
                    : findExecutable(preferred, false, environment);
            if (preferredPath.isPresent()) {
                return fromExecutable(preferredPath.get(), false);
            }
            String basename = preferred.isBlank() ? ""
                    : Path.of(preferred).getFileName().toString().toLowerCase(Locale.ROOT);
            return find(available, basename).or(() -> find(available, "bash"))
                    .or(() -> find(available, "sh"))
                    .or(() -> available.stream().findFirst())
                    .orElseThrow(() -> unavailable("auto"));
        }
        if (requested != null && isPath(requested)) {
            Path path = Path.of(requested);
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException("A configured terminal shell path must be absolute: " + requested);
            }
            String executable = usable(path, windows)
                    .orElseThrow(() -> unavailable(requested));
            return fromExecutable(executable, windows);
        }
        String normalized = switch (id) {
            case "command-prompt", "command_prompt", "cmd.exe" -> "cmd";
            case "pwsh", "pwsh.exe", "powershell.exe" -> "powershell";
            case "gitbash", "git_bash" -> "git-bash";
            case "bash.exe" -> "bash";
            case "sh.exe" -> "sh";
            default -> id;
        };
        return find(available, normalized).orElseThrow(() -> unavailable(normalized));
    }

    private static List<TerminalShell> discover(boolean windows, Map<String, String> environment) {
        ArrayList<TerminalShell> result = new ArrayList<>();
        if (windows) {
            findExecutable(env(environment, "COMSPEC").orElse("cmd.exe"), true, environment)
                    .ifPresent(path -> result.add(new TerminalShell("cmd", "Command Prompt", path,
                            TerminalShell.Kind.COMMAND_PROMPT)));
            findPowerShell(true, environment)
                    .ifPresent(path -> result.add(new TerminalShell("powershell", "PowerShell", path,
                            TerminalShell.Kind.POWERSHELL)));
            findGitBash(environment)
                    .ifPresent(path -> result.add(new TerminalShell("git-bash", "Git Bash", path,
                            TerminalShell.Kind.POSIX)));
        } else {
            findPowerShell(false, environment)
                    .ifPresent(path -> result.add(new TerminalShell("powershell", "PowerShell", path,
                            TerminalShell.Kind.POWERSHELL)));
            for (String name : List.of("sh", "bash", "zsh", "fish")) {
                findUnixExecutable(name, environment)
                        .ifPresent(path -> result.add(new TerminalShell(name, name, path, TerminalShell.Kind.POSIX)));
            }
            env(environment, "SHELL").flatMap(path -> findExecutable(path, false, environment))
                    .ifPresent(path -> {
                        if (result.stream().noneMatch(shell -> shell.executable().equals(path))) {
                            result.add(fromExecutable(path, false));
                        }
                    });
        }
        return List.copyOf(result);
    }

    private static boolean isPath(String value) {
        return value.contains("/") || value.contains("\\");
    }

    private static TerminalShell fromExecutable(String executable, boolean windows) {
        String basename = Path.of(executable).getFileName().toString();
        String id = basename.toLowerCase(Locale.ROOT);
        if (windows && id.endsWith(".exe")) {
            id = id.substring(0, id.length() - ".exe".length());
        }
        TerminalShell.Kind kind = switch (id) {
            case "cmd" -> TerminalShell.Kind.COMMAND_PROMPT;
            case "powershell", "pwsh" -> TerminalShell.Kind.POWERSHELL;
            default -> TerminalShell.Kind.POSIX;
        };
        String displayName = switch (id) {
            case "cmd" -> "Command Prompt";
            case "powershell", "pwsh" -> "PowerShell";
            case "bash" -> windows ? "Git Bash" : "bash";
            default -> basename;
        };
        return new TerminalShell(id, displayName, executable, kind);
    }

    private static Optional<String> findPowerShell(boolean windows, Map<String, String> environment) {
        ArrayList<String> names = new ArrayList<>(List.of("pwsh.exe", "powershell.exe"));
        if (!windows) {
            names = new ArrayList<>(List.of("pwsh", "powershell"));
        }
        Optional<String> found = findFirst(names, windows, environment);
        if (found.isPresent() || !windows) {
            return found;
        }
        return env(environment, "SystemRoot")
                .map(root -> Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString())
                .flatMap(path -> findExecutable(path, true, environment));
    }

    private static Optional<String> findGitBash(Map<String, String> environment) {
        ArrayList<String> candidates = new ArrayList<>();
        for (String key : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
            env(environment, key).ifPresent(root -> {
                candidates.add(Path.of(root, "Git", "bin", "bash.exe").toString());
                candidates.add(Path.of(root, "Git", "usr", "bin", "bash.exe").toString());
            });
        }
        env(environment, "PATH").ifPresent(path -> {
            for (String directory : path.split(";")) {
                Path git = Path.of(directory).resolve("git.exe").toAbsolutePath().normalize();
                if (Files.isRegularFile(git) && git.getParent() != null && git.getParent().getParent() != null) {
                    Path root = git.getParent().getParent();
                    candidates.add(root.resolve("bin").resolve("bash.exe").toString());
                    candidates.add(root.resolve("usr").resolve("bin").resolve("bash.exe").toString());
                }
            }
        });
        return candidates.stream().map(path -> findExecutable(path, true, environment))
                .flatMap(Optional::stream).findFirst();
    }

    private static Optional<String> findUnixExecutable(String name, Map<String, String> environment) {
        Optional<String> inPath = findExecutable(name, false, environment);
        if (inPath.isPresent()) {
            return inPath;
        }
        return List.of("/bin/" + name, "/usr/bin/" + name).stream()
                .map(path -> findExecutable(path, false, environment)).flatMap(Optional::stream).findFirst();
    }

    private static Optional<String> findFirst(List<String> names, boolean windows,
            Map<String, String> environment) {
        return names.stream().map(name -> findExecutable(name, windows, environment))
                .flatMap(Optional::stream).findFirst();
    }

    private static Optional<String> findExecutable(String name, boolean windows,
            Map<String, String> environment) {
        if (name.isBlank()) {
            return Optional.empty();
        }
        Path requested = Path.of(name);
        if (requested.isAbsolute() || name.contains("/") || name.contains("\\")) {
            return usable(requested, windows);
        }
        String separator = windows ? ";" : File.pathSeparator;
        for (String directory : env(environment, "PATH").orElse("").split(Pattern.quote(separator))) {
            if (!directory.isBlank()) {
                Optional<String> found = usable(Path.of(directory).resolve(name), windows);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> usable(Path path, boolean windows) {
        if (!Files.isRegularFile(path) || (!windows && !Files.isExecutable(path))) {
            return Optional.empty();
        }
        return Optional.of(path.toAbsolutePath().normalize().toString());
    }

    private static Optional<TerminalShell> find(List<TerminalShell> shells, String id) {
        return shells.stream().filter(shell -> shell.id().equals(id)).findFirst();
    }

    private static Optional<String> env(Map<String, String> environment, String name) {
        return environment.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst();
    }

    private static IllegalArgumentException unavailable(String id) {
        return new IllegalArgumentException("Configured terminal shell '" + id
                + "' is not available; choices include auto, powershell, cmd, git-bash, sh, bash, zsh and fish");
    }
}
