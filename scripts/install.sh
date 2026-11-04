#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_NAME="$(basename "$0")"
readonly DEFAULT_REPO_URL="${VINTELLI_REPO_URL:-https://github.com/omatheusmesmo/vintelli.git}"
readonly DEFAULT_RELEASES_API="${VINTELLI_RELEASES_API:-https://api.github.com/repos/omatheusmesmo/vintelli/releases}"
readonly DEFAULT_RELEASE_DOWNLOAD_BASE="${VINTELLI_RELEASE_DOWNLOAD_BASE:-https://github.com/omatheusmesmo/vintelli/releases/download}"
readonly REQUIRED_JAVA_MAJOR="${VINTELLI_REQUIRED_JAVA_MAJOR:-25}"

SOURCE_DIR=""
PREFIX_DIR=""
TARGET_VERSION="latest"
SYSTEM_INSTALL=false
FORCE_INSTALL=false
UNINSTALL=false
DRY_RUN=false

TMP_WORK_DIR=""
BACKUP_BIN=""
BACKUP_RUNTIME=""
BACKUP_ACTIVE=false
INSTALL_BIN=""
RUNTIME_DIR=""

log_info() {
    printf '[INFO] %s\n' "$*"
}

log_ok() {
    printf '[OK] %s\n' "$*"
}

log_warn() {
    printf '[WARN] %s\n' "$*" >&2
}

log_error() {
    printf '[ERROR] %s\n' "$*" >&2
}

die() {
    log_error "$*"
    exit 1
}

die_usage() {
    log_error "$*"
    print_usage >&2
    exit 2
}

on_error() {
    local line_no="$1"
    local failed_cmd="$2"
    log_error "Installation failed at line ${line_no} while running: ${failed_cmd}"
    if [ "$BACKUP_ACTIVE" = true ]; then
        restore_backup
    fi
}

cleanup() {
    if [ -n "$TMP_WORK_DIR" ] && [ -d "$TMP_WORK_DIR" ]; then
        rm -rf "$TMP_WORK_DIR"
    fi
}

trap 'on_error $LINENO "$BASH_COMMAND"' ERR
trap cleanup EXIT

print_usage() {
    cat <<EOF
Usage: ${SCRIPT_NAME} [OPTIONS]

Install or uninstall the Vintelli CLI binary ('vintelli').

Checks GitHub Releases for a pre-built native binary matching the current
OS and architecture first, and falls back to building from a Git clone (or
a local clone passed via --source) when no release artifact is available.

Options:
  --source <dir>    Build from an existing local repository clone at <dir>
  --prefix <dir>    Install binary into <dir> (default: ~/.local/bin, or /usr/local/bin with --system)
  --version <tag>   Install a specific release tag or git ref (default: latest)
  --system          Install system-wide (/usr/local/bin/vintelli, uses sudo if needed)
  --force           Overwrite an existing installation without prompting
  --uninstall       Remove an existing Vintelli installation
  --dry-run         Print planned actions and health checks without modifying the system
  -h, --help        Show this help message and exit
EOF
}

parse_args() {
    while [ $# -gt 0 ]; do
        case "$1" in
            --source)
                [ $# -ge 2 ] && [ -n "${2:-}" ] || die_usage "Option '--source' requires a directory argument."
                SOURCE_DIR="$2"
                shift 2
                ;;
            --source=*)
                SOURCE_DIR="${1#*=}"
                [ -n "$SOURCE_DIR" ] || die_usage "Option '--source' requires a non-empty directory argument."
                shift
                ;;
            --prefix)
                [ $# -ge 2 ] && [ -n "${2:-}" ] || die_usage "Option '--prefix' requires a directory argument."
                PREFIX_DIR="$2"
                shift 2
                ;;
            --prefix=*)
                PREFIX_DIR="${1#*=}"
                [ -n "$PREFIX_DIR" ] || die_usage "Option '--prefix' requires a non-empty directory argument."
                shift
                ;;
            --version)
                [ $# -ge 2 ] && [ -n "${2:-}" ] || die_usage "Option '--version' requires a tag argument."
                TARGET_VERSION="$2"
                shift 2
                ;;
            --version=*)
                TARGET_VERSION="${1#*=}"
                [ -n "$TARGET_VERSION" ] || die_usage "Option '--version' requires a non-empty tag argument."
                shift
                ;;
            --system)
                SYSTEM_INSTALL=true
                shift
                ;;
            --force)
                FORCE_INSTALL=true
                shift
                ;;
            --uninstall)
                UNINSTALL=true
                shift
                ;;
            --dry-run)
                DRY_RUN=true
                shift
                ;;
            -h|--help)
                print_usage
                exit 0
                ;;
            *)
                die_usage "Unknown option: $1"
                ;;
        esac
    done
}

