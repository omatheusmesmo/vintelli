package dev.omatheusmesmo.vintelli.tui;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import dev.tamboui.text.CharWidth;

/**
 * Fits the status bar into one row. The left group is the breadcrumb, the
 * right group is the message and the editor widgets, and the two groups never
 * overlap. When the row is too narrow the lowest priority segment goes first:
 * the leftmost breadcrumb ancestors, then the least important widgets. The
 * message gives up its width before any other segment is dropped, and it
 * shortens to its short text before it is clipped.
 */
public final class StatusBarLayout {

    public static final String MESSAGE_ID = "message";

    private static final String ELLIPSIS = "…";
    private static final int GROUP_GAP = 2;
    private static final int MESSAGE_MIN_WIDTH = 6;

    /**
     * One status bar segment. A higher priority survives longer when the row is
     * narrow; {@code shortText} replaces {@code text} before the segment is
     * dropped or clipped.
     */
    public record Segment(String id, int priority, String text, String shortText) {

        public Segment {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(text, "text");
            shortText = shortText == null ? text : shortText;
        }

        public static Segment of(String id, int priority, String text) {
            return new Segment(id, priority, text, text);
        }
    }

    /** One drawn segment at its cell column. */
    public record Placement(String id, int x, String text) {

        public int right() {
            return x + CharWidth.of(text);
        }
    }

    /** The placed segments of one row. */
    public record Line(List<Placement> placements, int width) {

        public Line {
            placements = List.copyOf(placements);
        }

        /** The row as plain text, with blanks where nothing is drawn. */
        public String text() {
            StringBuilder row = new StringBuilder();
            int cells = 0;
            for (Placement placement : placements) {
                row.append(" ".repeat(Math.max(0, placement.x() - cells))).append(placement.text());
                cells = Math.max(cells, placement.right());
            }
            return row.toString();
        }
    }

    private StatusBarLayout() {
    }

    /**
     * Lays out the breadcrumb {@code left} and the widgets {@code right} in a row of
     * {@code width} cells. The breadcrumb segments are joined with {@code divider}
     * and the widgets with {@code gap}. The message segment shrinks in place, and
     * the leftmost breadcrumb segments are replaced by an ellipsis when they go.
     */
    public static Line layout(List<Segment> left, String divider, List<Segment> right, String gap, int width) {
        if (width <= 0) {
            return new Line(List.of(), Math.max(0, width));
        }
        Set<String> dropped = new HashSet<>();
        while (minimumWidth(left, divider, right, gap, dropped) > width) {
            Segment victim = lowestPriority(left, right, dropped);
            if (victim == null) {
                break;
            }
            dropped.add(victim.id());
        }
        List<Segment> keptLeft = kept(left, dropped);
        List<Segment> keptRight = kept(right, dropped);
        String leftPrefix = keptLeft.size() < left.size() && !keptLeft.isEmpty() ? ELLIPSIS + divider : "";
        int leftWidth = segmentsWidth(keptLeft, divider) + CharWidth.of(leftPrefix);
        int groupGap = keptLeft.isEmpty() || keptRight.isEmpty() ? 0 : GROUP_GAP;
        int messageRoom = width - leftWidth - groupGap - othersWidth(keptRight) - gapWidth(keptRight.size(), gap);
        List<Segment> rightTexts = new ArrayList<>();
        for (Segment segment : keptRight) {
            if (!MESSAGE_ID.equals(segment.id())) {
                rightTexts.add(segment);
                continue;
            }
            String message = fitMessage(segment, messageRoom);
            if (!message.isEmpty()) {
                rightTexts.add(Segment.of(MESSAGE_ID, segment.priority(), message));
            }
        }
        List<Placement> placements = new ArrayList<>();
        int x = 0;
        if (!leftPrefix.isEmpty()) {
            placements.add(new Placement("", x, leftPrefix));
            x += CharWidth.of(leftPrefix);
        }
        for (int i = 0; i < keptLeft.size(); i++) {
            if (i > 0) {
                placements.add(new Placement("", x, divider));
                x += CharWidth.of(divider);
            }
            Segment segment = keptLeft.get(i);
            placements.add(new Placement(segment.id(), x, segment.text()));
            x += CharWidth.of(segment.text());
        }
        int rightWidth = segmentsWidth(rightTexts, gap);
        int rightStart = Math.max(x + groupGap, width - rightWidth);
        int cursor = rightStart;
        for (int i = 0; i < rightTexts.size(); i++) {
            if (i > 0) {
                placements.add(new Placement("", cursor, gap));
                cursor += CharWidth.of(gap);
            }
            Segment segment = rightTexts.get(i);
            placements.add(new Placement(segment.id(), cursor, segment.text()));
            cursor += CharWidth.of(segment.text());
        }
        return new Line(placements, width);
    }

