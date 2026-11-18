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
 * {@code Color.indexedToRgb} reports them. The scan keeps the first strict
 * minimum, so a tie stays on the lower index.
 *
 * <p>Distance is the Euclidean distance in Oklab (Bjorn Ottosson, 2020), a
 * perceptual space in which a blue keeps its hue as its lightness changes.
 * The squared sRGB distance it replaces weighs every channel alike, so for the
 * accent blue {@code #3574F0} it kept the red of the cube step 95 and chose
 * index 69, {@code #5F87FF}, which reads as lavender; Oklab chooses index 33,
 * {@code #0087FF} (issue #458 asked for the 256 color rendering to stay blue).
 * CIELAB was rejected because it bends saturated blues toward purple, the
 * defect this fixes.
 */
public final class PaletteDowngrade {

    static final int LOWEST_INDEX = 16;
    static final int HIGHEST_INDEX = 255;

    private static final double[][] PALETTE = palette();

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
        double[] target = oklab(r, g, b);
        int nearest = LOWEST_INDEX;
        double nearestDistance = Double.MAX_VALUE;
        for (int index = LOWEST_INDEX; index <= HIGHEST_INDEX; index++) {
            double distance = distance(PALETTE[index - LOWEST_INDEX], target);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = index;
            }
        }
        return nearest;
    }

    private static double distance(double[] candidate, double[] target) {
        double dl = candidate[0] - target[0];
        double da = candidate[1] - target[1];
        double db = candidate[2] - target[2];
        return dl * dl + da * da + db * db;
    }

    /**
     * The Oklab coordinates of an sRGB triple, with the matrices of the reference implementation.
     */
    static double[] oklab(int r, int g, int b) {
        double red = linear(r);
        double green = linear(g);
        double blue = linear(b);
        double l = Math.cbrt(0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue);
        double m = Math.cbrt(0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue);
        double s = Math.cbrt(0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue);
        return new double[] {
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s};
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static double[][] palette() {
        double[][] lab = new double[HIGHEST_INDEX - LOWEST_INDEX + 1][];
        for (int index = LOWEST_INDEX; index <= HIGHEST_INDEX; index++) {
            Rgb value = Color.indexedToRgb(index);
            lab[index - LOWEST_INDEX] = oklab(value.r(), value.g(), value.b());
        }
        return lab;
    }
}