resolve_install_paths() {
    if [ -n "$PREFIX_DIR" ]; then
        INSTALL_DIR="$PREFIX_DIR"
        RUNTIME_DIR="${VINTELLI_RUNTIME_DIR:-${INSTALL_DIR}/.vintelli-runtime}"
    elif [ "$SYSTEM_INSTALL" = true ]; then
        INSTALL_DIR="/usr/local/bin"
        RUNTIME_DIR="${VINTELLI_RUNTIME_DIR:-/usr/local/share/vintelli/runtime}"
    else
        INSTALL_DIR="${HOME}/.local/bin"
        local data_home="${XDG_DATA_HOME:-${HOME}/.local/share}"
        RUNTIME_DIR="${VINTELLI_RUNTIME_DIR:-${data_home}/vintelli/runtime}"
    fi
    INSTALL_BIN="${INSTALL_DIR}/vintelli"
}

run_privileged() {
    if [ "$DRY_RUN" = true ]; then
        log_info "[dry-run] $*"
        return 0
    fi
    if [ "$SYSTEM_INSTALL" = true ] && [ "$(id -u)" -ne 0 ]; then
        if ! command -v sudo >/dev/null 2>&1; then
            die "System installation (--system) requires root privileges, and 'sudo' is not available."
        fi
        sudo "$@"
    else
        "$@"
    fi
}

detect_platform() {
    local uname_s
    local uname_m
    uname_s="$(uname -s 2>/dev/null || echo unknown)"
    uname_m="$(uname -m 2>/dev/null || echo unknown)"

    case "$uname_s" in
        Linux|linux)
            DETECTED_OS="linux"
            ;;
        Darwin|darwin)
            DETECTED_OS="darwin"
            ;;
        MINGW*|MSYS*|CYGWIN*)
            DETECTED_OS="windows"
            ;;
        *)
            die "Unsupported operating system: ${uname_s}. Supported platforms: Linux, macOS, Windows."
            ;;
    esac

    case "$uname_m" in
        x86_64|amd64)
            DETECTED_ARCH="x86_64"
            ;;
        aarch64|arm64)
            DETECTED_ARCH="aarch64"
            ;;
        *)
            die "Unsupported CPU architecture: ${uname_m}. Supported architectures: x86_64, aarch64."
            ;;
    esac

    log_info "Detected platform: ${DETECTED_OS}/${DETECTED_ARCH}"
}

nearest_existing_dir() {
    local target="$1"
    while [ ! -d "$target" ]; do
        local parent
        parent="$(dirname "$target")"
        if [ "$parent" = "$target" ]; then
            echo "/"
            return
        fi
        target="$parent"
    done
    echo "$target"
}

check_disk_space() {
    local target_dir="$1"
    local required_mb="$2"
    local existing_dir
    existing_dir="$(nearest_existing_dir "$target_dir")"

    if ! command -v df >/dev/null 2>&1; then
        log_warn "'df' command not found; skipping disk space check."
        return 0
    fi

    local avail_kb
    avail_kb="$(df -Pk "$existing_dir" 2>/dev/null | awk 'NR==2 {print $4}')"
    if [ -z "$avail_kb" ] || ! [[ "$avail_kb" =~ ^[0-9]+$ ]]; then
        log_warn "Could not determine free disk space for ${existing_dir}; continuing."
        return 0
    fi

    local avail_mb=$((avail_kb / 1024))
    if [ "$avail_mb" -lt "$required_mb" ]; then
        die "Insufficient disk space in ${existing_dir}: ${avail_mb} MB available, at least ${required_mb} MB required."
    fi
    log_ok "Disk space check passed for ${existing_dir} (${avail_mb} MB available, ${required_mb} MB required)."
}

detect_http_client() {
    if command -v curl >/dev/null 2>&1; then
        HTTP_CLIENT="curl"
    elif command -v wget >/dev/null 2>&1; then
        HTTP_CLIENT="wget"
    else
        HTTP_CLIENT=""
    fi
}

http_get_to_stdout() {
    local url="$1"
    if [ "$HTTP_CLIENT" = "curl" ]; then
        curl -fsSL --connect-timeout 10 --max-time 60 "$url"
    elif [ "$HTTP_CLIENT" = "wget" ]; then
        wget -qO- --timeout=15 "$url"
    else
        die "Neither 'curl' nor 'wget' is installed. Install one of them to download remote artifacts."
    fi
}

http_download_file() {
    local url="$1"
    local dest="$2"
    case "$url" in
        file://*)
            local local_path="${url#file://}"
            [ -f "$local_path" ] || die "Local release artifact not found: ${local_path}"
            cp "$local_path" "$dest"
            return 0
            ;;
    esac
    if [ "$HTTP_CLIENT" = "curl" ]; then
        curl -fsSL --connect-timeout 10 --max-time 300 -o "$dest" "$url"
    elif [ "$HTTP_CLIENT" = "wget" ]; then
        wget -q --timeout=30 -O "$dest" "$url"
    else
        die "Neither 'curl' nor 'wget' is installed. Install one of them to download remote artifacts."
    fi
}

