#!/usr/bin/env bash
# Fails the site build when a screenshot placeholder has no capture, a spike link is
# broken, or the capture tooling tests fail. Run the capture first: captures are generated.
set -euo pipefail

SITE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SITE_DIR/.." && pwd)"
CAPTURES_DIR="$SITE_DIR/templates/captures"
failures=0

report() {
  echo "check: $*" >&2
  failures=$((failures + 1))
}

python3 "$SITE_DIR/generate_content.py" || report "content generation failed (see above)"

(cd "$SITE_DIR/capture" && python3 -m unittest -q) || report "capture tooling tests failed"

mapfile -t placeholders < <(
  grep -rhoE 'captures/[A-Za-z0-9_-]+' "$SITE_DIR/content" "$SITE_DIR/templates" --exclude-dir=captures 2>/dev/null \
    | sed 's#^captures/##' | sort -u
)
if [ "${#placeholders[@]}" -eq 0 ]; then
  report "no screenshot placeholders found in the site content"
fi
for name in "${placeholders[@]}"; do
  fragment="$CAPTURES_DIR/$name.html"
  if [ ! -s "$fragment" ]; then
    report "screenshot placeholder '$name' has no generated capture ($fragment)"
  fi
done

if [ -d "$CAPTURES_DIR" ]; then
  while IFS= read -r fragment; do
    if grep -qE '/home/|/Users/|/root/|/tmp/|[A-Za-z]:\\\\Users' "$fragment"; then
      report "capture $(basename "$fragment") contains an absolute path"
    fi
  done < <(find "$CAPTURES_DIR" -name '*.html' -type f)
fi

mapfile -t spike_links < <(
  grep -rhoE 'docs/spikes/[^ )"<>'\''`]+' "$SITE_DIR/content" "$SITE_DIR/templates" --exclude-dir=captures 2>/dev/null \
    | sort -u
)
for link in "${spike_links[@]}"; do
  if [ ! -e "$REPO_ROOT/$link" ]; then
    report "broken spike link: $link"
  fi
done

while IFS= read -r spike; do
  relative="${spike#"$REPO_ROOT"/}"
  if ! grep -qF "$relative" "$SITE_DIR/content/decisions.md" 2>/dev/null; then
    report "spike not listed on the decisions page: $relative"
  fi
done < <(find "$REPO_ROOT/docs/spikes" -type f \( -name '*.md' -o -name '*.json' \))

if [ "$failures" -gt 0 ]; then
  echo "check: $failures problem(s), the site must not be built" >&2
  exit 1
fi
echo "check: ${#placeholders[@]} placeholder(s) with captures, ${#spike_links[@]} spike link(s) resolved"