    /**
     * Clips {@code value} to {@code width} cells, ending with an ellipsis when it
     * is cut, and never splits a wide or combining character.
     */
    public static String truncate(String value, int width) {
        if (width <= 0) {
            return "";
        }
        if (cellWidth(value) <= width) {
            return value;
        }
        int ellipsisWidth = cellWidth(ELLIPSIS);
        if (ellipsisWidth > width) {
            return "";
        }
        BreakIterator graphemes = BreakIterator.getCharacterInstance(Locale.ROOT);
        graphemes.setText(value);
        StringBuilder prefix = new StringBuilder();
        int prefixWidth = 0;
        int start = graphemes.first();
        for (int end = graphemes.next(); end != BreakIterator.DONE; start = end, end = graphemes.next()) {
            String cluster = value.substring(start, end);
            int clusterWidth = cellWidth(cluster);
            if (prefixWidth + clusterWidth + ellipsisWidth > width) {
                break;
            }
            prefix.append(cluster);
            prefixWidth += clusterWidth;
        }
        return prefix.append(ELLIPSIS).toString();
    }

    private static int minimumWidth(List<Segment> left, String divider, List<Segment> right, String gap,
            Set<String> dropped) {
        List<Segment> keptLeft = kept(left, dropped);
        List<Segment> keptRight = kept(right, dropped);
        int leftWidth = keptLeft.isEmpty() ? 0 : segmentsWidth(keptLeft, divider)
                + (keptLeft.size() < left.size() ? CharWidth.of(ELLIPSIS + divider) : 0);
        int rightWidth = 0;
        for (Segment segment : keptRight) {
            rightWidth += MESSAGE_ID.equals(segment.id())
                    ? Math.min(cellWidth(segment.shortText()), MESSAGE_MIN_WIDTH)
                    : cellWidth(segment.text());
        }
        rightWidth += gapWidth(keptRight.size(), gap);
        return leftWidth + (keptLeft.isEmpty() || keptRight.isEmpty() ? 0 : GROUP_GAP) + rightWidth;
    }

    private static Segment lowestPriority(List<Segment> left, List<Segment> right, Set<String> dropped) {
        Segment lowest = null;
        for (Segment segment : concat(left, right)) {
            if (dropped.contains(segment.id())) {
                continue;
            }
            if (lowest == null || segment.priority() < lowest.priority()) {
                lowest = segment;
            }
        }
        return lowest;
    }

    private static String fitMessage(Segment message, int room) {
        if (room <= 0) {
            return "";
        }
        if (cellWidth(message.text()) <= room) {
            return message.text();
        }
        if (cellWidth(message.shortText()) <= room) {
            return message.shortText();
        }
        return truncate(message.shortText(), room);
    }

    private static List<Segment> kept(List<Segment> segments, Set<String> dropped) {
        return segments.stream().filter(segment -> !dropped.contains(segment.id())).toList();
    }

    private static List<Segment> concat(List<Segment> first, List<Segment> second) {
        List<Segment> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static int segmentsWidth(List<Segment> segments, String separator) {
        int width = 0;
        for (Segment segment : segments) {
            width += cellWidth(segment.text());
        }
        return width + gapWidth(segments.size(), separator);
    }

    private static int othersWidth(List<Segment> segments) {
        int width = 0;
        for (Segment segment : segments) {
            if (!MESSAGE_ID.equals(segment.id())) {
                width += cellWidth(segment.text());
            }
        }
        return width;
    }

    private static int gapWidth(int count, String separator) {
        return count <= 1 ? 0 : (count - 1) * cellWidth(separator);
    }

    private static int cellWidth(String value) {
        return CharWidth.of(value);
    }
}
