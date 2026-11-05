#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir
$InstallScript = Join-Path $ScriptDir 'install.ps1'

$TestTmp = Join-Path ([System.IO.Path]::GetTempPath()) ("vintelli-ps1-test-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $TestTmp -Force | Out-Null

function Write-Pass([string]$Message) {
    Write-Host "[PASS] $Message"
}

function Invoke-Installer([string[]]$InstallerArgs, [hashtable]$EnvOverrides = @{}) {
    $savedEnv = @{}
    foreach ($key in $EnvOverrides.Keys) {
        $savedEnv[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $EnvOverrides[$key], 'Process')
    }
    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $rawOut = & powershell -NoProfile -ExecutionPolicy Bypass -File $InstallScript @InstallerArgs 2>&1
        $code = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } else { 0 }
        $lines = @()
        foreach ($item in $rawOut) {
            if ($null -ne $item) {
                $lines += $item.ToString()
            }
        }
        return [pscustomobject]@{
            ExitCode = $code
            Output   = ($lines -join "`n")
        }
    } finally {
        $ErrorActionPreference = $prevPref
        foreach ($key in $EnvOverrides.Keys) {
            [Environment]::SetEnvironmentVariable($key, $savedEnv[$key], 'Process')
        }
    }
}

function Assert-Contains([string]$Haystack, [string]$Needle, [string]$Description) {
    if (-not $Haystack.Contains($Needle)) {
        throw "[FAIL] ${Description}: expected output to contain '$Needle', got:`n$Haystack"
    }
}

function New-MockSourceRepo([string]$SrcDir) {
    $quarkusAppDir = Join-Path $SrcDir 'vintelli-app\target\quarkus-app'
    New-Item -ItemType Directory -Path $quarkusAppDir -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $SrcDir 'pom.xml') -Value '<project><modelVersion>4.0.0</modelVersion><groupId>dev.omatheusmesmo</groupId><artifactId>vintelli</artifactId><version>1.0.0-SNAPSHOT</version></project>' -Encoding UTF8

    $realJar = Join-Path $RepoRoot 'vintelli-app\target\quarkus-app\quarkus-run.jar'
    if (Test-Path -LiteralPath $realJar) {
        Copy-Item -Path (Join-Path $RepoRoot 'vintelli-app\target\quarkus-app\*') -Destination $quarkusAppDir -Recurse -Force
    } else {
        $stubDir = Join-Path $SrcDir 'stub-src'
        New-Item -ItemType Directory -Path $stubDir -Force | Out-Null
        $stubJava = @"
public final class StubMain {
    public static void main(String[] args) {
        if (args.length > 0 && ("--version".equals(args[0]) || "-v".equals(args[0]) || "version".equals(args[0]))) {
            System.out.println("vintelli 1.0.0-SNAPSHOT");
            return;
        }
        if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]) || "help".equals(args[0]))) {
            System.out.println("Available commands: agents, inspect, problems, run, terminal");
            return;
        }
        System.err.println("Unknown command");
        System.exit(127);
    }
}
"@
        $stubJavaFile = Join-Path $stubDir 'StubMain.java'
        [System.IO.File]::WriteAllText($stubJavaFile, $stubJava, [System.Text.UTF8Encoding]::new($false))
        & javac -d $stubDir $stubJavaFile
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to compile StubMain.java"
        }
        $jarDest = Join-Path $quarkusAppDir 'quarkus-run.jar'
        & jar --create --file $jarDest --main-class StubMain -C $stubDir StubMain.class
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to package stub quarkus-run.jar"
        }
        Remove-Item -LiteralPath $stubDir -Recurse -Force
    }

    $mvnwCmd = "@echo off`r`nexit /b 0`r`n"
    Set-Content -LiteralPath (Join-Path $SrcDir 'mvnw.cmd') -Value $mvnwCmd -Encoding ASCII
}