check_network_connectivity() {
    if [ "${VINTELLI_SKIP_NETWORK_CHECK:-false}" = "true" ]; then
        log_info "Skipping network connectivity check (VINTELLI_SKIP_NETWORK_CHECK=true)."
        return 0
    fi
    detect_http_client
    [ -n "$HTTP_CLIENT" ] || die "Network check requires 'curl' or 'wget', neither was found in PATH."

    local probe_url="${VINTELLI_NETWORK_CHECK_URL:-https://github.com}"
    if [ "$HTTP_CLIENT" = "curl" ]; then
        if ! curl -fsSLI --connect-timeout 10 --max-time 20 "$probe_url" >/dev/null 2>&1; then
            die "Network connectivity check failed: unable to reach ${probe_url}."
        fi
    else
        if ! wget -q --spider --timeout=15 "$probe_url" >/dev/null 2>&1; then
            die "Network connectivity check failed: unable to reach ${probe_url}."
        fi
    fi
    log_ok "Network connectivity verified (${probe_url})."
}

compute_sha256() {
    local file="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$file" | awk '{print tolower($1)}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$file" | awk '{print tolower($1)}'
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 "$file" | awk '{print tolower($NF)}'
    else
        die "Cannot verify SHA-256 checksum: install 'sha256sum', 'shasum', or 'openssl'."
    fi
}

verify_checksum() {
    local artifact_file="$1"
    local checksum_file="$2"
    local artifact_name="${3:-$(basename "$artifact_file")}"
    [ -f "$checksum_file" ] || die "Checksum file missing: ${checksum_file}"

    local expected_hash=""
    expected_hash="$(awk -v name="$artifact_name" '$2 == name || $2 == ("*" name) {print tolower($1); exit}' "$checksum_file" | tr -d '\r\n')"
    if [ -z "$expected_hash" ]; then
        expected_hash="$(awk 'NR==1 {print tolower($1)}' "$checksum_file" | tr -d '\r\n')"
    fi
    if [ -z "$expected_hash" ] || ! [[ "$expected_hash" =~ ^[0-9a-f]{64}$ ]]; then
        die "Malformed SHA-256 checksum in ${checksum_file}: '${expected_hash}'"
    fi

    local actual_hash
    actual_hash="$(compute_sha256 "$artifact_file")"
    if [ "$actual_hash" != "$expected_hash" ]; then
        die "SHA-256 checksum mismatch for ${artifact_name}: expected ${expected_hash}, got ${actual_hash}"
    fi
    log_ok "SHA-256 checksum verified (${actual_hash})."
}

find_java_binary() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
        echo "${JAVA_HOME}/bin/java"
    elif command -v java >/dev/null 2>&1; then
        command -v java
    else
        echo ""
    fi
}

check_clone_prerequisites() {
    local need_git="$1"
    if [ "$need_git" = true ]; then
        command -v git >/dev/null 2>&1 || die "Prerequisite missing: 'git' is required to clone the repository."
        log_ok "Prerequisite found: git ($(git --version | head -n 1))"
    fi

    local java_bin
    java_bin="$(find_java_binary)"
    [ -n "$java_bin" ] || die "Prerequisite missing: 'java' (JDK ${REQUIRED_JAVA_MAJOR}+) is not installed or not on PATH."

    local version_output
    version_output="$("$java_bin" -version 2>&1 | head -n 1)"
    local major_version
    major_version="$(printf '%s' "$version_output" | sed -E 's/.*version "([0-9]+).*/\1/')"
    if [ -z "$major_version" ] || ! [[ "$major_version" =~ ^[0-9]+$ ]]; then
        die "Unable to parse Java version from: ${version_output}. JDK ${REQUIRED_JAVA_MAJOR}+ is required."
    fi
    if [ "$major_version" -lt "$REQUIRED_JAVA_MAJOR" ]; then
        die "Java ${REQUIRED_JAVA_MAJOR}+ is required to build Vintelli (found Java ${major_version}: ${version_output}). Use SDKMAN ('sdk env') or install JDK ${REQUIRED_JAVA_MAJOR}."
    fi
    JAVA_BIN="$java_bin"
    log_ok "Prerequisite found: Java ${major_version} (${version_output})"

    if [ "$need_git" = true ] && ! command -v unzip >/dev/null 2>&1 && ! command -v mvn >/dev/null 2>&1; then
        die "Prerequisite missing: 'unzip' is required by Maven Wrapper (mvnw) to extract the Maven '.zip' distribution and verify its SHA-256 checksum (without 'unzip', mvnw falls back to '.tar.gz' and fails SHA-256 validation against distributionSha256Sum). Install 'unzip' or 'mvn' and try again."
    fi
}

