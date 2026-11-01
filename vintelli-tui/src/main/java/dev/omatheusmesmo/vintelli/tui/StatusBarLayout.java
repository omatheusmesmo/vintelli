package dev.omatheusmesmo.vintelli.tui;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.text.BreakIterator;
import java.util.Locale;

import dev.omatheusmesmo.vintelli.api.statusbar.StatusBarWidget;
import dev.tamboui.text.Text;

/** Pure ordering and width fitting for status bar widgets. */
public final class StatusBarLayout {

    private static final String SEPARATOR = " │ ";
    private static final String ELLIPSIS = "…";
    private static final int COMPACT_BRANCH_WIDTH = 9;

    private StatusBarLayout() {
    }

    public static String render(List<StatusBarWidget> widgets, int width) {
        if (width <= 0 || widgets.isEmpty()) {
            return "";
        }
        List<StatusBarWidget> ordered = widgets.stream()
                .sorted(Comparator.comparingInt(StatusBarWidget::order).thenComparing(StatusBarWidget::id))
                .toList();
        List<String> visible = new ArrayList<>(ordered.size());
        int compactWidth = Math.max(0, ordered.size() - 1) * cellWidth(SEPARATOR);
        for (StatusBarWidget widget : ordered) {
            String compactText = compactText(widget);
            visible.add(compactText);
            compactWidth += cellWidth(compactText);
        }
        if (compactWidth > width) {
            return compactLine(visible, width);
        }
        int spare = width - compactWidth;
        for (int i = 0; i < ordered.size(); i++) {
            StatusBarWidget widget = ordered.get(i);
            int upgradeCost = cellWidth(widget.text()) - cellWidth(compactText(widget));
            if (upgradeCost > 0 && upgradeCost <= spare) {
                visible.set(i, widget.text());
                spare -= upgradeCost;
            }
        }
        return String.join(SEPARATOR, visible);
    }

    private static String compactLine(List<String> widgets, int width) {
        StringBuilder result = new StringBuilder();
        for (String widget : widgets) {
            String separator = result.isEmpty() ? "" : SEPARATOR;
            int remaining = width - cellWidth(result.toString()) - cellWidth(separator);
            if (remaining <= 0) {
                break;
            }
            String selected = cellWidth(widget) <= remaining ? widget : truncate(widget, remaining);
            result.append(separator).append(selected);
            if (cellWidth(selected) < cellWidth(widget)) {
                break;
            }
        }
        return result.toString();
    }

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

    private static int cellWidth(String value) {
        return Text.raw(value).width();
    }

    private static String compactText(StatusBarWidget widget) {
        if ("vcs-branch".equals(widget.id())) {
            return truncate(widget.compactText(), COMPACT_BRANCH_WIDTH);
        }
        return widget.compactText();
    }
}
