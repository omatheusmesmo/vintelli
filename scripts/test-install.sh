#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
readonly REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly INSTALL_SCRIPT="${SCRIPT_DIR}/install.sh"

TEST_TMP="$(mktemp -d "${TMPDIR:-/tmp}/vintelli-install-test.XXXXXX")"
cleanup() {
    if [ -n "$TEST_TMP" ] && [ -d "$TEST_TMP" ]; then
        rm -rf "$TEST_TMP"
    fi
}
trap cleanup EXIT

pass() {
    printf '[PASS] %s\n' "$*"
}

fail() {
    printf '[FAIL] %s\n' "$*" >&2
    exit 1
}

assert_contains() {
    local haystack="$1"
    local needle="$2"
    local msg="$3"
    if ! printf '%s' "$haystack" | grep -Fq -- "$needle"; then
        fail "${msg}: expected output to contain '${needle}', got: ${haystack}"
    fi
}

assert_exit_nonzero() {
    local desc="$1"
    shift
    local out=""
    if out="$("$@" 2>&1)"; then
        fail "${desc}: expected non-zero exit code, but command succeeded with output: ${out}"
    fi
    LAST_FAILURE_OUT="$out"
}

compute_sha256() {
    local file="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$file" | awk '{print tolower($1)}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$file" | awk '{print tolower($1)}'
    else
        openssl dgst -sha256 "$file" | awk '{print tolower($NF)}'
    fi
}

create_mock_source_repo() {
    local src_dir="$1"
    mkdir -p "${src_dir}/vintelli-app/target/quarkus-app"
    cat > "${src_dir}/pom.xml" <<'EOF'
<project><modelVersion>4.0.0</modelVersion><groupId>dev.omatheusmesmo</groupId><artifactId>vintelli</artifactId><version>1.0.0-SNAPSHOT</version></project>
EOF

    if [ -f "${REPO_ROOT}/vintelli-app/target/quarkus-app/quarkus-run.jar" ]; then
        cp -R "${REPO_ROOT}/vintelli-app/target/quarkus-app/." "${src_dir}/vintelli-app/target/quarkus-app/"
    else
        local stub_src="${src_dir}/stub_src"
        mkdir -p "$stub_src"
        cat > "${stub_src}/StubMain.java" <<'EOF'
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
EOF
        javac -d "$stub_src" "${stub_src}/StubMain.java"
        jar --create --file "${src_dir}/vintelli-app/target/quarkus-app/quarkus-run.jar" --main-class StubMain -C "$stub_src" StubMain.class
        rm -rf "$stub_src"
    fi

    cat > "${src_dir}/mvnw" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
for arg in "$@"; do
    if [ "$arg" = "-Dnative" ]; then
        mkdir -p vintelli-app/target
        cat > vintelli-app/target/vintelli-app-1.0.0-SNAPSHOT-runner <<'RUNNER'
#!/usr/bin/env bash
if [ "${1:-}" = "--version" ] || [ "${1:-}" = "-v" ] || [ "${1:-}" = "version" ]; then
    echo "vintelli 1.0.0-SNAPSHOT (native)"
    exit 0
fi
if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ] || [ "${1:-}" = "help" ]; then
    echo "Available commands: agents, inspect, problems, run, terminal"
    exit 0
fi
exit 127
RUNNER
        chmod 0755 vintelli-app/target/vintelli-app-1.0.0-SNAPSHOT-runner
        exit 0
    fi
done
exit 0
EOF
    chmod 0755 "${src_dir}/mvnw"
}

