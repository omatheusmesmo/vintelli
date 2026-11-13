#!/usr/bin/env bash
# Renders the Vintelli TUI screens to HTML fragments for the project site.
# Usage: capture.sh <quarkus-run.jar> [output-dir]
# The TUI runs in tmux on a private socket, against a throwaway `git archive` copy of HEAD.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_ARGUMENT="${1:-}"
OUTPUT_DIR="${2:-$SCRIPT_DIR/../templates/captures}"
SOCKET=vintelli-site
SESSION=vintelli
COLUMNS=160
ROWS=45
THEMES=(darcula dark)
SCREENS=(project goto-file search-everywhere problems maven structure)

die() {
  echo "capture: $*" >&2
  exit 1
}

tmux_site() {
  tmux -L "$SOCKET" "$@"
}

screen_keys() {
  case "$1" in
    project) echo "M-1" ;;
    goto-file) echo "C-o" ;;
    search-everywhere) echo "C-p" ;;
    problems) echo "M-6" ;;
    maven) echo "M-9" ;;
    structure) echo "M-7" ;;
    *) die "no keys defined for screen $1" ;;
  esac
}

[ -n "$JAR_ARGUMENT" ] || die "usage: capture.sh <quarkus-run.jar> [output-dir]"
[ -f "$JAR_ARGUMENT" ] || die "runner jar not found: $JAR_ARGUMENT"
JAR="$(cd "$(dirname "$JAR_ARGUMENT")" && pwd)/$(basename "$JAR_ARGUMENT")"
command -v tmux >/dev/null || die "tmux is required"
command -v python3 >/dev/null || die "python3 is required"
[ -n "${HOME:-}" ] || die "HOME must be set so that it can be scrubbed from the captures"

REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/vintelli-site.XXXXXX")"
WORK_REAL="$(cd "$WORK" && pwd -P)"

cleanup() {
  tmux_site kill-server 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

mkdir "$WORK/repo" "$WORK/out"
git -C "$REPO_ROOT" archive HEAD | tar -x -C "$WORK/repo"

REPLACE_ARGS=(--replace "$WORK_REAL" "<workspace>" --replace "$HOME" "<home>")
if [ "$WORK" != "$WORK_REAL" ]; then
  REPLACE_ARGS+=(--replace "$WORK" "<workspace>")
fi

pane_state() {
  tmux_site display-message -p -t "$SESSION" '#{pane_dead} #{pane_dead_status}' 2>/dev/null || echo "1 unknown"
}

plain_pane() {
  tmux_site capture-pane -p -t "$SESSION" 2>/dev/null || true
}

press() {
  tmux_site send-keys -t "$SESSION" "$@"
}

start_tui() {
  tmux_site kill-server 2>/dev/null || true
  tmux_site -f /dev/null new-session -d -s "$SESSION" -x "$COLUMNS" -y "$ROWS" -c "$WORK/repo" \
    "env TERM=xterm-256color COLORTERM=truecolor VINTELLI_TUI_MARQUEE=false java -Dvintelli.tui.theme=$1 -jar '$JAR' --tui"
  tmux_site set-option -g remain-on-exit on
}

wait_until_settled() {
  local previous="" current="" attempt
  for attempt in $(seq 1 60); do
    read -r dead _ < <(pane_state)
    if [ "$dead" = 1 ]; then
      return 0
    fi
    current="$(plain_pane)"
    if [ -n "$current" ] && [ "$current" = "$previous" ]; then
      return 0
    fi
    previous="$current"
    sleep 1
  done
}

capture_screen() {
  local theme="$1" screen="$2" keys="$3"
  local ansi="$WORK/$screen-$theme.ansi" html="$WORK/$screen-$theme.html" err="$WORK/$screen-$theme.err"
  if [ -n "$keys" ]; then
    press $keys
  fi
  sleep 1
  wait_until_settled
  local dead status
  read -r dead status < <(pane_state)
  if [ "$dead" = 1 ]; then
    die "the TUI exited early during screen $screen ($theme), exit status $status"
  fi
  tmux_site capture-pane -p -e -t "$SESSION" > "$ansi"
  if ! python3 "$SCRIPT_DIR/ansi_to_html.py" "${REPLACE_ARGS[@]}" "$ansi" > "$html" 2> "$err"; then
    die "screen $screen ($theme) failed: $(cat "$err")"
  fi
  mv "$html" "$WORK/out/$screen-$theme.html"
}

start_tui "${THEMES[0]}"
wait_until_settled

for index in "${!THEMES[@]}"; do
  theme="${THEMES[$index]}"
  if [ "$index" -gt 0 ]; then
    press Escape
    sleep 1
    press C-t
    sleep 2
  fi
  for screen in "${SCREENS[@]}"; do
    capture_screen "$theme" "$screen" "$(screen_keys "$screen")"
    press Escape
    sleep 1
  done
done

if cmp -s "$WORK/out/project-${THEMES[0]}.html" "$WORK/out/project-${THEMES[1]}.html"; then
  die "the theme switch (C-t) changed nothing: ${THEMES[0]} and ${THEMES[1]} project captures are identical"
fi

for file in "$WORK"/out/*.html; do
  if grep -qF -e "$WORK_REAL" -e "$HOME" "$file"; then
    die "a scrubbed path survived in $(basename "$file")"
  fi
done

case "$(basename "$OUTPUT_DIR")" in
  captures) ;;
  *) die "output directory must be named captures: $OUTPUT_DIR" ;;
esac
mkdir -p "$OUTPUT_DIR"
rm -f "$OUTPUT_DIR"/*.html
cp "$WORK"/out/*.html "$OUTPUT_DIR/"
echo "capture: wrote $(ls "$OUTPUT_DIR"/*.html | wc -l) fragments to $OUTPUT_DIR"
