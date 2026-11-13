#!/usr/bin/env python3
"""Convert `tmux capture-pane -p -e` output into a self-contained HTML fragment.

Standard library only. Supports the 16, 256 and 24-bit SGR colors, bold, dim,
italic, underline, reverse and strikethrough, escapes HTML, and refuses a
capture that is empty, still contains a scrubbed value, or contains an em or
en dash.
"""

import argparse
import html
import re
import sys

DEFAULT_FOREGROUND = "#d4d4d4"
DEFAULT_BACKGROUND = "#1e1f22"
FORBIDDEN_DASHES = ("\u2014", "\u2013")
MINIMUM_REPLACEMENT_LENGTH = 3

ESCAPE_TOKEN = re.compile(
    r"\x1b\[([0-9;]*)m"
    r"|\x1b\[[0-9;?]*[ -/]*[@-~]"
    r"|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)"
    r"|\x1b[0-9=>@-Z\\-_]"
)

BASIC_PALETTE = (
    "#000000", "#cd0000", "#00cd00", "#cdcd00",
    "#0000ee", "#cd00cd", "#00cdcd", "#e5e5e5",
    "#7f7f7f", "#ff0000", "#00ff00", "#ffff00",
    "#5c5cff", "#ff00ff", "#00ffff", "#ffffff",
)
CUBE_LEVELS = (0, 95, 135, 175, 215, 255)


class CaptureError(Exception):
    pass


def rgb_hex(red, green, blue):
    return f"#{red:02x}{green:02x}{blue:02x}"


