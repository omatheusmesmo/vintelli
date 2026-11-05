#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Script:DefaultRepoUrl = if ($env:VINTELLI_REPO_URL) { $env:VINTELLI_REPO_URL } else { 'https://github.com/omatheusmesmo/vintelli.git' }
$Script:DefaultReleasesApi = if ($env:VINTELLI_RELEASES_API) { $env:VINTELLI_RELEASES_API } else { 'https://api.github.com/repos/omatheusmesmo/vintelli/releases' }
$Script:DefaultReleaseDownloadBase = if ($env:VINTELLI_RELEASE_DOWNLOAD_BASE) { $env:VINTELLI_RELEASE_DOWNLOAD_BASE } else { 'https://github.com/omatheusmesmo/vintelli/releases/download' }
$Script:RequiredJavaMajor = if ($env:VINTELLI_REQUIRED_JAVA_MAJOR) { [int]$env:VINTELLI_REQUIRED_JAVA_MAJOR } else { 25 }

$Script:SourceDir = ''
$Script:PrefixDir = ''
$Script:TargetVersion = 'latest'
$Script:SystemInstall = $false
$Script:ForceInstall = $false
$Script:Uninstall = $false
$Script:DryRun = $false

$Script:TmpWorkDir = ''
$Script:BackupBin = ''
$Script:BackupRuntime = ''
$Script:BackupActive = $false
$Script:InstallDir = ''
$Script:InstallBin = ''
$Script:RuntimeDir = ''
$Script:InstallModeSummary = ''
$Script:InstalledVersionSummary = ''
$Script:JavaBin = ''

function Write-Info([string]$Message) {
    Write-Host "[INFO] $Message"
}

function Write-Ok([string]$Message) {
    Write-Host "[OK] $Message"
}

function Write-WarnMsg([string]$Message) {
    [Console]::Error.WriteLine("[WARN] $Message")
}

function Write-ErrorMsg([string]$Message) {
    [Console]::Error.WriteLine("[ERROR] $Message")
}

function Invoke-ExternalCommand([string]$Executable, [string[]]$Arguments = @()) {
    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $rawOut = & $Executable @Arguments 2>&1
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
            Lines    = $lines
        }
    } finally {
        $ErrorActionPreference = $prevPref
    }
}

function Show-Usage {
    $usage = @"
Usage: install.ps1 [OPTIONS]

Install or uninstall the Vintelli CLI binary ('vintelli.exe').

Checks GitHub Releases for a pre-built native binary matching the current
OS and architecture first, and falls back to building from a Git clone (or
a local clone passed via --source) when no release artifact is available.

Options:
  --source <dir>    Build from an existing local repository clone at <dir>
  --prefix <dir>    Install binary into <dir> (default: %LOCALAPPDATA%\vintelli, or %ProgramFiles%\vintelli with --system)
  --version <tag>   Install a specific release tag or git ref (default: latest)
  --system          Install system-wide (%ProgramFiles%\vintelli\vintelli.exe, requires Administrator)
  --force           Overwrite an existing installation without prompting
  --uninstall       Remove an existing Vintelli installation
  --dry-run         Print planned actions and health checks without modifying the system
  -h, --help        Show this help message and exit
"@
    Write-Host $usage
}

function Parse-Arguments([string[]]$InputArgs) {
    $i = 0
    while ($i -lt $InputArgs.Count) {
        $arg = $InputArgs[$i]
        switch -Regex ($arg) {
            '^(--source|-Source)$' {
                if (($i + 1) -ge $InputArgs.Count -or [string]::IsNullOrWhiteSpace($InputArgs[$i + 1])) {
                    Write-ErrorMsg "Option '--source' requires a directory argument."
                    Show-Usage
                    exit 2
                }
                $Script:SourceDir = $InputArgs[$i + 1]
                $i += 2
            }
            '^--source=(.+)$' {
                $Script:SourceDir = $Matches[1]
                $i += 1
            }
            '^(--prefix|-Prefix)$' {
                if (($i + 1) -ge $InputArgs.Count -or [string]::IsNullOrWhiteSpace($InputArgs[$i + 1])) {
                    Write-ErrorMsg "Option '--prefix' requires a directory argument."
                    Show-Usage
                    exit 2
                }
                $Script:PrefixDir = $InputArgs[$i + 1]
                $i += 2
            }
            '^--prefix=(.+)$' {
                $Script:PrefixDir = $Matches[1]
                $i += 1
            }
            '^(--version|-Version)$' {
                if (($i + 1) -ge $InputArgs.Count -or [string]::IsNullOrWhiteSpace($InputArgs[$i + 1])) {
                    Write-ErrorMsg "Option '--version' requires a tag argument."
                    Show-Usage
                    exit 2
                }
                $Script:TargetVersion = $InputArgs[$i + 1]
                $i += 2
            }
            '^--version=(.+)$' {
                $Script:TargetVersion = $Matches[1]
                $i += 1
            }
            '^(--system|-System)$' {
                $Script:SystemInstall = $true
                $i += 1
            }
            '^(--force|-Force)$' {
                $Script:ForceInstall = $true
                $i += 1
            }
            '^(--uninstall|-Uninstall)$' {
                $Script:Uninstall = $true
                $i += 1
            }
            '^(--dry-run|-DryRun)$' {
                $Script:DryRun = $true
                $i += 1
            }
            '^(-h|--help|-Help)$' {
                Show-Usage
                exit 0
            }
            default {
                Write-ErrorMsg "Unknown option: $arg"
                Show-Usage
                exit 2
            }
        }
    }
}

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Resolve-InstallPaths {
    if (-not [string]::IsNullOrWhiteSpace($Script:PrefixDir)) {
        $Script:InstallDir = [System.IO.Path]::GetFullPath($Script:PrefixDir)
    } elseif ($Script:SystemInstall) {
        if (-not $Script:DryRun -and -not (Test-Administrator)) {
            throw "System installation (--system) requires an elevated Administrator PowerShell session."
        }
        $programFiles = if ($env:ProgramFiles) { $env:ProgramFiles } else { 'C:\Program Files' }
        $Script:InstallDir = Join-Path $programFiles 'vintelli'
    } else {
        $localAppData = if ($env:LOCALAPPDATA) { $env:LOCALAPPDATA } else { Join-Path $HOME 'AppData\Local' }
        $Script:InstallDir = Join-Path $localAppData 'vintelli'
    }

    $Script:InstallBin = Join-Path $Script:InstallDir 'vintelli.exe'
    $Script:RuntimeDir = if ($env:VINTELLI_RUNTIME_DIR) { $env:VINTELLI_RUNTIME_DIR } else { Join-Path $Script:InstallDir 'runtime' }
}