detect_native_toolchain() {
    local build_mode="${VINTELLI_BUILD_MODE:-auto}"
    case "$build_mode" in
        jvm)
            log_info "Build mode forced to JVM runner (VINTELLI_BUILD_MODE=jvm)."
            return 1
            ;;
        native)
            if [ -n "${GRAALVM_HOME:-}" ] && [ -x "${GRAALVM_HOME}/bin/native-image" ]; then
                log_ok "Native toolchain detected at ${GRAALVM_HOME}/bin/native-image"
                return 0
            elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/native-image" ]; then
                log_ok "Native toolchain detected at ${JAVA_HOME}/bin/native-image"
                return 0
            elif command -v native-image >/dev/null 2>&1; then
                log_ok "Native toolchain detected at $(command -v native-image)"
                return 0
            fi
            die "VINTELLI_BUILD_MODE=native was requested, but 'native-image' (GraalVM/Mandrel) was not found."
            ;;
        auto)
            if [ -n "${GRAALVM_HOME:-}" ] && [ -x "${GRAALVM_HOME}/bin/native-image" ]; then
                log_ok "Native toolchain detected at ${GRAALVM_HOME}/bin/native-image"
                return 0
            elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/native-image" ]; then
                log_ok "Native toolchain detected at ${JAVA_HOME}/bin/native-image"
                return 0
            elif command -v native-image >/dev/null 2>&1; then
                log_ok "Native toolchain detected at $(command -v native-image)"
                return 0
            fi
            log_warn "GraalVM/Mandrel 'native-image' toolchain not found; falling back to JVM runner mode."
            return 1
            ;;
        *)
            die "Invalid VINTELLI_BUILD_MODE '${build_mode}'. Allowed values: auto, native, jvm."
            ;;
    esac
}

ensure_temp_dir() {
    if [ -z "$TMP_WORK_DIR" ]; then
        TMP_WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/vintelli-install.XXXXXX")"
    fi
}

try_release_install() {
    if [ -n "$SOURCE_DIR" ]; then
        log_info "Local source directory specified (--source); skipping release check."
        return 1
    fi
    if [ "${VINTELLI_SKIP_RELEASE_CHECK:-false}" = "true" ]; then
        log_info "Skipping release check (VINTELLI_SKIP_RELEASE_CHECK=true)."
        return 1
    fi

    detect_http_client
    if [ -z "$HTTP_CLIENT" ]; then
        log_warn "Neither 'curl' nor 'wget' found; skipping release check and falling back to clone mode."
        return 1
    fi

    local release_arch="$DETECTED_ARCH"
    case "$DETECTED_ARCH" in
        x86_64) release_arch="amd64" ;;
        aarch64) release_arch="arm64" ;;
    esac

    local asset_name="vintelli-${DETECTED_OS}-${DETECTED_ARCH}"
    local release_tag="$TARGET_VERSION"
    local download_url=""
    local checksum_url="${VINTELLI_RELEASE_CHECKSUM_URL:-}"

    if [ -n "${VINTELLI_RELEASE_ASSET_URL:-}" ]; then
        download_url="$VINTELLI_RELEASE_ASSET_URL"
        asset_name="$(basename "${download_url#file://}")"
        [ -n "$checksum_url" ] || checksum_url="${download_url}.sha256"
    else
        local api_url
        if [ "$release_tag" = "latest" ]; then
            api_url="${DEFAULT_RELEASES_API}/latest"
        else
            api_url="${DEFAULT_RELEASES_API}/tags/${release_tag}"
        fi

        log_info "Checking GitHub Releases for pre-built binary (${DETECTED_OS}/${release_arch}, version: ${release_tag})..."
        local release_json=""
        if ! release_json="$(http_get_to_stdout "$api_url" 2>/dev/null)"; then
            log_info "No published release found for '${release_tag}'; falling back to clone mode."
            return 1
        fi

        local resolved_tag
        resolved_tag="$(printf '%s\n' "$release_json" | sed -nE 's/.*"tag_name"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' | head -n 1)"
        if [ -z "$resolved_tag" ]; then
            log_info "No release tag available in GitHub response; falling back to clone mode."
            return 1
        fi

        local plain_version="${resolved_tag#v}"
        local archive_asset="vintelli-${plain_version}-${DETECTED_OS}-${release_arch}.tar.gz"
        if printf '%s\n' "$release_json" | grep -q "\"name\"[[:space:]]*:[[:space:]]*\"${archive_asset}\""; then
            asset_name="$archive_asset"
            download_url="${DEFAULT_RELEASE_DOWNLOAD_BASE}/${resolved_tag}/${asset_name}"
            if [ -z "$checksum_url" ]; then
                if printf '%s\n' "$release_json" | grep -q '"name"[[:space:]]*:[[:space:]]*"SHA256SUMS"'; then
                    checksum_url="${DEFAULT_RELEASE_DOWNLOAD_BASE}/${resolved_tag}/SHA256SUMS"
                else
                    checksum_url="${download_url}.sha256"
                fi
            fi
        elif printf '%s\n' "$release_json" | grep -q "\"name\"[[:space:]]*:[[:space:]]*\"${asset_name}\""; then
            download_url="${DEFAULT_RELEASE_DOWNLOAD_BASE}/${resolved_tag}/${asset_name}"
            [ -n "$checksum_url" ] || checksum_url="${download_url}.sha256"
        else
            log_info "Release '${resolved_tag}' does not contain asset '${archive_asset}' or '${asset_name}'; falling back to clone mode."
            return 1
        fi
    fi

    local min_disk="${VINTELLI_MIN_DISK_MB:-50}"
    check_disk_space "$INSTALL_DIR" "$min_disk"

    if [ "$DRY_RUN" = true ]; then
        log_info "[dry-run] Would download release binary from ${download_url}"
        log_info "[dry-run] Would download and verify SHA-256 checksum from ${checksum_url}"
        log_info "[dry-run] Would install binary to ${INSTALL_BIN}"
        INSTALL_MODE_SUMMARY="release (dry-run)"
        return 0
    fi

    ensure_temp_dir
    local downloaded_asset="${TMP_WORK_DIR}/${asset_name}"
    local downloaded_sha="${TMP_WORK_DIR}/${asset_name}.sha256"

    log_info "Downloading release binary from ${download_url}..."
    http_download_file "$download_url" "$downloaded_asset" || die "Failed to download release binary from ${download_url}"

    log_info "Downloading SHA-256 checksum from ${checksum_url}..."
    http_download_file "$checksum_url" "$downloaded_sha" || die "Failed to download SHA-256 checksum from ${checksum_url}"

    verify_checksum "$downloaded_asset" "$downloaded_sha" "$asset_name"

    local binary_to_install="$downloaded_asset"
    case "$asset_name" in
        *.tar.gz)
            local extract_dir="${TMP_WORK_DIR}/release-extract"
            mkdir -p "$extract_dir"
            tar -xzf "$downloaded_asset" -C "$extract_dir" || die "Failed to extract release archive ${asset_name}"
            binary_to_install="$(find "$extract_dir" -type f -name 'vintelli' | head -n 1 || true)"
            [ -n "$binary_to_install" ] && [ -f "$binary_to_install" ] || die "Release archive ${asset_name} did not contain a 'vintelli' binary."
            ;;
    esac
    chmod 0755 "$binary_to_install"

    prepare_existing_installation
    run_privileged mkdir -p "$INSTALL_DIR"
    run_privileged cp "$binary_to_install" "$INSTALL_BIN"
    run_privileged chmod 0755 "$INSTALL_BIN"

    INSTALL_MODE_SUMMARY="release"
    return 0
}

