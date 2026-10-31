package dev.omatheusmesmo.vintelli.tui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Color.Rgb;

/**
 * Maps a palette value onto the 256 color palette for terminals without
 * truecolor, as {@code Color.indexed(n)}.
 *
 * <p>Candidates are the indices 16 to 255 only: 0 to 15 are the terminal's own
 * palette, so one index renders differently per terminal, while the cube from
 * 16 and the grayscale ramp from 232 are fixed by the xterm specification and
 * {@code Color.indexedToRgb} reports them. Distance is the sum of the squared
 * channel differences (Euclidean); the scan keeps the first strict minimum, so
 * a tie stays on the lower index. The squared distance is the metric that
 * reproduces every index the README table documents; Chebyshev and Manhattan
 * do not.
 *
 * <p>The three README rows whose documented index is below 16 are recomputed
 * here: white leaves 15 for 231, {@code #3574F0} leaves 12 for 69 and
 * {@code #7A7E85} leaves 8 for 244.
 */
public final class PaletteDowngrade {

    static final int LOWEST_INDEX = 16;
    static final int HIGHEST_INDEX = 255;

    private static final int[][] PALETTE = palette();

    private PaletteDowngrade() {
    }

    /**
     * The nearest fixed palette entry of {@code color} as {@code Color.indexed}.
     */
    public static Color nearest(Color color) {
        Rgb rgb = color.toRgb();
        return Color.indexed(nearestIndex(rgb.r(), rgb.g(), rgb.b()));
    }

    /**
     * The index of the nearest entry of the fixed palette for a channel triple.
     */
    static int nearestIndex(int r, int g, int b) {
        int nearest = LOWEST_INDEX;
        long nearestDistance = Long.MAX_VALUE;
        for (int index = LOWEST_INDEX; index <= HIGHEST_INDEX; index++) {
            long distance = distance(PALETTE[index - LOWEST_INDEX], r, g, b);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = index;
            }
        }
        return nearest;
    }

    private static long distance(int[] rgb, int r, int g, int b) {
        long dr = r - rgb[0];
        long dg = g - rgb[1];
        long db = b - rgb[2];
        return dr * dr + dg * dg + db * db;
    }

    private static int[][] palette() {
        int[][] rgb = new int[HIGHEST_INDEX - LOWEST_INDEX + 1][];
        for (int index = LOWEST_INDEX; index <= HIGHEST_INDEX; index++) {
            Rgb value = Color.indexedToRgb(index);
            rgb[index - LOWEST_INDEX] = new int[] {value.r(), value.g(), value.b()};
        }
        return rgb;
    }
}
