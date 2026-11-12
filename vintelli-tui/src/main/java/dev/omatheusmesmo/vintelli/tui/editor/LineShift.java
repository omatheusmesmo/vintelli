package dev.omatheusmesmo.vintelli.tui.editor;

/**
 * Maps zero based lines across one text edit, found from the lines the old and new text share at their start and end.
 * Lines before and after the changed region keep their text, so they move by the line-count delta. Inside the changed
 * region only its first line survives, and only when the region still has lines afterwards: a split keeps its anchor
 * on the first part, while every other line of the region is removed.
 */
record LineShift(int prefix, int oldSuffixStart, int delta) {
    static LineShift between(String before, String after) {
        String[] oldLines = before.split("\n", -1);
        String[] newLines = after.split("\n", -1);
        int limit = Math.min(oldLines.length, newLines.length);
        int prefix = 0;
        while (prefix < limit && oldLines[prefix].equals(newLines[prefix])) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < limit - prefix
                && oldLines[oldLines.length - 1 - suffix].equals(newLines[newLines.length - 1 - suffix])) {
            suffix++;
        }
        return new LineShift(prefix, oldLines.length - suffix, newLines.length - oldLines.length);
    }

    /** Returns the line the old line now occupies, or -1 when the edit removed it. */
    int map(int line) {
        if (line < prefix) {
            return line;
        }
        if (line >= oldSuffixStart) {
            return line + delta;
        }
        return line == prefix && oldSuffixStart + delta > prefix ? prefix : -1;
    }
}