prepare_existing_installation() {
    if [ ! -e "$INSTALL_BIN" ] && [ ! -d "$RUNTIME_DIR" ]; then
        return 0
    fi

    if [ "$FORCE_INSTALL" != true ]; then
        if [ -t 0 ] && [ "${VINTELLI_NONINTERACTIVE:-false}" != "true" ]; then
            printf 'Existing Vintelli installation detected at %s. Overwrite? [y/N]: ' "$INSTALL_BIN"
            local answer=""
            read -r answer
            case "$answer" in
                y|Y|yes|YES)
                    ;;
                *)
                    log_info "Installation cancelled by user; existing installation kept intact."
                    exit 0
                    ;;
            esac
        else
            log_info "Existing installation detected at ${INSTALL_BIN}; backing up before updating."
        fi
    else
        log_info "Overwriting existing installation at ${INSTALL_BIN} (--force)."
    fi

    if [ "$DRY_RUN" = true ]; then
        log_info "[dry-run] Would back up existing installation at ${INSTALL_BIN}"
        return 0
    fi

    if [ -e "$INSTALL_BIN" ]; then
        BACKUP_BIN="${INSTALL_BIN}.bak"
        run_privileged cp -p "$INSTALL_BIN" "$BACKUP_BIN"
        BACKUP_ACTIVE=true
        log_info "Backed up existing binary to ${BACKUP_BIN}"
    fi
    if [ -d "$RUNTIME_DIR" ]; then
        BACKUP_RUNTIME="${RUNTIME_DIR}.bak"
        run_privileged rm -rf "$BACKUP_RUNTIME"
        run_privileged cp -Rp "$RUNTIME_DIR" "$BACKUP_RUNTIME"
        BACKUP_ACTIVE=true
    fi
}

restore_backup() {
    log_warn "Restoring previous Vintelli installation from backup..."
    if [ -n "$BACKUP_BIN" ] && [ -e "$BACKUP_BIN" ]; then
        run_privileged mv -f "$BACKUP_BIN" "$INSTALL_BIN" || true
    fi
    if [ -n "$BACKUP_RUNTIME" ] && [ -d "$BACKUP_RUNTIME" ]; then
        run_privileged rm -rf "$RUNTIME_DIR" || true
        run_privileged mv "$BACKUP_RUNTIME" "$RUNTIME_DIR" || true
    fi
    BACKUP_ACTIVE=false
}

cleanup_backup() {
    if [ -n "$BACKUP_BIN" ] && [ -e "$BACKUP_BIN" ]; then
        run_privileged rm -f "$BACKUP_BIN"
    fi
    if [ -n "$BACKUP_RUNTIME" ] && [ -d "$BACKUP_RUNTIME" ]; then
        run_privileged rm -rf "$BACKUP_RUNTIME"
    fi
    BACKUP_ACTIVE=false
}