main() {
    [ -f "$INSTALL_SCRIPT" ] || fail "Missing ${INSTALL_SCRIPT}"
    chmod 0755 "$INSTALL_SCRIPT"

    # 1. --help
    local help_out
    help_out="$("$INSTALL_SCRIPT" --help)"
    assert_contains "$help_out" "Usage:" "--help output"
    assert_contains "$help_out" "--source" "--help options"
    pass "--help prints usage and exits 0"

    # 2. Invalid arguments exit non-zero
    assert_exit_nonzero "unknown option" "$INSTALL_SCRIPT" --not-a-valid-flag
    assert_contains "$LAST_FAILURE_OUT" "Unknown option" "unknown option message"
    assert_exit_nonzero "missing --source value" "$INSTALL_SCRIPT" --source
    pass "Invalid CLI options fail with clear messages"

    # 3. Prepare mock source tree
    local mock_src="${TEST_TMP}/mock-source"
    create_mock_source_repo "$mock_src"

    # 4. --dry-run does not create files
    local dry_prefix="${TEST_TMP}/dry-run-bin"
    local dry_profile="${TEST_TMP}/dry-profile"
    local dry_out
    dry_out="$(VINTELLI_SHELL_PROFILE="$dry_profile" "$INSTALL_SCRIPT" --dry-run --source "$mock_src" --prefix "$dry_prefix")"
    assert_contains "$dry_out" "[dry-run]" "dry-run output"
    [ ! -e "${dry_prefix}/vintelli" ] || fail "--dry-run must not create ${dry_prefix}/vintelli"
    [ ! -e "$dry_profile" ] || fail "--dry-run must not create shell profile"
    pass "--dry-run logs planned steps without modifying the filesystem"

    # 5. Health check failures exit non-zero with actionable messages
    assert_exit_nonzero "missing source dir" "$INSTALL_SCRIPT" --source "${TEST_TMP}/does-not-exist" --prefix "$dry_prefix"
    assert_contains "$LAST_FAILURE_OUT" "Source directory does not exist" "missing source message"

    local empty_src="${TEST_TMP}/empty-src"
    mkdir -p "$empty_src"
    assert_exit_nonzero "invalid source dir" "$INSTALL_SCRIPT" --source "$empty_src" --prefix "$dry_prefix"
    assert_contains "$LAST_FAILURE_OUT" "missing pom.xml" "invalid source message"

    assert_exit_nonzero "Java version check" env VINTELLI_REQUIRED_JAVA_MAJOR=99 "$INSTALL_SCRIPT" --source "$mock_src" --prefix "$dry_prefix"
    assert_contains "$LAST_FAILURE_OUT" "Java 99+ is required" "Java version message"

    assert_exit_nonzero "Disk space check" env VINTELLI_MIN_DISK_MB=999999999 "$INSTALL_SCRIPT" --source "$mock_src" --prefix "$dry_prefix"
    assert_contains "$LAST_FAILURE_OUT" "Insufficient disk space" "disk space message"

    assert_exit_nonzero "Invalid build mode" env VINTELLI_BUILD_MODE=invalid "$INSTALL_SCRIPT" --source "$mock_src" --prefix "$dry_prefix"
    assert_contains "$LAST_FAILURE_OUT" "Invalid VINTELLI_BUILD_MODE" "invalid build mode message"

    local wrapper_src="${TEST_TMP}/wrapper-source"
    cp -R "$mock_src" "$wrapper_src"
    mkdir -p "${wrapper_src}/.mvn/wrapper"
    cat > "${wrapper_src}/.mvn/wrapper/maven-wrapper.properties" <<'EOF'
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip
distributionSha256Sum=5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce
EOF
    local no_unzip_bin="${TEST_TMP}/no-unzip-bin"
    mkdir -p "$no_unzip_bin"
    for tool in bash sh env uname id df awk sed grep head tail tr cat mkdir rm cp mv chmod mktemp dirname basename touch java; do
        if command -v "$tool" >/dev/null 2>&1; then
            ln -sf "$(command -v "$tool")" "${no_unzip_bin}/${tool}"
        fi
    done
    assert_exit_nonzero "missing unzip for mvnw .zip distribution" env PATH="$no_unzip_bin" JAVA_HOME="${JAVA_HOME:-}" "$INSTALL_SCRIPT" --source "$wrapper_src" --prefix "$dry_prefix"
    assert_contains "$LAST_FAILURE_OUT" "'unzip' is required by Maven Wrapper (mvnw)" "missing unzip semantic message"
    pass "Health checks reject invalid prerequisites and disk constraints"

    # 6. Clone mode (JVM fallback): build, install, delete source tree, verify persistence & PATH profile
    local jvm_src="${TEST_TMP}/jvm-source-copy"
    cp -R "$mock_src" "$jvm_src"
    local jvm_prefix="${TEST_TMP}/jvm-prefix/bin"
    local jvm_runtime="${TEST_TMP}/jvm-prefix/runtime"
    local jvm_profile="${TEST_TMP}/bashrc"

    VINTELLI_BUILD_MODE=jvm \
    VINTELLI_RUNTIME_DIR="$jvm_runtime" \
    VINTELLI_SHELL_PROFILE="$jvm_profile" \
    VINTELLI_NONINTERACTIVE=true \
        "$INSTALL_SCRIPT" --source "$jvm_src" --prefix "$jvm_prefix"

    [ -x "${jvm_prefix}/vintelli" ] || fail "Expected installed binary at ${jvm_prefix}/vintelli"
    [ -f "$jvm_profile" ] || fail "Expected shell profile ${jvm_profile} to be updated"
    assert_contains "$(cat "$jvm_profile")" "$jvm_prefix" "PATH export in shell profile"

    # Delete the source/clone tree completely and verify installed binary still works!
    rm -rf "$jvm_src"
    local installed_help
    local installed_ver
    installed_help="$("${jvm_prefix}/vintelli" --help)"
    installed_ver="$("${jvm_prefix}/vintelli" --version)"
    assert_contains "$installed_help" "Available commands:" "installed vintelli --help after source deletion"
    assert_contains "$installed_ver" "vintelli " "installed vintelli --version after source deletion"
    pass "Clone mode (JVM) installs persistent binary that survives source tree deletion"

    # 7. Idempotency & --force: re-running does not corrupt install or duplicate PATH entry
    cp -R "$mock_src" "$jvm_src"
    VINTELLI_BUILD_MODE=jvm \
    VINTELLI_RUNTIME_DIR="$jvm_runtime" \
    VINTELLI_SHELL_PROFILE="$jvm_profile" \
        "$INSTALL_SCRIPT" --source "$jvm_src" --prefix "$jvm_prefix" --force

    local path_count
    path_count="$(grep -Fc "$jvm_prefix" "$jvm_profile")"
    [ "$path_count" -eq 1 ] || fail "Expected exactly 1 PATH entry in ${jvm_profile}, found ${path_count}"
    rm -rf "$jvm_src"
    "${jvm_prefix}/vintelli" --help >/dev/null
    pass "Idempotent re-install (--force) preserves working binary and avoids duplicate PATH entries"

    # 8. Release mode: checksum verification failure rolls back to backup; valid checksum succeeds
    local release_dir="${TEST_TMP}/release-assets"
    mkdir -p "$release_dir"
    local fake_release_bin="${release_dir}/vintelli-release-bin"
    cat > "$fake_release_bin" <<'EOF'
#!/usr/bin/env bash
if [ "${1:-}" = "--version" ]; then
    echo "vintelli 0.1.0-release"
    exit 0
fi
if [ "${1:-}" = "--help" ]; then
    echo "Available commands: agents, inspect, problems, run, terminal"
    exit 0
fi
exit 1
EOF
    chmod 0755 "$fake_release_bin"

    local bad_sha_file="${release_dir}/vintelli-release-bin.bad.sha256"
    echo "0000000000000000000000000000000000000000000000000000000000000000  vintelli-release-bin" > "$bad_sha_file"

    assert_exit_nonzero "checksum mismatch" env \
        VINTELLI_RELEASE_ASSET_URL="file://${fake_release_bin}" \
        VINTELLI_RELEASE_CHECKSUM_URL="file://${bad_sha_file}" \
        VINTELLI_RUNTIME_DIR="$jvm_runtime" \
        "$INSTALL_SCRIPT" --prefix "$jvm_prefix" --force
    assert_contains "$LAST_FAILURE_OUT" "SHA-256 checksum mismatch" "checksum mismatch error"

    # Existing installation must still work after failed release install
    "${jvm_prefix}/vintelli" --help >/dev/null

    local valid_sha
    valid_sha="$(compute_sha256 "$fake_release_bin")"
    local good_sha_file="${release_dir}/vintelli-release-bin.sha256"
    echo "${valid_sha}  vintelli-release-bin" > "$good_sha_file"

    VINTELLI_RELEASE_ASSET_URL="file://${fake_release_bin}" \
    VINTELLI_RELEASE_CHECKSUM_URL="file://${good_sha_file}" \
    VINTELLI_RUNTIME_DIR="$jvm_runtime" \
    VINTELLI_SHELL_PROFILE="$jvm_profile" \
        "$INSTALL_SCRIPT" --prefix "$jvm_prefix" --force

    local rel_ver
    rel_ver="$("${jvm_prefix}/vintelli" --version)"
    assert_contains "$rel_ver" "vintelli 0.1.0-release" "release binary version"

    local archive_stage="${release_dir}/vintelli-0.1.0-linux-amd64"
    mkdir -p "$archive_stage"
    cp "$fake_release_bin" "${archive_stage}/vintelli"
    local archive_file="${release_dir}/vintelli-0.1.0-linux-amd64.tar.gz"
    tar -C "$release_dir" -czf "$archive_file" "vintelli-0.1.0-linux-amd64"
    local archive_sha
    archive_sha="$(compute_sha256 "$archive_file")"
    local sha256sums_file="${release_dir}/SHA256SUMS"
    cat > "$sha256sums_file" <<EOF
1111111111111111111111111111111111111111111111111111111111111111  vintelli-0.1.0-darwin-arm64.tar.gz
${archive_sha}  vintelli-0.1.0-linux-amd64.tar.gz
EOF

    VINTELLI_RELEASE_ASSET_URL="file://${archive_file}" \
    VINTELLI_RELEASE_CHECKSUM_URL="file://${sha256sums_file}" \
    VINTELLI_RUNTIME_DIR="$jvm_runtime" \
    VINTELLI_SHELL_PROFILE="$jvm_profile" \
        "$INSTALL_SCRIPT" --prefix "$jvm_prefix" --force

    rel_ver="$("${jvm_prefix}/vintelli" --version)"
    assert_contains "$rel_ver" "vintelli 0.1.0-release" "release tar.gz archive binary version"
    pass "Release mode verifies SHA-256 checksum, rejects tampered binaries, and installs valid release"

    # 9. --uninstall removes binary and runtime and is idempotent
    VINTELLI_RUNTIME_DIR="$jvm_runtime" "$INSTALL_SCRIPT" --prefix "$jvm_prefix" --uninstall
    [ ! -e "${jvm_prefix}/vintelli" ] || fail "Expected ${jvm_prefix}/vintelli to be removed by --uninstall"
    [ ! -d "$jvm_runtime" ] || fail "Expected ${jvm_runtime} to be removed by --uninstall"
    VINTELLI_RUNTIME_DIR="$jvm_runtime" "$INSTALL_SCRIPT" --prefix "$jvm_prefix" --uninstall
    pass "--uninstall cleanly removes installation and is idempotent"

    # 10. Clone mode (native toolchain detected): builds *-runner, installs persistent binary, survives source deletion
    local native_src="${TEST_TMP}/native-source-copy"
    cp -R "$mock_src" "$native_src"
    local fake_graal="${TEST_TMP}/fake-graal/bin"
    mkdir -p "$fake_graal"
    cat > "${fake_graal}/native-image" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
    chmod 0755 "${fake_graal}/native-image"

    local native_prefix="${TEST_TMP}/native-prefix/bin"
    GRAALVM_HOME="${TEST_TMP}/fake-graal" \
    VINTELLI_SHELL_PROFILE="$jvm_profile" \
        "$INSTALL_SCRIPT" --source "$native_src" --prefix "$native_prefix" --force

    rm -rf "$native_src"
    local native_ver
    native_ver="$("${native_prefix}/vintelli" --version)"
    assert_contains "$native_ver" "vintelli 1.0.0-SNAPSHOT (native)" "native binary version after source deletion"
    pass "Clone mode (native) installs persistent native binary that survives source tree deletion"

    printf '\nAll install.sh tests passed!\n'
}

main "$@"
