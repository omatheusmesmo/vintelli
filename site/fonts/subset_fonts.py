#!/usr/bin/env python3
"""Subset the capture font to the characters the site captures use.

Reads the regular and bold JetBrains Mono NL Nerd Font Mono files from a
source directory, keeps printable ASCII, Latin-1 and every character found
in the capture fragments, and writes WOFF files plus the font license into
the output directory. Every captured character must have a glyph: one taken
from a fallback font has another width, shifts the rest of its row and breaks
the columns of the screen. A character the font lacks is mapped in ALIASES to
a glyph of the font that looks the same; any other gap fails the build.
"""

import argparse
import html
import re
import shutil
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont

FACES = {
    "regular": "JetBrainsMonoNLNerdFontMono-Regular.ttf",
    "bold": "JetBrainsMonoNLNerdFontMono-Bold.ttf",
}
OUTPUT_NAME = "vintelli-mono-{face}.woff"
LICENSE_NAME = "OFL.txt"
TAG = re.compile(r"<[^>]+>")
BASE_CODEPOINTS = set(range(0x20, 0x7F)) | set(range(0xA0, 0x100))
ALIASES = {
    0x21BB: 0xF01E,
}


def captured_codepoints(captures_dir):
    codepoints = set()
    fragments = sorted(captures_dir.glob("*.html"))
    if not fragments:
        raise SystemExit(f"subset_fonts: no capture fragments in {captures_dir}; run the capture first")
    for fragment in fragments:
        text = html.unescape(TAG.sub("", fragment.read_text(encoding="utf-8")))
        codepoints.update(ord(ch) for ch in text if ch not in "\r\n\t")
    return codepoints


def subset_face(source, target, codepoints):
    font = TTFont(source)
    cmap = font.getBestCmap()
    aliased = {cp: ALIASES[cp] for cp in codepoints if cp not in cmap and ALIASES.get(cp) in cmap}
    missing = sorted(cp for cp in codepoints if cp not in cmap and cp not in aliased and cp > 0x7E)
    options = subset.Options()
    options.flavor = "woff"
    options.layout_features = []
    options.hinting = False
    options.name_IDs = ["*"]
    options.notdef_outline = True
    options.drop_tables += ["PfEd"]
    subsetter = subset.Subsetter(options=options)
    kept = {cp for cp in codepoints if cp in cmap} | set(aliased.values())
    subsetter.populate(unicodes=sorted(kept))
    subsetter.subset(font)
    for table in font["cmap"].tables:
        if table.isUnicode():
            for codepoint, alias in aliased.items():
                if codepoint <= 0xFFFF or table.format in (12, 13):
                    table.cmap[codepoint] = cmap[alias]
    font.flavor = "woff"
    font.save(target)
    return missing


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--captures-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    codepoints = BASE_CODEPOINTS | captured_codepoints(args.captures_dir)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    failures = []
    for face, file_name in FACES.items():
        target = args.output_dir / OUTPUT_NAME.format(face=face)
        missing = subset_face(args.source_dir / file_name, target, codepoints)
        if missing:
            failures.append(f"{file_name}: " + " ".join(f"U+{cp:04X}" for cp in missing))
        print(f"subset_fonts: {target.name} {target.stat().st_size // 1024} KiB")
    shutil.copyfile(args.source_dir / LICENSE_NAME, args.output_dir / LICENSE_NAME)
    if failures:
        print("subset_fonts: captured characters without a glyph (map each in ALIASES):", file=sys.stderr)
        for failure in failures:
            print("  " + failure, file=sys.stderr)
        return 1
    print(f"subset_fonts: {len(codepoints)} characters kept")
    return 0


if __name__ == "__main__":
    sys.exit(main())