resolve_maven_command() {
    local build_root="$1"
    if [ -x "${build_root}/mvnw" ]; then
        if [ -f "${build_root}/.mvn/wrapper/maven-wrapper.properties" ] \
            && grep -Eq '^distributionUrl=.*\.zip[[:space:]]*$' "${build_root}/.mvn/wrapper/maven-wrapper.properties" 2>/dev/null \
            && ! command -v unzip >/dev/null 2>&1; then
            if command -v mvn >/dev/null 2>&1; then
                log_warn "'unzip' is not installed (required by mvnw to verify and extract the Maven .zip distribution); falling back to system 'mvn'."
                MAVEN_CMD="$(command -v mvn)"
                log_ok "Maven build launcher: ${MAVEN_CMD}"
                return 0
            fi
            die "Prerequisite missing: 'unzip' is required by Maven Wrapper (mvnw) to extract the Maven '.zip' distribution and verify its SHA-256 checksum (without 'unzip', mvnw falls back to '.tar.gz' and fails SHA-256 validation against distributionSha256Sum). Install 'unzip' or 'mvn' and try again."
        fi
        MAVEN_CMD="${build_root}/mvnw"
    elif command -v mvn >/dev/null 2>&1; then
        MAVEN_CMD="$(command -v mvn)"
    else
        die "Prerequisite missing: neither '${build_root}/mvnw' nor 'mvn' on PATH is available."
    fi
    log_ok "Maven build launcher: ${MAVEN_CMD}"
}

run_maven_build() {
    local build_dir="$1"
    shift
    ensure_temp_dir
    local build_log="${TMP_WORK_DIR}/maven-build.log"
    if ! (cd "$build_dir" && "$MAVEN_CMD" "$@" 2>&1 | tee "$build_log"); then
        if [ "$BACKUP_ACTIVE" = true ]; then
            restore_backup
        fi
        if grep -Fq "Failed to validate Maven distribution SHA-256" "$build_log" 2>/dev/null; then
            if ! command -v unzip >/dev/null 2>&1; then
                die "Maven Wrapper (mvnw) failed to verify the Maven distribution SHA-256 checksum because 'unzip' is not installed: without 'unzip', mvnw downloads the '.tar.gz' archive instead of '.zip' and compares it against the '.zip' distributionSha256Sum. Install 'unzip' (or 'mvn') and try again."
            fi
            die "Maven Wrapper (mvnw) failed to validate the downloaded Maven distribution against 'distributionSha256Sum' in .mvn/wrapper/maven-wrapper.properties."
        fi
        die "Maven build failed while running: ${MAVEN_CMD} $*"
    fi
}

