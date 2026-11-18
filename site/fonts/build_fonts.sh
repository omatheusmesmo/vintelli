#!/usr/bin/env bash
# Builds the web font of the site captures: downloads the pinned Nerd Fonts
# release once (checked against its SHA-256), then subsets it to the characters
# the captures use. Run the capture first. PYTHON must have fontTools
# (site/fonts/requirements.txt).
set -euo pipefail

SITE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FONT_VERSION="v3.5.1"
FONT_ARCHIVE="JetBrainsMono.tar.xz"
FONT_SHA256="04d5e8f903693f9dd13e16f867e994834e681eb3c72c0d337a770dcda09010cf"
FONT_URL="https://github.com/ryanoasis/nerd-fonts/releases/download/$FONT_VERSION/$FONT_ARCHIVE"
CACHE_DIR="${VINTELLI_FONT_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/vintelli-site-fonts}/$FONT_VERSION"
PYTHON="${PYTHON:-python3}"

mkdir -p "$CACHE_DIR"
archive="$CACHE_DIR/$FONT_ARCHIVE"
if [ ! -f "$archive" ] || ! echo "$FONT_SHA256  $archive" | sha256sum -c --status; then
  curl -fsSL --retry 3 -o "$archive.part" "$FONT_URL"
  if ! echo "$FONT_SHA256  $archive.part" | sha256sum -c --status; then
    rm -f "$archive.part"
    echo "build_fonts: $FONT_ARCHIVE does not match its pinned SHA-256" >&2
    exit 1
  fi
  mv "$archive.part" "$archive"
fi

source_dir="$CACHE_DIR/extracted"
mkdir -p "$source_dir"
tar -xJf "$archive" -C "$source_dir" \
  JetBrainsMonoNLNerdFontMono-Regular.ttf JetBrainsMonoNLNerdFontMono-Bold.ttf OFL.txt

"$PYTHON" "$SITE_DIR/fonts/subset_fonts.py" \
  --source-dir "$source_dir" \
  --captures-dir "$SITE_DIR/templates/captures" \
  --output-dir "$SITE_DIR/public/fonts"