function Get-DetectedPlatform {
    $archRaw = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
    $arch = switch -Regex ($archRaw) {
        '^(AMD64|x86_64)$' { 'x86_64' }
        '^(ARM64|aarch64)$' { 'aarch64' }
        default { throw "Unsupported CPU architecture: $archRaw. Supported architectures: x86_64, aarch64." }
    }
    Write-Info "Detected platform: windows/$arch"
    return $arch
}

function Test-DiskSpace([string]$TargetPath, [int]$RequiredMb) {
    $fullPath = [System.IO.Path]::GetFullPath($TargetPath)
    $root = [System.IO.Path]::GetPathRoot($fullPath)
    if ([string]::IsNullOrWhiteSpace($root)) {
        Write-WarnMsg "Could not determine drive root for $TargetPath; skipping disk space check."
        return
    }
    $drive = New-Object System.IO.DriveInfo($root)
    $availMb = [math]::Floor($drive.AvailableFreeSpace / 1MB)
    if ($availMb -lt $RequiredMb) {
        throw "Insufficient disk space on drive ${root}: ${availMb} MB available, at least ${RequiredMb} MB required."
    }
    Write-Ok "Disk space check passed for ${root} (${availMb} MB available, ${RequiredMb} MB required)."
}

function Test-NetworkConnectivity {
    if ($env:VINTELLI_SKIP_NETWORK_CHECK -eq 'true') {
        Write-Info "Skipping network connectivity check (VINTELLI_SKIP_NETWORK_CHECK=true)."
        return
    }
    $probeUrl = if ($env:VINTELLI_NETWORK_CHECK_URL) { $env:VINTELLI_NETWORK_CHECK_URL } else { 'https://github.com' }
    try {
        $null = Invoke-WebRequest -Uri $probeUrl -Method Head -TimeoutSec 15 -UseBasicParsing
        Write-Ok "Network connectivity verified ($probeUrl)."
    } catch {
        throw "Network connectivity check failed: unable to reach $probeUrl ($($_.Exception.Message))."
    }
}

function Test-Sha256Checksum([string]$ArtifactFile, [string]$ChecksumFile, [string]$ArtifactName = '') {
    if (-not (Test-Path -LiteralPath $ChecksumFile)) {
        throw "Checksum file missing: $ChecksumFile"
    }
    $targetName = if (-not [string]::IsNullOrWhiteSpace($ArtifactName)) { $ArtifactName } else { Split-Path -Leaf $ArtifactFile }
    $lines = @(Get-Content -LiteralPath $ChecksumFile)
    $expectedHash = ''
    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed)) { continue }
        $parts = $trimmed -split '\s+'
        if ($parts.Count -ge 2) {
            $entryName = $parts[1].TrimStart('*')
            if ($entryName -eq $targetName) {
                $expectedHash = $parts[0].ToLowerInvariant()
                break
            }
        }
    }
    if ([string]::IsNullOrWhiteSpace($expectedHash) -and $lines.Count -gt 0) {
        $expectedHash = (($lines[0].Trim()) -split '\s+')[0].ToLowerInvariant()
    }
    if ($expectedHash -notmatch '^[0-9a-f]{64}$') {
        throw "Malformed SHA-256 checksum in ${ChecksumFile}: '$expectedHash'"
    }
    $actualHash = (Get-FileHash -LiteralPath $ArtifactFile -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedHash) {
        throw "SHA-256 checksum mismatch for ${targetName}: expected $expectedHash, got $actualHash"
    }
    Write-Ok "SHA-256 checksum verified ($actualHash)."
}

