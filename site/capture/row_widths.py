#!/usr/bin/env python3
"""Fail when the rows of a capture fragment have different widths.

A capture is a terminal screen, so every row has the same number of cells.
A shorter row means the capture lost its trailing spaces, and a page that
does not align the capture left then starts that row in another column.
"""

import html
import re
import sys
from pathlib import Path

TAG = re.compile(r"<[^>]+>")


def row_widths(fragment):
    text = html.unescape(TAG.sub("", fragment))
    rows = text.rstrip("\n").split("\n")
    return [len(row) for row in rows]


def ragged_rows(fragment):
    widths = row_widths(fragment)
    widest = max(widths, default=0)
    return [number for number, width in enumerate(widths, start=1) if width != widest]


def main(argv=None):
    paths = sys.argv[1:] if argv is None else argv
    failed = False
    for path in paths:
        rows = ragged_rows(Path(path).read_text(encoding="utf-8"))
        if rows:
            print(f"row_widths: {path}: {len(rows)} row(s) narrower than the screen, first on row {rows[0]}",
                  file=sys.stderr)
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
