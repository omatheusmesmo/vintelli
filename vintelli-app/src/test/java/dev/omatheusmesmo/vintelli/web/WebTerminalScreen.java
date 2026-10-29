package dev.omatheusmesmo.vintelli.web;

import java.util.Arrays;

/**
 * Minimal screen model of the browser terminal stream: it applies the cursor
 * positioning, erase and text sequences a TamboUI diff frame uses, so a test
 * can read the rendered rows the way a terminal would show them. Styles,
 * modes and scroll sequences are consumed and ignored, and the raw stream is
 * kept for assertions on the sequences themselves.
 */
final class WebTerminalScreen {

    private static final int ROWS = 200;
    private static final int COLUMNS = 400;
    private static final char ESCAPE = '\u001b';

    private final char[][] cells = new char[ROWS][COLUMNS];
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder raw = new StringBuilder();
    private int row;
    private int column;

    WebTerminalScreen() {
        clearAll();
    }

    synchronized void write(String text) {
        raw.append(text);
        pending.append(text);
        int index = 0;
        while (index < pending.length()) {
            char current = pending.charAt(index);
            if (current != ESCAPE) {
                printable(current);
                index++;
                continue;
            }
            if (index + 1 >= pending.length()) {
                break;
            }
            if (pending.charAt(index + 1) != '[') {
                index += 2;
                continue;
            }
            int end = index + 2;
            while (end < pending.length() && !isFinalByte(pending.charAt(end))) {
                end++;
            }
            if (end >= pending.length()) {
                break;
            }
            sequence(pending.substring(index + 2, end), pending.charAt(end));
            index = end + 1;
        }
        pending.delete(0, index);
    }

    synchronized String text() {
        StringBuilder text = new StringBuilder();
        int last = lastContentRow();
        for (int line = 0; line <= last; line++) {
            if (line > 0) {
                text.append('\n');
            }
            text.append(rowText(line).stripTrailing());
        }
        return text.toString();
    }

    synchronized int lastContentRow() {
        for (int line = ROWS - 1; line >= 0; line--) {
            if (!rowText(line).isBlank()) {
                return line;
            }
        }
        return -1;
    }

    synchronized String raw() {
        return raw.toString();
    }

    private void printable(char character) {
        if (character == '\r') {
            column = 0;
        } else if (character == '\n') {
            row = Math.min(ROWS - 1, row + 1);
        } else if (row < ROWS && column < COLUMNS) {
            cells[row][column] = character;
            column++;
        }
    }

    private void sequence(String parameters, char finalByte) {
        switch (finalByte) {
            case 'H' -> {
                int[] position = numbers(parameters, 1, 1);
                row = clamp(position[0] - 1, ROWS - 1);
                column = clamp(position[1] - 1, COLUMNS - 1);
            }
            case 'J' -> clearAll();
            case 'K' -> Arrays.fill(cells[row], column, COLUMNS, ' ');
            default -> {
            }
        }
    }

    private void clearAll() {
        for (char[] line : cells) {
            Arrays.fill(line, ' ');
        }
    }

    private String rowText(int line) {
        char[] content = cells[line];
        return new String(content).replace('\0', ' ');
    }

    private static int[] numbers(String parameters, int defaultRow, int defaultColumn) {
        String[] parts = parameters.replace("?", "").split(";", -1);
        int first = parts.length > 0 && !parts[0].isEmpty() ? Integer.parseInt(parts[0]) : defaultRow;
        int second = parts.length > 1 && !parts[1].isEmpty() ? Integer.parseInt(parts[1]) : defaultColumn;
        return new int[] {first, second};
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }

    private static boolean isFinalByte(char character) {
        return character >= 0x40 && character <= 0x7e;
    }
}