install_from_clone() {
    local need_git=true
    if [ -n "$SOURCE_DIR" ]; then
        need_git=false
    fi

    check_clone_prerequisites "$need_git"

    local min_disk="${VINTELLI_MIN_DISK_MB:-500}"
    check_disk_space "$INSTALL_DIR" "$min_disk"

    local build_dir=""
    if [ -n "$SOURCE_DIR" ]; then
        [ -d "$SOURCE_DIR" ] || die "Source directory does not exist: ${SOURCE_DIR}"
        build_dir="$(cd "$SOURCE_DIR" && pwd)"
        [ -f "${build_dir}/pom.xml" ] || die "Invalid source directory '${build_dir}': missing pom.xml."
        [ -d "${build_dir}/vintelli-app" ] || die "Invalid source directory '${build_dir}': missing vintelli-app module."
        log_info "Using existing source tree at ${build_dir}"
    else
        check_network_connectivity
        if [ "$DRY_RUN" = true ]; then
            log_info "[dry-run] Would clone ${DEFAULT_REPO_URL} (version: ${TARGET_VERSION}) into a temporary directory"
        else
            ensure_temp_dir
            check_disk_space "$TMP_WORK_DIR" "$min_disk"
            build_dir="${TMP_WORK_DIR}/vintelli-src"
            log_info "Cloning ${DEFAULT_REPO_URL} into ${build_dir}..."
            if [ "$TARGET_VERSION" != "latest" ]; then
                git clone --depth 1 --branch "$TARGET_VERSION" "$DEFAULT_REPO_URL" "$build_dir"
            else
                git clone --depth 1 "$DEFAULT_REPO_URL" "$build_dir"
            fi
        fi
    fi

    local use_native=false
    if detect_native_toolchain; then
        use_native=true
    fi

    if [ -n "$build_dir" ]; then
        resolve_maven_command "$build_dir"
    fi

    if [ "$DRY_RUN" = true ]; then
        if [ "$use_native" = true ]; then
            log_info "[dry-run] Would build native binary with: ./mvnw -B -ntp package -Dnative -DskipTests"
            log_info "[dry-run] Would copy vintelli-app/target/*-runner to ${INSTALL_BIN}"
            INSTALL_MODE_SUMMARY="clone-native (dry-run)"
        else
            log_info "[dry-run] Would build JVM application with: ./mvnw -B -ntp package -DskipTests"
            log_info "[dry-run] Would copy vintelli-app/target/quarkus-app to ${RUNTIME_DIR}/quarkus-app"
            log_info "[dry-run] Would install wrapper script to ${INSTALL_BIN}"
            INSTALL_MODE_SUMMARY="clone-jvm (dry-run)"
        fi
        return 0
    fi

    prepare_existing_installation

    if [ "$use_native" = true ]; then
        local existing_runner=""
        if [ "${VINTELLI_REUSE_EXISTING_BUILD:-false}" = "true" ]; then
            existing_runner="$(find "${build_dir}/vintelli-app/target" -maxdepth 1 -type f -name '*-runner' 2>/dev/null | head -n 1 || true)"
        fi
        if [ -n "$existing_runner" ]; then
            log_info "Reusing existing native binary: ${existing_runner}"
        else
            log_info "Building Vintelli native binary (this may take a few minutes)..."
            run_maven_build "$build_dir" -B -ntp package -Dnative -DskipTests
            existing_runner="$(find "${build_dir}/vintelli-app/target" -maxdepth 1 -type f -name '*-runner' 2>/dev/null | head -n 1 || true)"
        fi
        [ -n "$existing_runner" ] && [ -f "$existing_runner" ] || die "Native build finished, but no '*-runner' binary was found under ${build_dir}/vintelli-app/target."

        run_privileged mkdir -p "$INSTALL_DIR"
        run_privileged rm -rf "$RUNTIME_DIR"
        run_privileged cp "$existing_runner" "$INSTALL_BIN"
        run_privileged chmod 0755 "$INSTALL_BIN"
        INSTALL_MODE_SUMMARY="clone-native"
    else
        local quarkus_app_dir="${build_dir}/vintelli-app/target/quarkus-app"
        if [ "${VINTELLI_REUSE_EXISTING_BUILD:-false}" = "true" ] && [ -f "${quarkus_app_dir}/quarkus-run.jar" ]; then
            log_info "Reusing existing JVM package at ${quarkus_app_dir}"
        else
            log_info "Building Vintelli JVM package..."
            run_maven_build "$build_dir" -B -ntp package -DskipTests
        fi
        [ -f "${quarkus_app_dir}/quarkus-run.jar" ] || die "JVM build finished, but '${quarkus_app_dir}/quarkus-run.jar' was not found."

        run_privileged mkdir -p "$INSTALL_DIR"
        run_privileged rm -rf "${RUNTIME_DIR}/quarkus-app"
        run_privileged mkdir -p "$RUNTIME_DIR"
        run_privileged cp -R "$quarkus_app_dir" "${RUNTIME_DIR}/quarkus-app"

        ensure_temp_dir
        local wrapper_tmp="${TMP_WORK_DIR}/vintelli-wrapper"
        cat > "$wrapper_tmp" <<EOF
#!/usr/bin/env bash
set -euo pipefail
readonly VINTELLI_JAR="${RUNTIME_DIR}/quarkus-app/quarkus-run.jar"
if [ ! -f "\$VINTELLI_JAR" ]; then
    printf 'vintelli error: runtime jar not found at %s\n' "\$VINTELLI_JAR" >&2
    exit 1
fi
if [ -n "\${JAVA_HOME:-}" ] && [ -x "\${JAVA_HOME}/bin/java" ]; then
    JAVA_EXEC="\${JAVA_HOME}/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_EXEC="\$(command -v java)"
else
    printf 'vintelli error: java (JDK ${REQUIRED_JAVA_MAJOR}+) not found on PATH or JAVA_HOME\n' >&2
    exit 1
fi
if [ -n "\${VINTELLI_LOG_LEVEL:-}" ]; then
    exec "\$JAVA_EXEC" -Dquarkus.log.level="\${VINTELLI_LOG_LEVEL}" -jar "\$VINTELLI_JAR" "\$@"
fi
exec "\$JAVA_EXEC" -jar "\$VINTELLI_JAR" "\$@"
EOF
        chmod 0755 "$wrapper_tmp"
        run_privileged cp "$wrapper_tmp" "$INSTALL_BIN"
        run_privileged chmod 0755 "$INSTALL_BIN"
        INSTALL_MODE_SUMMARY="clone-jvm"
    fi
}

select_shell_profile() {
    if [ -n "${VINTELLI_SHELL_PROFILE:-}" ]; then
        echo "$VINTELLI_SHELL_PROFILE"
        return
    fi
    local shell_name
    shell_name="$(basename "${SHELL:-}")"
    case "$shell_name" in
        zsh)
            echo "${HOME}/.zshrc"
            ;;
        bash)
            if [ -f "${HOME}/.bashrc" ] || [ ! -f "${HOME}/.bash_profile" ]; then
                echo "${HOME}/.bashrc"
            else
                echo "${HOME}/.bash_profile"
            fi
            ;;
        *)
            if [ -f "${HOME}/.bashrc" ]; then
                echo "${HOME}/.bashrc"
            elif [ -f "${HOME}/.zshrc" ]; then
                echo "${HOME}/.zshrc"
            else
                echo "${HOME}/.profile"
            fi
            ;;
    esac
}