function Ensure-TempDir {
    if ([string]::IsNullOrWhiteSpace($Script:TmpWorkDir)) {
        $baseTemp = [System.IO.Path]::GetTempPath()
        $Script:TmpWorkDir = Join-Path $baseTemp ("vintelli-install-" + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $Script:TmpWorkDir -Force | Out-Null
    }
}

function Prepare-ExistingInstallation {
    if (-not (Test-Path -LiteralPath $Script:InstallBin) -and -not (Test-Path -LiteralPath $Script:RuntimeDir)) {
        return
    }

    if (-not $Script:ForceInstall) {
        if ([Environment]::UserInteractive -and $env:VINTELLI_NONINTERACTIVE -ne 'true' -and -not [Console]::IsInputRedirected) {
            $answer = Read-Host "Existing Vintelli installation detected at $($Script:InstallBin). Overwrite? [y/N]"
            if ($answer -notmatch '^(y|yes)$') {
                Write-Info "Installation cancelled by user; existing installation kept intact."
                exit 0
            }
        } else {
            Write-Info "Existing installation detected at $($Script:InstallBin); backing up before updating."
        }
    } else {
        Write-Info "Overwriting existing installation at $($Script:InstallBin) (--force)."
    }

    if ($Script:DryRun) {
        Write-Info "[dry-run] Would back up existing installation at $($Script:InstallBin)"
        return
    }

    if (Test-Path -LiteralPath $Script:InstallBin) {
        $Script:BackupBin = "$($Script:InstallBin).bak"
        if (Test-Path -LiteralPath $Script:BackupBin) {
            Remove-Item -LiteralPath $Script:BackupBin -Force
        }
        Move-Item -LiteralPath $Script:InstallBin -Destination $Script:BackupBin -Force
        $Script:BackupActive = $true
        Write-Info "Backed up existing binary to $($Script:BackupBin)"
    }
    if (Test-Path -LiteralPath $Script:RuntimeDir) {
        $Script:BackupRuntime = "$($Script:RuntimeDir).bak"
        if (Test-Path -LiteralPath $Script:BackupRuntime) {
            Remove-Item -LiteralPath $Script:BackupRuntime -Recurse -Force
        }
        Copy-Item -LiteralPath $Script:RuntimeDir -Destination $Script:BackupRuntime -Recurse -Force
        $Script:BackupActive = $true
    }
}

function Restore-Backup {
    if (-not $Script:BackupActive) {
        return
    }
    Write-WarnMsg "Restoring previous Vintelli installation from backup..."
    try {
        if ($Script:BackupBin -and (Test-Path -LiteralPath $Script:BackupBin)) {
            Move-Item -LiteralPath $Script:BackupBin -Destination $Script:InstallBin -Force
        }
        if ($Script:BackupRuntime -and (Test-Path -LiteralPath $Script:BackupRuntime)) {
            if (Test-Path -LiteralPath $Script:RuntimeDir) {
                Remove-Item -LiteralPath $Script:RuntimeDir -Recurse -Force
            }
            Move-Item -LiteralPath $Script:BackupRuntime -Destination $Script:RuntimeDir -Force
        }
    } catch {
        Write-WarnMsg "Failed to restore backup: $($_.Exception.Message)"
    }
    $Script:BackupActive = $false
}

function Remove-Backup {
    if ($Script:BackupBin -and (Test-Path -LiteralPath $Script:BackupBin)) {
        Remove-Item -LiteralPath $Script:BackupBin -Force
    }
    if ($Script:BackupRuntime -and (Test-Path -LiteralPath $Script:BackupRuntime)) {
        Remove-Item -LiteralPath $Script:BackupRuntime -Recurse -Force
    }
    $Script:BackupActive = $false
}

function Copy-OrDownloadFile([string]$UrlOrPath, [string]$Destination) {
    if ($UrlOrPath -match '^file://(.+)$') {
        $localPath = [Uri]::UnescapeDataString(($UrlOrPath -replace '^file:///?', ''))
        if (-not (Test-Path -LiteralPath $localPath)) {
            throw "Local file artifact not found: $localPath"
        }
        Copy-Item -LiteralPath $localPath -Destination $Destination -Force
    } elseif (Test-Path -LiteralPath $UrlOrPath) {
        Copy-Item -LiteralPath $UrlOrPath -Destination $Destination -Force
    } else {
        Invoke-WebRequest -Uri $UrlOrPath -OutFile $Destination -TimeoutSec 300 -UseBasicParsing
    }
}

function Try-ReleaseInstall([string]$Arch) {
    if (-not [string]::IsNullOrWhiteSpace($Script:SourceDir)) {
        Write-Info "Local source directory specified (--source); skipping release check."
        return $false
    }
    if ($env:VINTELLI_SKIP_RELEASE_CHECK -eq 'true') {
        Write-Info "Skipping release check (VINTELLI_SKIP_RELEASE_CHECK=true)."
        return $false
    }

    $releaseArch = switch ($Arch) {
        'x86_64' { 'amd64' }
        'aarch64' { 'arm64' }
        default { $Arch }
    }
    $assetName = "vintelli-windows-${Arch}.exe"
    $downloadUrl = ''
    $checksumUrl = if ($env:VINTELLI_RELEASE_CHECKSUM_URL) { $env:VINTELLI_RELEASE_CHECKSUM_URL } else { '' }

    if ($env:VINTELLI_RELEASE_ASSET_URL) {
        $downloadUrl = $env:VINTELLI_RELEASE_ASSET_URL
        $assetName = Split-Path -Leaf ($downloadUrl -replace '^file:///?', '')
        if ([string]::IsNullOrWhiteSpace($checksumUrl)) {
            $checksumUrl = "${downloadUrl}.sha256"
        }
    } else {
        $apiUrl = if ($Script:TargetVersion -eq 'latest') {
            "$($Script:DefaultReleasesApi)/latest"
        } else {
            "$($Script:DefaultReleasesApi)/tags/$($Script:TargetVersion)"
        }

        Write-Info "Checking GitHub Releases for pre-built binary (windows/$releaseArch, version: $($Script:TargetVersion))..."
        try {
            $release = Invoke-RestMethod -Uri $apiUrl -Method Get -TimeoutSec 15
        } catch {
            Write-Info "No published release found for '$($Script:TargetVersion)'; falling back to clone mode."
            return $false
        }

        if (-not $release -or -not $release.tag_name) {
            Write-Info "No release tag available in GitHub response; falling back to clone mode."
            return $false
        }

        $plainVersion = $release.tag_name -replace '^v', ''
        $archiveAssetName = "vintelli-${plainVersion}-windows-${releaseArch}.zip"
        $archiveAsset = $null
        $matchingAsset = $null
        $shaSumsAsset = $null
        if ($release.assets) {
            $archiveAsset = $release.assets | Where-Object { $_.name -eq $archiveAssetName } | Select-Object -First 1
            $matchingAsset = $release.assets | Where-Object { $_.name -eq $assetName } | Select-Object -First 1
            $shaSumsAsset = $release.assets | Where-Object { $_.name -eq 'SHA256SUMS' } | Select-Object -First 1
        }

        if ($archiveAsset) {
            $assetName = $archiveAssetName
            $downloadUrl = "$($Script:DefaultReleaseDownloadBase)/$($release.tag_name)/$assetName"
            if ([string]::IsNullOrWhiteSpace($checksumUrl)) {
                if ($shaSumsAsset) {
                    $checksumUrl = "$($Script:DefaultReleaseDownloadBase)/$($release.tag_name)/SHA256SUMS"
                } else {
                    $checksumUrl = "${downloadUrl}.sha256"
                }
            }
        } elseif ($matchingAsset) {
            $downloadUrl = "$($Script:DefaultReleaseDownloadBase)/$($release.tag_name)/$assetName"
            if ([string]::IsNullOrWhiteSpace($checksumUrl)) {
                $checksumUrl = "${downloadUrl}.sha256"
            }
        } else {
            Write-Info "Release '$($release.tag_name)' does not contain asset '$archiveAssetName' or '$assetName'; falling back to clone mode."
            return $false
        }
    }

    $minDisk = if ($env:VINTELLI_MIN_DISK_MB) { [int]$env:VINTELLI_MIN_DISK_MB } else { 50 }
    Test-DiskSpace -TargetPath $Script:InstallDir -RequiredMb $minDisk

    if ($Script:DryRun) {
        Write-Info "[dry-run] Would download release binary from $downloadUrl"
        Write-Info "[dry-run] Would download and verify SHA-256 checksum from $checksumUrl"
        Write-Info "[dry-run] Would install binary to $($Script:InstallBin)"
        $Script:InstallModeSummary = 'release (dry-run)'
        return $true
    }

    Ensure-TempDir
    $downloadedAsset = Join-Path $Script:TmpWorkDir $assetName
    $downloadedSha = Join-Path $Script:TmpWorkDir "${assetName}.sha256"

    Write-Info "Downloading release binary from $downloadUrl..."
    Copy-OrDownloadFile -UrlOrPath $downloadUrl -Destination $downloadedAsset

    Write-Info "Downloading SHA-256 checksum from $checksumUrl..."
    Copy-OrDownloadFile -UrlOrPath $checksumUrl -Destination $downloadedSha

    Test-Sha256Checksum -ArtifactFile $downloadedAsset -ChecksumFile $downloadedSha -ArtifactName $assetName

    $binaryToInstall = $downloadedAsset
    if ($assetName.EndsWith('.zip', [StringComparison]::OrdinalIgnoreCase)) {
        $extractDir = Join-Path $Script:TmpWorkDir 'release-extract'
        Expand-Archive -LiteralPath $downloadedAsset -DestinationPath $extractDir -Force
        $extractedExe = Get-ChildItem -LiteralPath $extractDir -Filter 'vintelli.exe' -Recurse -File | Select-Object -First 1
        if (-not $extractedExe) {
            throw "Release archive $assetName did not contain 'vintelli.exe'."
        }
        $binaryToInstall = $extractedExe.FullName
    }

    Prepare-ExistingInstallation
    New-Item -ItemType Directory -Path $Script:InstallDir -Force | Out-Null
    Copy-Item -LiteralPath $binaryToInstall -Destination $Script:InstallBin -Force

    $Script:InstallModeSummary = 'release'
    return $true
}

function Find-JavaBinary {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }
    return ''
}

