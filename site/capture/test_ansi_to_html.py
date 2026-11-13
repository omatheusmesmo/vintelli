import io
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ansi_to_html  # noqa: E402

ESC = "\x1b["
SPAN_OPEN = '<span style="'


def span_style(rendered, text):
    end = rendered.index(f">{text}</span>")
    value_start = rendered.rindex(SPAN_OPEN, 0, end) + len(SPAN_OPEN)
    return rendered[value_start:rendered.index('"', value_start)]


class BasicColorTest(unittest.TestCase):
    def test_basic_foreground_uses_xterm_palette(self):
        rendered = ansi_to_html.convert(f"{ESC}31mred{ESC}0m")
        self.assertIn("color:#cd0000", span_style(rendered, "red"))

    def test_bright_foreground_uses_upper_palette(self):
        rendered = ansi_to_html.convert(f"{ESC}91mbright{ESC}0m")
        self.assertIn("color:#ff0000", span_style(rendered, "bright"))

    def test_basic_background(self):
        rendered = ansi_to_html.convert(f"{ESC}44mblue{ESC}0m")
        self.assertIn("background-color:#0000ee", span_style(rendered, "blue"))

    def test_bright_background(self):
        rendered = ansi_to_html.convert(f"{ESC}103mbg{ESC}0m")
        self.assertIn("background-color:#ffff00", span_style(rendered, "bg"))

    def test_256_color_cube_and_gray_ramp(self):
        cube = ansi_to_html.convert(f"{ESC}38;5;196mcube{ESC}0m")
        gray = ansi_to_html.convert(f"{ESC}48;5;232mgray{ESC}0m")
        self.assertIn("color:#ff0000", span_style(cube, "cube"))
        self.assertIn("background-color:#080808", span_style(gray, "gray"))

    def test_24_bit_foreground_and_background(self):
        rendered = ansi_to_html.convert(f"{ESC}38;2;1;2;3;48;2;250;251;252mtrue{ESC}0m")
        style = span_style(rendered, "true")
        self.assertIn("color:#010203", style)
        self.assertIn("background-color:#fafbfc", style)

    def test_default_color_reset(self):
        rendered = ansi_to_html.convert(f"{ESC}31mred{ESC}39mplain")
        self.assertIn(f'{SPAN_OPEN}color:#cd0000">red</span>plain', rendered)

    def test_malformed_extended_color_is_ignored(self):
        rendered = ansi_to_html.convert(f"{ESC}38;5mtext{ESC}0m")
        self.assertTrue(rendered.endswith(">text</pre>\n"))


class AttributeTest(unittest.TestCase):
    def test_bold_dim_italic_underline_strike(self):
        raw = (
            f"{ESC}1mb{ESC}0m{ESC}2md{ESC}0m{ESC}3mi{ESC}0m"
            f"{ESC}4mu{ESC}0m{ESC}9ms{ESC}0m"
        )
        rendered = ansi_to_html.convert(raw)
        self.assertIn("font-weight:bold", span_style(rendered, "b"))
        self.assertIn("opacity:0.6", span_style(rendered, "d"))
        self.assertIn("font-style:italic", span_style(rendered, "i"))
        self.assertIn("text-decoration:underline", span_style(rendered, "u"))
        self.assertIn("text-decoration:line-through", span_style(rendered, "s"))

    def test_reverse_swaps_default_colors(self):
        rendered = ansi_to_html.convert(f"{ESC}7mr{ESC}0m")
        style = span_style(rendered, "r")
        self.assertIn("color:#1e1f22", style)
        self.assertIn("background-color:#d4d4d4", style)

    def test_reverse_swaps_explicit_colors(self):
        rendered = ansi_to_html.convert(f"{ESC}31;42;7mr{ESC}0m")
        style = span_style(rendered, "r")
        self.assertIn("color:#00cd00", style)
        self.assertIn("background-color:#cd0000", style)

    def test_attribute_off_code_clears_only_that_attribute(self):
        rendered = ansi_to_html.convert(f"{ESC}1;4mboth{ESC}24mbold{ESC}0m")
        self.assertIn("text-decoration:underline", span_style(rendered, "both"))
        bold_only = span_style(rendered, "bold")
        self.assertIn("font-weight:bold", bold_only)
        self.assertNotIn("text-decoration", bold_only)

    def test_reset_clears_attributes_for_following_text(self):
        rendered = ansi_to_html.convert(f"{ESC}1;31mstyled{ESC}0mafter")
        self.assertIn("</span>after</pre>", rendered)

    def test_non_sgr_sequences_are_removed(self):
        rendered = ansi_to_html.convert(f"\x1b[2J\x1b[?25l\x1b]0;title\x07{ESC}32mok{ESC}0m")
        self.assertNotIn("\x1b", rendered)
        self.assertIn("color:#00cd00", span_style(rendered, "ok"))