def palette_color(index):
    if not 0 <= index <= 255:
        return None
    if index < 16:
        return BASIC_PALETTE[index]
    if index < 232:
        offset = index - 16
        return rgb_hex(
            CUBE_LEVELS[offset // 36],
            CUBE_LEVELS[(offset // 6) % 6],
            CUBE_LEVELS[offset % 6],
        )
    level = 8 + 10 * (index - 232)
    return rgb_hex(level, level, level)


class Style:
    def __init__(self):
        self.reset()

    def reset(self):
        self.foreground = None
        self.background = None
        self.bold = False
        self.dim = False
        self.italic = False
        self.underline = False
        self.reverse = False
        self.strike = False

    def css(self):
        foreground, background = self.foreground, self.background
        if self.reverse:
            foreground, background = background or DEFAULT_BACKGROUND, foreground or DEFAULT_FOREGROUND
        declarations = []
        if foreground:
            declarations.append(f"color:{foreground}")
        if background:
            declarations.append(f"background-color:{background}")
        if self.bold:
            declarations.append("font-weight:bold")
        if self.dim:
            declarations.append("opacity:0.6")
        if self.italic:
            declarations.append("font-style:italic")
        decorations = []
        if self.underline:
            decorations.append("underline")
        if self.strike:
            decorations.append("line-through")
        if decorations:
            declarations.append("text-decoration:" + " ".join(decorations))
        return ";".join(declarations)


def extended_color(codes, index):
    if index + 1 < len(codes) and codes[index + 1] == 5 and index + 2 < len(codes):
        return palette_color(codes[index + 2]), 3
    if index + 1 < len(codes) and codes[index + 1] == 2 and index + 4 < len(codes):
        red, green, blue = codes[index + 2:index + 5]
        if max(red, green, blue) <= 255:
            return rgb_hex(red, green, blue), 5
    return None, len(codes) - index


def apply_sgr(style, parameters):
    codes = [int(part) if part else 0 for part in parameters.split(";")] if parameters else [0]
    index = 0
    while index < len(codes):
        code = codes[index]
        if code == 0:
            style.reset()
        elif code == 1:
            style.bold = True
        elif code == 2:
            style.dim = True
        elif code == 3:
            style.italic = True
        elif code == 4:
            style.underline = True
        elif code == 7:
            style.reverse = True
        elif code == 9:
            style.strike = True
        elif code == 22:
            style.bold = False
            style.dim = False
        elif code == 23:
            style.italic = False
        elif code == 24:
            style.underline = False
        elif code == 27:
            style.reverse = False
        elif code == 29:
            style.strike = False
        elif code == 39:
            style.foreground = None
        elif code == 49:
            style.background = None
        elif 30 <= code <= 37:
            style.foreground = BASIC_PALETTE[code - 30]
        elif 90 <= code <= 97:
            style.foreground = BASIC_PALETTE[code - 90 + 8]
        elif 40 <= code <= 47:
            style.background = BASIC_PALETTE[code - 40]
        elif 100 <= code <= 107:
            style.background = BASIC_PALETTE[code - 100 + 8]
        elif code in (38, 48):
            color, consumed = extended_color(codes, index)
            if code == 38:
                style.foreground = color or style.foreground
            else:
                style.background = color or style.background
            index += consumed
            continue
        index += 1


def escape_text(text):
    return html.escape(text, quote=True).replace("{", "&#123;").replace("}", "&#125;")


def plain_text(ansi_text):
    return ESCAPE_TOKEN.sub("", ansi_text).replace("\r", "")


def render_html(ansi_text):
    style = Style()
    body = []
    position = 0
    for match in ESCAPE_TOKEN.finditer(ansi_text):
        body.append(_segment(ansi_text[position:match.start()], style))
        position = match.end()
        if match.group(1) is not None:
            apply_sgr(style, match.group(1))
    body.append(_segment(ansi_text[position:], style))
    return (
        f'<pre class="vintelli-capture" style="background-color:{DEFAULT_BACKGROUND};'
        f'color:{DEFAULT_FOREGROUND}">' + "".join(body) + "</pre>\n"
    )


def _segment(text, style):
    text = text.replace("\r", "")
    if not text:
        return ""
    css = style.css()
    escaped = escape_text(text)
    return f'<span style="{css}">{escaped}</span>' if css else escaped


def scrub(ansi_text, replacements):
    for value, label in replacements:
        if value:
            ansi_text = ansi_text.replace(value, label)
    return ansi_text


def leftover_values(scrubbed_text, rendered, replacements):
    plain = plain_text(scrubbed_text)
    return [
        value
        for value, _ in replacements
        if value and (value in plain or html.escape(value, quote=True) in rendered)
    ]


def dash_lines(rendered):
    return [
        number
        for number, line in enumerate(rendered.splitlines(), start=1)
        if any(dash in line for dash in FORBIDDEN_DASHES)
    ]


def convert(ansi_text, replacements=()):
    for value, _ in replacements:
        if len(value) < MINIMUM_REPLACEMENT_LENGTH:
            raise CaptureError(f"scrub value is too short to be a path: {len(value)} characters")
    if not plain_text(ansi_text).strip():
        raise CaptureError("the capture is empty")
    scrubbed = scrub(ansi_text, replacements)
    rendered = render_html(scrubbed)
    leftovers = leftover_values(scrubbed, rendered, replacements)
    if leftovers:
        raise CaptureError(f"{len(leftovers)} scrubbed value(s) still appear in the capture")
    lines = dash_lines(rendered)
    if lines:
        raise CaptureError(f"the capture contains an em or en dash on line {lines[0]}")
    return rendered


def parse_arguments(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--replace",
        nargs=2,
        action="append",
        default=[],
        metavar=("VALUE", "LABEL"),
        help="replace VALUE with LABEL everywhere in the capture (repeatable)",
    )
    parser.add_argument("input", nargs="?", help="capture file, standard input when omitted")
    return parser.parse_args(argv)


def main(argv=None):
    arguments = parse_arguments(argv)
    if arguments.input:
        with open(arguments.input, encoding="utf-8", errors="replace") as source:
            ansi_text = source.read()
    else:
        ansi_text = sys.stdin.read()
    try:
        rendered = convert(ansi_text, [tuple(pair) for pair in arguments.replace])
    except CaptureError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    sys.stdout.write(rendered)
    return 0


if __name__ == "__main__":
    sys.exit(main())