function Test-ClonePrerequisites([bool]$NeedGit) {
    if ($NeedGit) {
        $gitCmd = Get-Command git -ErrorAction SilentlyContinue
        if (-not $gitCmd) {
            throw "Prerequisite missing: 'git' is required to clone the repository."
        }
        $gitRes = Invoke-ExternalCommand -Executable $gitCmd.Source -Arguments @('--version')
        $gitVer = ($gitRes.Lines | Select-Object -First 1)
        Write-Ok "Prerequisite found: git ($gitVer)"
    }

    $javaBin = Find-JavaBinary
    if ([string]::IsNullOrWhiteSpace($javaBin)) {
        throw "Prerequisite missing: 'java' (JDK $($Script:RequiredJavaMajor)+) is not installed or not on PATH."
    }

    $javaRes = Invoke-ExternalCommand -Executable $javaBin -Arguments @('-version')
    $firstLine = ($javaRes.Lines | Select-Object -First 1)
    if (-not $firstLine -or $firstLine -notmatch 'version "(\d+)') {
        throw "Unable to parse Java version from: $firstLine. JDK $($Script:RequiredJavaMajor)+ is required."
    }
    $majorVersion = [int]$Matches[1]
    if ($majorVersion -lt $Script:RequiredJavaMajor) {
        throw "Java $($Script:RequiredJavaMajor)+ is required to build Vintelli (found Java ${majorVersion}: $firstLine)."
    }
    $Script:JavaBin = $javaBin
    Write-Ok "Prerequisite found: Java $majorVersion ($firstLine)"
}