configure_path() {
    case ":${PATH}:" in
        *":${INSTALL_DIR}:"*)
            log_ok "Install directory ${INSTALL_DIR} is already on PATH."
            return 0
            ;;
    esac

    if [ "$SYSTEM_INSTALL" = true ]; then
        log_warn "Install directory ${INSTALL_DIR} is not on PATH. Add 'export PATH=\"${INSTALL_DIR}:\$PATH\"' to your shell profile."
        return 0
    fi

    local profile_file
    profile_file="$(select_shell_profile)"
    local export_line="export PATH=\"${INSTALL_DIR}:\$PATH\""

    if [ "$DRY_RUN" = true ]; then
        log_info "[dry-run] Would ensure '${export_line}' is present in ${profile_file}"
        return 0
    fi

    mkdir -p "$(dirname "$profile_file")"
    touch "$profile_file"
    if grep -Fq "$INSTALL_DIR" "$profile_file" 2>/dev/null; then
        log_info "PATH entry for ${INSTALL_DIR} is already configured in ${profile_file}."
    else
        {
            printf '\n# Added by Vintelli installer\n'
            printf '%s\n' "$export_line"
        } >> "$profile_file"
        log_ok "Appended ${INSTALL_DIR} to PATH in ${profile_file}."
    fi
    log_info "Reload your shell or run: source ${profile_file}"
}

run_smoke_test() {
    if [ "$DRY_RUN" = true ]; then
        log_info "[dry-run] Would run post-install smoke test: ${INSTALL_BIN} --version && ${INSTALL_BIN} --help"
        INSTALLED_VERSION_SUMMARY="${TARGET_VERSION} (dry-run)"
        return 0
    fi

    [ -x "$INSTALL_BIN" ] || die "Smoke test failed: ${INSTALL_BIN} is not executable."

    log_info "Running post-install smoke test..."
    local version_out=""
    local help_out=""
    if ! version_out="$(cd "${TMPDIR:-/tmp}" && "$INSTALL_BIN" --version 2>&1)"; then
        die "Smoke test failed: '${INSTALL_BIN} --version' exited non-zero: ${version_out}"
    fi
    if ! help_out="$(cd "${TMPDIR:-/tmp}" && "$INSTALL_BIN" --help 2>&1)"; then
        die "Smoke test failed: '${INSTALL_BIN} --help' exited non-zero: ${help_out}"
    fi

    INSTALLED_VERSION_SUMMARY="$(printf '%s\n' "$version_out" | tr -d '\r' | grep -E '^vintelli ' | tail -n 1 || true)"
    if [ -z "$INSTALLED_VERSION_SUMMARY" ]; then
        INSTALLED_VERSION_SUMMARY="$(printf '%s\n' "$version_out" | tail -n 1 | tr -d '\r')"
    fi
    [ -n "$INSTALLED_VERSION_SUMMARY" ] || INSTALLED_VERSION_SUMMARY="vintelli"
    log_ok "Smoke test passed (${INSTALLED_VERSION_SUMMARY})."
}

perform_uninstall() {
    log_info "Uninstalling Vintelli from ${INSTALL_BIN}..."
    if [ "$DRY_RUN" = true ]; then
        log_info "[dry-run] Would remove ${INSTALL_BIN} and ${RUNTIME_DIR}"
        return 0
    fi

    local removed_any=false
    if [ -e "$INSTALL_BIN" ]; then
        run_privileged rm -f "$INSTALL_BIN"
        removed_any=true
        log_ok "Removed ${INSTALL_BIN}"
    fi
    if [ -e "${INSTALL_BIN}.bak" ]; then
        run_privileged rm -f "${INSTALL_BIN}.bak"
    fi
    if [ -d "$RUNTIME_DIR" ]; then
        run_privileged rm -rf "$RUNTIME_DIR"
        removed_any=true
        log_ok "Removed runtime directory ${RUNTIME_DIR}"
    fi

    if [ "$removed_any" = false ]; then
        log_info "No existing Vintelli installation found at ${INSTALL_BIN} (nothing to remove)."
    else
        log_ok "Vintelli uninstalled cleanly."
    fi
}

main() {
    parse_args "$@"
    resolve_install_paths

    if [ "$UNINSTALL" = true ]; then
        perform_uninstall
        exit 0
    fi

    detect_platform

    if ! try_release_install; then
        install_from_clone
    fi

    run_smoke_test
    cleanup_backup
    configure_path

    printf '\n'
    log_ok "Vintelli installation complete!"
    printf '  Mode:    %s\n' "${INSTALL_MODE_SUMMARY}"
    printf '  Version: %s\n' "${INSTALLED_VERSION_SUMMARY}"
    printf '  Binary:  %s\n' "${INSTALL_BIN}"
}

main "$@"
