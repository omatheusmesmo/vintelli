package dev.omatheusmesmo.vintelli.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerminalShellResolverTest {

    @TempDir
    Path temp;

    @Test
    void discoversWindowsShellsAndBuildsFixedLaunchArguments() throws IOException {
        Path programFiles = Files.createDirectories(temp.resolve("Program Files"));
        Path gitBash = Files.createDirectories(programFiles.resolve("Git/bin")).resolve("bash.exe");
        Path powershell = Files.createDirectories(temp.resolve("PowerShell")).resolve("pwsh.exe");
        Path commandPrompt = temp.resolve("cmd.exe");
        Files.createFile(gitBash);
        Files.createFile(powershell);
        Files.createFile(commandPrompt);
        Map<String, String> environment = Map.of(
                "COMSPEC", commandPrompt.toString(),
                "ProgramFiles", programFiles.toString(),
                "PATH", temp + ";" + powershell.getParent());

        TerminalShellResolver powershellResolver = new TerminalShellResolver("powershell", "Windows 11", environment);
        assertEquals("powershell", powershellResolver.selected().id());
        assertEquals(List.of(powershell.toAbsolutePath().normalize().toString(), "-NoLogo", "-NoExit"),
                powershellResolver.selected().interactiveCommand());
        assertEquals(List.of(powershell.toAbsolutePath().normalize().toString(), "-NoLogo", "-NoProfile",
                "-NonInteractive", "-Command", "Write-Output selected-token"),
                powershellResolver.selected().oneShotCommand("Write-Output selected-token"));

        TerminalShellResolver gitBashResolver = new TerminalShellResolver("git-bash", "Windows 11", environment);
        assertEquals(gitBash.toAbsolutePath().normalize().toString(), gitBashResolver.selected().executable());
        assertTrue(gitBashResolver.available().stream().anyMatch(shell -> shell.id().equals("cmd")));

        TerminalShellResolver commandPromptResolver = new TerminalShellResolver("cmd", "Windows 11", environment);
        assertEquals(List.of(commandPrompt.toAbsolutePath().normalize().toString(), "/d", "/q"),
                commandPromptResolver.selected().interactiveCommand());
    }

    @Test
    void autoUsesTheConfiguredUnixShellAndKeepsCommandTextAsOneArgument() throws IOException {
        assumeFalse(TestProcesses.isWindows(), "Unix executable-bit checks are unavailable on Windows");
        Path shellDirectory = Files.createDirectories(temp.resolve("shells with spaces and 'quotes'"));
        Path bash = Files.createFile(shellDirectory.resolve("bash"));
        assertTrue(bash.toFile().setExecutable(true));
        Path emptyPath = Files.createDirectories(temp.resolve("empty-path"));
        Map<String, String> environment = Map.of("SHELL", bash.toString(), "PATH", emptyPath.toString());

        TerminalShellResolver resolver = new TerminalShellResolver("auto", "Linux", environment);
        assertEquals("bash", resolver.selected().id());
        assertEquals(bash.toAbsolutePath().normalize().toString(), resolver.selected().executable());
        assertTrue(resolver.available().stream().anyMatch(shell -> shell.executable()
                .equals(bash.toAbsolutePath().normalize().toString())));
        TerminalShellResolver explicit = new TerminalShellResolver(bash.toString(), "Linux", environment);
        assertEquals(bash.toAbsolutePath().normalize().toString(), explicit.selected().executable());
        String commandText = "echo 'a; b' && printf done";
        assertEquals(List.of(bash.toAbsolutePath().normalize().toString(), "-c", commandText),
                resolver.selected().oneShotCommand(commandText));
    }

    @Test
    void shellPathUsedByScriptIsPosixQuotedAsOneExecutableArgument() {
        String executable = "/tmp/shell with 'quotes'/bash";
        assertEquals("'/tmp/shell with '\\''quotes'\\''/bash' '-i'",
                LocalTerminalSession.quotePosixCommand(List.of(executable, "-i")));
    }

    @Test
    void anExplicitUnavailableShellFailsInsteadOfFallingBack() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new TerminalShellResolver("fish", "Linux", Map.of("PATH", temp.toString())));
        assertTrue(failure.getMessage().contains("fish"));
    }
}