function Test-NativeToolchain {
    $buildMode = if ($env:VINTELLI_BUILD_MODE) { $env:VINTELLI_BUILD_MODE } else { 'auto' }
    switch ($buildMode) {
        'jvm' {
            Write-Info "Build mode forced to JVM runner (VINTELLI_BUILD_MODE=jvm)."
            return $false
        }
        'native' {
            $nativeCmd = Find-NativeImage
            if ($nativeCmd) {
                Write-Ok "Native toolchain detected at $nativeCmd"
                return $true
            }
            throw "VINTELLI_BUILD_MODE=native was requested, but 'native-image' (GraalVM/Mandrel) was not found."
        }
        'auto' {
            $nativeCmd = Find-NativeImage
            if ($nativeCmd) {
                Write-Ok "Native toolchain detected at $nativeCmd"
                return $true
            }
            Write-WarnMsg "GraalVM/Mandrel 'native-image' toolchain not found; falling back to JVM runner mode."
            return $false
        }
        default {
            throw "Invalid VINTELLI_BUILD_MODE '$buildMode'. Allowed values: auto, native, jvm."
        }
    }
}

function Find-NativeImage {
    foreach ($homeVar in @($env:GRAALVM_HOME, $env:JAVA_HOME)) {
        if ($homeVar) {
            foreach ($name in @('native-image.cmd', 'native-image.exe')) {
                $candidate = Join-Path $homeVar "bin\$name"
                if (Test-Path -LiteralPath $candidate) {
                    return $candidate
                }
            }
        }
    }
    $cmd = Get-Command native-image -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }
    return ''
}

function Resolve-MavenCommand([string]$BuildRoot) {
    $wrapperCmd = Join-Path $BuildRoot 'mvnw.cmd'
    if (Test-Path -LiteralPath $wrapperCmd) {
        Write-Ok "Maven build launcher: $wrapperCmd"
        return $wrapperCmd
    }
    $mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mvnCmd) {
        Write-Ok "Maven build launcher: $($mvnCmd.Source)"
        return $mvnCmd.Source
    }
    throw "Prerequisite missing: neither '$wrapperCmd' nor 'mvn' on PATH is available."
}

