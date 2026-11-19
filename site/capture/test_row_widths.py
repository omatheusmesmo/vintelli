import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import row_widths  # noqa: E402


class RowWidthsTest(unittest.TestCase):
    def test_rectangular_screen_passes(self):
        fragment = '<pre class="vintelli-capture"><span style="color:#fff">ab</span>c\nd&amp;e\n</pre>\n'
        self.assertEqual([], row_widths.ragged_rows(fragment))

    def test_row_without_its_trailing_spaces_is_reported(self):
        fragment = '<pre class="vintelli-capture">abc \n<span style="color:#fff">ab</span>\nabcd</pre>\n'
        self.assertEqual([2], row_widths.ragged_rows(fragment))

    def test_entities_and_icons_count_as_one_cell(self):
        fragment = '<pre class="vintelli-capture">&lt;\n&#123;}</pre>\n'
        self.assertEqual([], row_widths.ragged_rows(fragment))


if __name__ == "__main__":
    unittest.main()