try {
    # 1. --help
    $res = Invoke-Installer -InstallerArgs @('--help')
    if ($res.ExitCode -ne 0) {
        throw "Expected --help to exit 0, got $($res.ExitCode): $($res.Output)"
    }
    Assert-Contains -Haystack $res.Output -Needle 'Usage: install.ps1' -Description '--help usage'
    Write-Pass "--help prints usage and exits 0"

    # 2. Invalid CLI flags fail non-zero
    $res = Invoke-Installer -InstallerArgs @('--not-a-valid-flag')
    if ($res.ExitCode -eq 0) {
        throw "Expected unknown option to fail non-zero"
    }
    Assert-Contains -Haystack $res.Output -Needle 'Unknown option' -Description 'unknown option message'

    $res = Invoke-Installer -InstallerArgs @('--source')
    if ($res.ExitCode -eq 0) {
        throw "Expected missing --source value to fail non-zero"
    }
    Write-Pass "Invalid CLI options fail with clear messages"

    # 3. Create mock source repo
    $mockSrc = Join-Path $TestTmp 'mock-source'
    New-MockSourceRepo -SrcDir $mockSrc

    # 4. --dry-run does not create files
    $dryPrefix = Join-Path $TestTmp 'dry-run-bin'
    $res = Invoke-Installer -InstallerArgs @('--dry-run', '--source', $mockSrc, '--prefix', $dryPrefix)
    if ($res.ExitCode -ne 0) {
        throw "Expected --dry-run to exit 0, got $($res.ExitCode): $($res.Output)"
    }
    Assert-Contains -Haystack $res.Output -Needle '[dry-run]' -Description 'dry-run output'
    if (Test-Path -LiteralPath (Join-Path $dryPrefix 'vintelli.exe')) {
        throw "--dry-run must not create vintelli.exe"
    }
    Write-Pass "--dry-run logs planned steps without modifying the filesystem"

    # 5. Health check failures
    $res = Invoke-Installer -InstallerArgs @('--source', (Join-Path $TestTmp 'missing-dir'), '--prefix', $dryPrefix)
    if ($res.ExitCode -eq 0) {
        throw "Expected missing source directory to fail"
    }
    Assert-Contains -Haystack $res.Output -Needle 'Source directory does not exist' -Description 'missing source error'

    $emptySrc = Join-Path $TestTmp 'empty-src'
    New-Item -ItemType Directory -Path $emptySrc -Force | Out-Null
    $res = Invoke-Installer -InstallerArgs @('--source', $emptySrc, '--prefix', $dryPrefix)
    if ($res.ExitCode -eq 0) {
        throw "Expected invalid source directory to fail"
    }
    Assert-Contains -Haystack $res.Output -Needle 'missing pom.xml' -Description 'invalid source error'

    $res = Invoke-Installer -InstallerArgs @('--source', $mockSrc, '--prefix', $dryPrefix) -EnvOverrides @{ VINTELLI_REQUIRED_JAVA_MAJOR = '99' }
    if ($res.ExitCode -eq 0) {
        throw "Expected Java 99 check to fail"
    }
    Assert-Contains -Haystack $res.Output -Needle 'Java 99+ is required' -Description 'Java version error'

    $res = Invoke-Installer -InstallerArgs @('--source', $mockSrc, '--prefix', $dryPrefix) -EnvOverrides @{ VINTELLI_MIN_DISK_MB = '999999999' }
    if ($res.ExitCode -eq 0) {
        throw "Expected disk space check to fail"
    }
    Assert-Contains -Haystack $res.Output -Needle 'Insufficient disk space' -Description 'disk space error'

    $res = Invoke-Installer -InstallerArgs @('--source', $mockSrc, '--prefix', $dryPrefix) -EnvOverrides @{ VINTELLI_BUILD_MODE = 'invalid' }
    if ($res.ExitCode -eq 0) {
        throw "Expected invalid build mode to fail"
    }
    Assert-Contains -Haystack $res.Output -Needle 'Invalid VINTELLI_BUILD_MODE' -Description 'build mode error'
    Write-Pass "Health checks reject invalid prerequisites and disk constraints"

    # 6. Clone mode (JVM fallback): build, install, delete source tree, verify persistence
    $jvmSrc = Join-Path $TestTmp 'jvm-source-copy'
    Copy-Item -LiteralPath $mockSrc -Destination $jvmSrc -Recurse -Force
    $jvmPrefix = Join-Path $TestTmp 'jvm-prefix'
    $jvmBin = Join-Path $jvmPrefix 'vintelli.exe'

    $res = Invoke-Installer -InstallerArgs @('--source', $jvmSrc, '--prefix', $jvmPrefix) -EnvOverrides @{
        VINTELLI_BUILD_MODE       = 'jvm'
        VINTELLI_SKIP_PATH_UPDATE = 'true'
        VINTELLI_NONINTERACTIVE   = 'true'
    }
    if ($res.ExitCode -ne 0) {
        throw "Clone mode install failed ($($res.ExitCode)):`n$($res.Output)"
    }
    if (-not (Test-Path -LiteralPath $jvmBin)) {
        throw "Expected installed binary at $jvmBin"
    }

    # Delete source directory completely and verify installed binary still works
    Remove-Item -LiteralPath $jvmSrc -Recurse -Force
    $verOut = (& $jvmBin --version 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw "Installed vintelli.exe --version failed after source deletion: $verOut"
    }
    $helpOut = (& $jvmBin --help 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw "Installed vintelli.exe --help failed after source deletion: $helpOut"
    }
    Assert-Contains -Haystack $verOut -Needle 'vintelli ' -Description 'installed --version output'
    Assert-Contains -Haystack $helpOut -Needle 'Available commands:' -Description 'installed --help output'
    Write-Pass "Clone mode (JVM) installs persistent vintelli.exe that survives source tree deletion"

    # 7. Idempotent re-install with --force
    Copy-Item -LiteralPath $mockSrc -Destination $jvmSrc -Recurse -Force
    $res = Invoke-Installer -InstallerArgs @('--source', $jvmSrc, '--prefix', $jvmPrefix, '--force') -EnvOverrides @{
        VINTELLI_BUILD_MODE       = 'jvm'
        VINTELLI_SKIP_PATH_UPDATE = 'true'
    }
    if ($res.ExitCode -ne 0) {
        throw "Idempotent re-install failed ($($res.ExitCode)):`n$($res.Output)"
    }
    Remove-Item -LiteralPath $jvmSrc -Recurse -Force
    $null = & $jvmBin --help
    if ($LASTEXITCODE -ne 0) {
        throw "vintelli.exe --help failed after re-install"
    }
    Write-Pass "Idempotent re-install (--force) preserves working installation"

    # 8. Release mode: checksum mismatch rolls back to backup; valid checksum succeeds
    $relDir = Join-Path $TestTmp 'release-assets'
    New-Item -ItemType Directory -Path $relDir -Force | Out-Null
    $relBin = Join-Path $relDir 'vintelli-windows-x86_64.exe'
    $nativeStubCs = Join-Path $relDir 'NativeStub.cs'
    $nativeStubCode = @"
using System;
internal static class NativeStub {
    private static int Main(string[] args) {
        if (args.Length > 0 && (args[0] == "--version" || args[0] == "-v" || args[0] == "version")) {
            Console.WriteLine("vintelli 1.0.0-SNAPSHOT (native)");
            return 0;
        }
        if (args.Length > 0 && (args[0] == "--help" || args[0] == "-h" || args[0] == "help")) {
            Console.WriteLine("Available commands: agents, inspect, problems, run, terminal");
            return 0;
        }
        Console.Error.WriteLine("Unknown command");
        return 127;
    }
}
"@
    [System.IO.File]::WriteAllText($nativeStubCs, $nativeStubCode, [System.Text.UTF8Encoding]::new($false))
    $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
    if (-not (Test-Path -LiteralPath $csc)) {
        $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe'
    }
    if (Test-Path -LiteralPath $csc) {
        $null = & $csc /nologo /target:exe /optimize+ "/out:$relBin" $nativeStubCs 2>&1
    } else {
        Add-Type -TypeDefinition $nativeStubCode -OutputAssembly $relBin -OutputType ConsoleApplication
    }

    $badShaFile = Join-Path $relDir 'vintelli-windows-x86_64.exe.bad.sha256'
    Set-Content -LiteralPath $badShaFile -Value '0000000000000000000000000000000000000000000000000000000000000000  vintelli-windows-x86_64.exe' -Encoding ASCII

    $res = Invoke-Installer -InstallerArgs @('--prefix', $jvmPrefix, '--force') -EnvOverrides @{
        VINTELLI_RELEASE_ASSET_URL    = $relBin
        VINTELLI_RELEASE_CHECKSUM_URL = $badShaFile
        VINTELLI_SKIP_PATH_UPDATE     = 'true'
    }
    if ($res.ExitCode -eq 0) {
        throw "Expected release install with bad SHA-256 checksum to fail"
    }
    Assert-Contains -Haystack $res.Output -Needle 'SHA-256 checksum mismatch' -Description 'checksum mismatch error'

    # Existing installation must still work after failed release install
    $null = & $jvmBin --help
    if ($LASTEXITCODE -ne 0) {
        throw "Backup was not restored properly after failed release install"
    }

    $validHash = (Get-FileHash -LiteralPath $relBin -Algorithm SHA256).Hash.ToLowerInvariant()
    $goodShaFile = Join-Path $relDir 'vintelli-windows-x86_64.exe.sha256'
    Set-Content -LiteralPath $goodShaFile -Value "$validHash  vintelli-windows-x86_64.exe" -Encoding ASCII

    $res = Invoke-Installer -InstallerArgs @('--prefix', $jvmPrefix, '--force') -EnvOverrides @{
        VINTELLI_RELEASE_ASSET_URL    = $relBin
        VINTELLI_RELEASE_CHECKSUM_URL = $goodShaFile
        VINTELLI_SKIP_PATH_UPDATE     = 'true'
    }
    if ($res.ExitCode -ne 0) {
        throw "Expected release install with valid SHA-256 checksum to succeed: $($res.Output)"
    }
    Assert-Contains -Haystack $res.Output -Needle 'Mode:    release' -Description 'release mode summary'
    Write-Pass "Release mode verifies SHA-256 checksum, rejects tampered binaries, and installs valid release"

    # 9. --uninstall removes binary and runtime and is idempotent
    $res = Invoke-Installer -InstallerArgs @('--prefix', $jvmPrefix, '--uninstall')
    if ($res.ExitCode -ne 0) {
        throw "--uninstall failed: $($res.Output)"
    }
    if (Test-Path -LiteralPath $jvmBin) {
        throw "Expected $jvmBin to be removed by --uninstall"
    }
    $res = Invoke-Installer -InstallerArgs @('--prefix', $jvmPrefix, '--uninstall')
    if ($res.ExitCode -ne 0) {
        throw "Second --uninstall should be idempotent and exit 0"
    }
    Write-Pass "--uninstall cleanly removes installation and is idempotent"

    # 10. Clone mode (native build): installs native runner and survives source deletion
    $nativeSrc = Join-Path $TestTmp 'native-src'
    Copy-Item -LiteralPath $mockSrc -Destination $nativeSrc -Recurse -Force
    $nativeRunnerArtifact = Join-Path $nativeSrc 'vintelli-app\target\vintelli-1.0.0-SNAPSHOT-runner.exe'
    Copy-Item -LiteralPath $relBin -Destination $nativeRunnerArtifact -Force

    $fakeGraal = Join-Path $TestTmp 'fake-graal'
    $fakeGraalBin = Join-Path $fakeGraal 'bin'
    New-Item -ItemType Directory -Path $fakeGraalBin -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $fakeGraalBin 'native-image.cmd') -Value "@echo off`r`nexit /b 0`r`n" -Encoding ASCII

    $nativePrefix = Join-Path $TestTmp 'native-bin'
    $res = Invoke-Installer -InstallerArgs @('--source', $nativeSrc, '--prefix', $nativePrefix) -EnvOverrides @{
        GRAALVM_HOME              = $fakeGraal
        VINTELLI_BUILD_MODE       = 'native'
        VINTELLI_SKIP_PATH_UPDATE = 'true'
    }
    if ($res.ExitCode -ne 0) {
        throw "Native clone install failed ($($res.ExitCode)):`n$($res.Output)"
    }
    Assert-Contains -Haystack $res.Output -Needle 'Mode:    clone-native' -Description 'clone-native mode summary'

    Remove-Item -LiteralPath $nativeSrc -Recurse -Force
    $nativeBin = Join-Path $nativePrefix 'vintelli.exe'
    $nativeHelpOut = (& $nativeBin --help | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw "Native vintelli.exe --help failed after deleting source directory"
    }
    Assert-Contains -Haystack $nativeHelpOut -Needle 'Available commands:' -Description 'native help output after source removal'
    Write-Pass "Clone mode (native) installs standalone binary and works after source removal"

    Write-Host "`nAll install.ps1 tests passed!"
} finally {
    if (Test-Path -LiteralPath $TestTmp) {
        Remove-Item -LiteralPath $TestTmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