function New-WindowsJvmLauncherExe([string]$OutputExePath, [string]$JarPath) {
    $csharpSource = @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;

public static class VintelliLauncherShim {
    public static int Main(string[] args) {
        string baseDir = AppDomain.CurrentDomain.BaseDirectory;
        string runtimeCfg = Path.Combine(baseDir, "vintelli.runtime");
        string jarPath = Path.Combine(baseDir, "runtime", "quarkus-app", "quarkus-run.jar");
        if (File.Exists(runtimeCfg)) {
            string configured = File.ReadAllText(runtimeCfg).Trim();
            if (!string.IsNullOrEmpty(configured)) {
                jarPath = configured;
            }
        }
        if (!File.Exists(jarPath)) {
            Console.Error.WriteLine("vintelli error: runtime jar not found at " + jarPath);
            return 1;
        }
        string javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
        string javaExe = "java";
        if (!string.IsNullOrEmpty(javaHome)) {
            string candidate = Path.Combine(javaHome, "bin", "java.exe");
            if (File.Exists(candidate)) {
                javaExe = candidate;
            }
        }
        string logLevel = Environment.GetEnvironmentVariable("VINTELLI_LOG_LEVEL");
        var allArgs = new List<string>();
        if (!string.IsNullOrEmpty(logLevel)) {
            allArgs.Add("-Dquarkus.log.level=" + logLevel);
        }
        allArgs.Add("-jar");
        allArgs.Add(jarPath);
        allArgs.AddRange(args);

        var psi = new ProcessStartInfo();
        psi.FileName = javaExe;
        psi.Arguments = JoinArguments(allArgs);
        psi.UseShellExecute = false;

        try {
            using (Process proc = Process.Start(psi)) {
                proc.WaitForExit();
                return proc.ExitCode;
            }
        } catch (Exception ex) {
            Console.Error.WriteLine("vintelli error: failed to launch java (" + ex.Message + ")");
            return 1;
        }
    }

    private static string JoinArguments(IEnumerable<string> args) {
        var sb = new StringBuilder();
        foreach (string arg in args) {
            if (sb.Length > 0) sb.Append(' ');
            sb.Append(QuoteArgument(arg));
        }
        return sb.ToString();
    }

    private static string QuoteArgument(string arg) {
        if (arg.Length > 0 && arg.IndexOfAny(new char[] { ' ', '\t', '\n', '\v', '\"' }) == -1) {
            return arg;
        }
        var sb = new StringBuilder();
        sb.Append('\"');
        for (int i = 0; i < arg.Length; i++) {
            int backslashes = 0;
            while (i < arg.Length && arg[i] == '\\') {
                i++;
                backslashes++;
            }
            if (i == arg.Length) {
                sb.Append('\\', backslashes * 2);
                break;
            } else if (arg[i] == '\"') {
                sb.Append('\\', backslashes * 2 + 1);
                sb.Append('\"');
            } else {
                sb.Append('\\', backslashes);
                sb.Append(arg[i]);
            }
        }
        sb.Append('\"');
        return sb.ToString();
    }
}
'@

    $outDir = Split-Path -Parent $OutputExePath
    $defaultJar = Join-Path $outDir 'runtime\quarkus-app\quarkus-run.jar'
    $runtimeCfgFile = Join-Path $outDir 'vintelli.runtime'
    if ($JarPath -ne $defaultJar) {
        [System.IO.File]::WriteAllText($runtimeCfgFile, $JarPath, [System.Text.UTF8Encoding]::new($false))
    } elseif (Test-Path -LiteralPath $runtimeCfgFile) {
        Remove-Item -LiteralPath $runtimeCfgFile -Force
    }

    if (Test-Path -LiteralPath $OutputExePath) {
        Remove-Item -LiteralPath $OutputExePath -Force
    }

    Ensure-TempDir
    $csFile = Join-Path $Script:TmpWorkDir 'VintelliLauncherShim.cs'
    [System.IO.File]::WriteAllText($csFile, $csharpSource, [System.Text.UTF8Encoding]::new($false))

    $cscCandidates = @(
        (Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'),
        (Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe')
    )
    $cscExe = $cscCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1

    if ($cscExe) {
        $cscRes = Invoke-ExternalCommand -Executable $cscExe -Arguments @('/nologo', '/target:exe', "/out:$OutputExePath", $csFile)
        if ($cscRes.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $OutputExePath)) {
            throw "Failed to compile Windows launcher executable: $($cscRes.Output)"
        }
    } else {
        Add-Type -TypeDefinition $csharpSource -OutputAssembly $OutputExePath -OutputType ConsoleApplication
    }
}

function Install-FromClone {
    $needGit = [string]::IsNullOrWhiteSpace($Script:SourceDir)
    Test-ClonePrerequisites -NeedGit $needGit

    $minDisk = if ($env:VINTELLI_MIN_DISK_MB) { [int]$env:VINTELLI_MIN_DISK_MB } else { 500 }
    Test-DiskSpace -TargetPath $Script:InstallDir -RequiredMb $minDisk

    $buildDir = ''
    if (-not [string]::IsNullOrWhiteSpace($Script:SourceDir)) {
        if (-not (Test-Path -LiteralPath $Script:SourceDir -PathType Container)) {
            throw "Source directory does not exist: $($Script:SourceDir)"
        }
        $buildDir = [System.IO.Path]::GetFullPath($Script:SourceDir)
        if (-not (Test-Path -LiteralPath (Join-Path $buildDir 'pom.xml'))) {
            throw "Invalid source directory '${buildDir}': missing pom.xml."
        }
        if (-not (Test-Path -LiteralPath (Join-Path $buildDir 'vintelli-app'))) {
            throw "Invalid source directory '${buildDir}': missing vintelli-app module."
        }
        Write-Info "Using existing source tree at $buildDir"
    } else {
        Test-NetworkConnectivity
        if ($Script:DryRun) {
            Write-Info "[dry-run] Would clone $($Script:DefaultRepoUrl) (version: $($Script:TargetVersion)) into a temporary directory"
        } else {
            Ensure-TempDir
            Test-DiskSpace -TargetPath $Script:TmpWorkDir -RequiredMb $minDisk
            $buildDir = Join-Path $Script:TmpWorkDir 'vintelli-src'
            Write-Info "Cloning $($Script:DefaultRepoUrl) into $buildDir..."
            $cloneArgs = if ($Script:TargetVersion -ne 'latest') {
                @('clone', '--depth', '1', '--branch', $Script:TargetVersion, $Script:DefaultRepoUrl, $buildDir)
            } else {
                @('clone', '--depth', '1', $Script:DefaultRepoUrl, $buildDir)
            }
            $cloneRes = Invoke-ExternalCommand -Executable 'git' -Arguments $cloneArgs
            if ($cloneRes.ExitCode -ne 0) {
                throw "Failed to clone $($Script:DefaultRepoUrl): $($cloneRes.Output)"
            }
        }
    }

    $useNative = Test-NativeToolchain
    $mavenCmd = ''
    if ($buildDir) {
        $mavenCmd = Resolve-MavenCommand -BuildRoot $buildDir
    }

    if ($Script:DryRun) {
        if ($useNative) {
            Write-Info "[dry-run] Would build native binary with: .\mvnw.cmd -B -ntp package -Dnative -DskipTests"
            Write-Info "[dry-run] Would copy vintelli-app\target\*-runner.exe to $($Script:InstallBin)"
            $Script:InstallModeSummary = 'clone-native (dry-run)'
        } else {
            Write-Info "[dry-run] Would build JVM application with: .\mvnw.cmd -B -ntp package -DskipTests"
            Write-Info "[dry-run] Would copy vintelli-app\target\quarkus-app to $($Script:RuntimeDir)\quarkus-app"
            Write-Info "[dry-run] Would install launcher executable to $($Script:InstallBin)"
            $Script:InstallModeSummary = 'clone-jvm (dry-run)'
        }
        return
    }

    Prepare-ExistingInstallation

    if ($useNative) {
        $targetDir = Join-Path $buildDir 'vintelli-app\target'
        $existingRunner = $null
        if ($env:VINTELLI_REUSE_EXISTING_BUILD -eq 'true' -and (Test-Path -LiteralPath $targetDir)) {
            $existingRunner = Get-ChildItem -LiteralPath $targetDir -Filter '*-runner.exe' -File -ErrorAction SilentlyContinue | Select-Object -First 1
        }
        if ($existingRunner) {
            Write-Info "Reusing existing native binary: $($existingRunner.FullName)"
        } else {
            Write-Info "Building Vintelli native binary (this may take a few minutes)..."
            Push-Location $buildDir
            try {
                $mvnRes = Invoke-ExternalCommand -Executable $mavenCmd -Arguments @('-B', '-ntp', 'package', '-Dnative', '-DskipTests')
                if ($mvnRes.ExitCode -ne 0) {
                    throw "Maven native build failed with exit code $($mvnRes.ExitCode): $($mvnRes.Output)"
                }
            } finally {
                Pop-Location
            }
            $existingRunner = Get-ChildItem -LiteralPath $targetDir -Filter '*-runner.exe' -File -ErrorAction SilentlyContinue | Select-Object -First 1
        }
        if (-not $existingRunner) {
            throw "Native build finished, but no '*-runner.exe' binary was found under $targetDir."
        }

        New-Item -ItemType Directory -Path $Script:InstallDir -Force | Out-Null
        if (Test-Path -LiteralPath $Script:RuntimeDir) {
            Remove-Item -LiteralPath $Script:RuntimeDir -Recurse -Force
        }
        Copy-Item -LiteralPath $existingRunner.FullName -Destination $Script:InstallBin -Force
        $Script:InstallModeSummary = 'clone-native'
    } else {
        $quarkusAppDir = Join-Path $buildDir 'vintelli-app\target\quarkus-app'
        $quarkusRunJar = Join-Path $quarkusAppDir 'quarkus-run.jar'
        if ($env:VINTELLI_REUSE_EXISTING_BUILD -eq 'true' -and (Test-Path -LiteralPath $quarkusRunJar)) {
            Write-Info "Reusing existing JVM package at $quarkusAppDir"
        } else {
            Write-Info "Building Vintelli JVM package..."
            Push-Location $buildDir
            try {
                $mvnRes = Invoke-ExternalCommand -Executable $mavenCmd -Arguments @('-B', '-ntp', 'package', '-DskipTests')
                if ($mvnRes.ExitCode -ne 0) {
                    throw "Maven JVM build failed with exit code $($mvnRes.ExitCode): $($mvnRes.Output)"
                }
            } finally {
                Pop-Location
            }
        }
        if (-not (Test-Path -LiteralPath $quarkusRunJar)) {
            throw "JVM build finished, but '$quarkusRunJar' was not found."
        }

        New-Item -ItemType Directory -Path $Script:InstallDir -Force | Out-Null
        $installedQuarkusApp = Join-Path $Script:RuntimeDir 'quarkus-app'
        if (Test-Path -LiteralPath $installedQuarkusApp) {
            Remove-Item -LiteralPath $installedQuarkusApp -Recurse -Force
        }
        New-Item -ItemType Directory -Path $Script:RuntimeDir -Force | Out-Null
        Copy-Item -LiteralPath $quarkusAppDir -Destination $installedQuarkusApp -Recurse -Force

        $installedJar = Join-Path $installedQuarkusApp 'quarkus-run.jar'
        New-WindowsJvmLauncherExe -OutputExePath $Script:InstallBin -JarPath $installedJar
        $Script:InstallModeSummary = 'clone-jvm'
    }
}

function Set-PathConfiguration {
    $pathEntries = ($env:PATH -split ';') | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_.TrimEnd('\') }
    $normalizedInstallDir = $Script:InstallDir.TrimEnd('\')

    if ($pathEntries -contains $normalizedInstallDir) {
        Write-Ok "Install directory $($Script:InstallDir) is already on PATH."
        return
    }

    if ($Script:DryRun) {
        $scopeName = if ($Script:SystemInstall) { 'Machine' } else { 'User' }
        Write-Info "[dry-run] Would add '$($Script:InstallDir)' to $scopeName PATH"
        return
    }

    if ($env:VINTELLI_SKIP_PATH_UPDATE -eq 'true') {
        Write-Info "Skipping persistent Windows PATH update (VINTELLI_SKIP_PATH_UPDATE=true)."
        $env:PATH = "$($Script:InstallDir);$env:PATH"
        return
    }

    $targetScope = if ($Script:SystemInstall) { [EnvironmentVariableTarget]::Machine } else { [EnvironmentVariableTarget]::User }
    $currentScopePath = [Environment]::GetEnvironmentVariable('Path', $targetScope)
    $scopeEntries = if ($currentScopePath) {
        ($currentScopePath -split ';') | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_.TrimEnd('\') }
    } else {
        @()
    }

    if ($scopeEntries -notcontains $normalizedInstallDir) {
        $newScopePath = if ([string]::IsNullOrWhiteSpace($currentScopePath)) {
            $Script:InstallDir
        } else {
            "$($currentScopePath.TrimEnd(';'));$($Script:InstallDir)"
        }
        [Environment]::SetEnvironmentVariable('Path', $newScopePath, $targetScope)
        Write-Ok "Added $($Script:InstallDir) to Windows $targetScope PATH."
    } else {
        Write-Info "Install directory $($Script:InstallDir) is already present in Windows $targetScope PATH."
    }

    $env:PATH = "$($Script:InstallDir);$env:PATH"
    Write-Info "Open a new terminal window for the updated PATH to take effect everywhere."
}

function Invoke-SmokeTest {
    if ($Script:DryRun) {
        Write-Info "[dry-run] Would run post-install smoke test: $($Script:InstallBin) --version && $($Script:InstallBin) --help"
        $Script:InstalledVersionSummary = "$($Script:TargetVersion) (dry-run)"
        return
    }

    if (-not (Test-Path -LiteralPath $Script:InstallBin)) {
        throw "Smoke test failed: $($Script:InstallBin) does not exist."
    }

    Write-Info "Running post-install smoke test..."
    $neutralDir = [System.IO.Path]::GetTempPath()
    Push-Location $neutralDir
    try {
        $verRes = Invoke-ExternalCommand -Executable $Script:InstallBin -Arguments @('--version')
        if ($verRes.ExitCode -ne 0) {
            throw "Smoke test failed: '$($Script:InstallBin) --version' exited with code $($verRes.ExitCode): $($verRes.Output)"
        }
        $helpRes = Invoke-ExternalCommand -Executable $Script:InstallBin -Arguments @('--help')
        if ($helpRes.ExitCode -ne 0) {
            throw "Smoke test failed: '$($Script:InstallBin) --help' exited with code $($helpRes.ExitCode): $($helpRes.Output)"
        }
        $versionLine = ($verRes.Lines | Where-Object { $_ -match '^vintelli ' } | Select-Object -Last 1)
        if (-not $versionLine) {
            $versionLine = ($verRes.Lines | Select-Object -Last 1)
        }
        $Script:InstalledVersionSummary = if ($versionLine) { $versionLine.Trim() } else { 'vintelli' }
        Write-Ok "Smoke test passed ($($Script:InstalledVersionSummary))."
    } finally {
        Pop-Location
    }
}

function Invoke-Uninstall {
    Write-Info "Uninstalling Vintelli from $($Script:InstallBin)..."
    if ($Script:DryRun) {
        Write-Info "[dry-run] Would remove $($Script:InstallBin) and $($Script:RuntimeDir)"
        return
    }

    $removedAny = $false
    if (Test-Path -LiteralPath $Script:InstallBin) {
        Remove-Item -LiteralPath $Script:InstallBin -Force
        $removedAny = $true
        Write-Ok "Removed $($Script:InstallBin)"
    }
    $bakPath = "$($Script:InstallBin).bak"
    if (Test-Path -LiteralPath $bakPath) {
        Remove-Item -LiteralPath $bakPath -Force
    }
    if (Test-Path -LiteralPath $Script:RuntimeDir) {
        Remove-Item -LiteralPath $Script:RuntimeDir -Recurse -Force
        $removedAny = $true
        Write-Ok "Removed runtime directory $($Script:RuntimeDir)"
    }

    if (-not $removedAny) {
        Write-Info "No existing Vintelli installation found at $($Script:InstallBin) (nothing to remove)."
    } else {
        Write-Ok "Vintelli uninstalled cleanly."
    }
}

try {
    Parse-Arguments -InputArgs $args
    Resolve-InstallPaths

    if ($Script:Uninstall) {
        Invoke-Uninstall
        exit 0
    }

    $arch = Get-DetectedPlatform
    if (-not (Try-ReleaseInstall -Arch $arch)) {
        Install-FromClone
    }

    Invoke-SmokeTest
    Remove-Backup
    Set-PathConfiguration

    Write-Host ""
    Write-Ok "Vintelli installation complete!"
    Write-Host "  Mode:    $($Script:InstallModeSummary)"
    Write-Host "  Version: $($Script:InstalledVersionSummary)"
    Write-Host "  Binary:  $($Script:InstallBin)"
} catch {
    Write-ErrorMsg $_.Exception.Message
    Restore-Backup
    exit 1
} finally {
    if ($Script:TmpWorkDir -and (Test-Path -LiteralPath $Script:TmpWorkDir)) {
        Remove-Item -LiteralPath $Script:TmpWorkDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