class EscapingTest(unittest.TestCase):
    def test_html_is_escaped(self):
        rendered = ansi_to_html.convert("<script>alert('x') & \"y\"</script>")
        self.assertNotIn("<script>", rendered)
        self.assertIn(
            "&lt;script&gt;alert(&#x27;x&#x27;) &amp; &quot;y&quot;&lt;/script&gt;",
            rendered,
        )

    def test_braces_are_entity_encoded_for_qute(self):
        rendered = ansi_to_html.convert("fn() { return x; }")
        self.assertNotIn("{", rendered)
        self.assertNotIn("}", rendered)
        self.assertIn("&#123; return x; &#125;", rendered)

    def test_output_is_a_pre_fragment_without_document_wrapper(self):
        rendered = ansi_to_html.convert("hello")
        self.assertTrue(rendered.startswith('<pre class="vintelli-capture"'))
        self.assertNotIn("<html", rendered)


class ScrubTest(unittest.TestCase):
    def test_scrub_replaces_home_and_workspace(self):
        raw = f"{ESC}36m/home/alice/work/repo{ESC}0m and /tmp/vintelli-site.abc/repo"
        rendered = ansi_to_html.convert(
            raw,
            [("/home/alice", "<home>"), ("/tmp/vintelli-site.abc", "<workspace>")],
        )
        self.assertNotIn("/home/alice", rendered)
        self.assertNotIn("/tmp/vintelli-site.abc", rendered)
        self.assertIn("&lt;home&gt;/work/repo", rendered)
        self.assertIn("&lt;workspace&gt;/repo", rendered)

    def test_value_split_by_color_codes_is_reported_as_leftover(self):
        raw = f"/home/{ESC}31malice{ESC}0m/x"
        with self.assertRaisesRegex(ansi_to_html.CaptureError, "still appear"):
            ansi_to_html.convert(raw, [("/home/alice", "<home>")])

    def test_short_scrub_value_is_rejected(self):
        with self.assertRaisesRegex(ansi_to_html.CaptureError, "too short"):
            ansi_to_html.convert("text", [("/", "<root>")])


class DashTest(unittest.TestCase):
    def test_em_dash_fails_with_line_number(self):
        with self.assertRaisesRegex(ansi_to_html.CaptureError, "em or en dash on line 2"):
            ansi_to_html.convert("first\nsecond \u2014 bad")

    def test_en_dash_fails(self):
        with self.assertRaisesRegex(ansi_to_html.CaptureError, "em or en dash"):
            ansi_to_html.convert(f"2020{ESC}31m\u2013{ESC}0m2025")

    def test_hyphen_and_colon_pass(self):
        rendered = ansi_to_html.convert("2020-2025: fine")
        self.assertIn("2020-2025: fine", rendered)


class EmptyCaptureTest(unittest.TestCase):
    def test_blank_capture_fails(self):
        with self.assertRaisesRegex(ansi_to_html.CaptureError, "empty"):
            ansi_to_html.convert("   \n  \n")

    def test_capture_of_only_sequences_fails(self):
        with self.assertRaisesRegex(ansi_to_html.CaptureError, "empty"):
            ansi_to_html.convert(f"{ESC}0m{ESC}2J")


class CommandLineTest(unittest.TestCase):
    def run_main(self, arguments):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = ansi_to_html.main(arguments)
        return code, out.getvalue(), err.getvalue()

    def write_capture(self, directory, content):
        source = os.path.join(directory, "pane.ansi")
        with open(source, "w", encoding="utf-8") as handle:
            handle.write(content)
        return source

    def test_converts_file_and_scrubs_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            source = self.write_capture(directory, f"{ESC}32m/home/alice/project{ESC}0m\n")
            code, out, err = self.run_main(["--replace", "/home/alice", "<home>", source])
        self.assertEqual(code, 0, err)
        self.assertIn("&lt;home&gt;/project", out)
        self.assertNotIn("alice", out)

    def test_empty_file_exits_non_zero_with_message(self):
        with tempfile.TemporaryDirectory() as directory:
            code, out, err = self.run_main([self.write_capture(directory, "\n")])
        self.assertEqual(code, 1)
        self.assertEqual(out, "")
        self.assertIn("empty", err)

    def test_dash_exits_non_zero(self):
        with tempfile.TemporaryDirectory() as directory:
            code, _, err = self.run_main([self.write_capture(directory, "bad \u2013 dash\n")])
        self.assertEqual(code, 1)
        self.assertIn("dash", err)


if __name__ == "__main__":
    unittest.main()
