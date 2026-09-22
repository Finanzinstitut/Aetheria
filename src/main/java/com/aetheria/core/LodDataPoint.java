package com.aetheria.core;

/**
 * Bit layout helpers for a single LOD data point.
 *
 * <p>A data point describes one vertical span of visually uniform blocks in a column. Aetheria
 * never keeps block states, block entities or biome objects in memory for distant terrain: a single
 * {@code long} carries everything the renderer needs. This is the main reason the mod's resident
 * set stays flat as the render distance grows &mdash; a fully populated 4096-chunk LOD ring costs a
 * few tens of megabytes rather than gigabytes.
 *
 * <p>Layout (least significant bit first):
 *
 * <pre>
 *   bits  0..11  topY + Y_BIAS        (12 bits, world Y in [-2048, 2047])
 *   bits 12..23  bottomY + Y_BIAS     (12 bits, world Y in [-2048, 2047])
 *   bits 24..47  packed RGB colour    (24 bits, 0xRRGGBB)
 *   bits 48..51  block light          (4 bits, 0..15)
 *   bits 52..55  sky light            (4 bits, 0..15)
 *   bits 56..63  flags                (8 bits, see FLAG_* constants)
 * </pre>
 *
 * <p>The class is a pure static utility; data points are passed around as primitive {@code long}s
 * and stored in {@code long[]} arrays so that no object header is ever paid per data point.
 */
public final class LodDataPoint {

    /** Offset applied to world Y values so they fit into an unsigned 12-bit field. */
    public static final int Y_BIAS = 2048;

    /** Lowest world Y that can be represented. */
    public static final int MIN_Y = -Y_BIAS;

    /** Highest world Y that can be represented. */
    public static final int MAX_Y = Y_BIAS - 1;

    /** Sentinel meaning "nothing here"; an empty data point is simply zero. */
    public static final long EMPTY = 0L;

    /** Set when the data point carries real terrain. Without it the data point is ignored. */
    public static final int FLAG_EXISTS = 1;

    /** Set for water, ice, glass and similar: rendered in the translucent pass. */
    public static final int FLAG_TRANSPARENT = 1 << 1;

    /** Set when the span was produced by merging several vertically adjacent spans. */
    public static final int FLAG_MERGED = 1 << 2;

    private static final int TOP_SHIFT = 0;
    private static final int BOTTOM_SHIFT = 12;
    private static final int COLOR_SHIFT = 24;
    private static final int BLOCK_LIGHT_SHIFT = 48;
    private static final int SKY_LIGHT_SHIFT = 52;
    private static final int FLAGS_SHIFT = 56;

    private static final long Y_MASK = 0xFFFL;
    private static final long COLOR_MASK = 0xFF_FFFFL;
    private static final long LIGHT_MASK = 0xFL;
    private static final long FLAGS_MASK = 0xFFL;

    private LodDataPoint() {
    }

    /**
     * Packs a vertical span into a single data point.
     *
     * @param topY        world Y of the first block <em>above</em> the span's top face
     * @param bottomY     world Y of the span's lowest block
     * @param rgb         packed 0xRRGGBB surface colour, already tinted by biome
     * @param blockLight  emitted light level, 0..15
     * @param skyLight    sky light level, 0..15
     * @param flags       bitwise OR of the {@code FLAG_*} constants
     * @return the packed data point
     * @throws IllegalArgumentException if {@code topY} or {@code bottomY} is out of range, or if
     *                                  the span is inverted
     */
    public static long pack(int topY, int bottomY, int rgb, int blockLight, int skyLight, int flags) {
        if (topY < MIN_Y || topY > MAX_Y) {
            throw new IllegalArgumentException("topY out of range: " + topY);
        }
        if (bottomY < MIN_Y || bottomY > MAX_Y) {
            throw new IllegalArgumentException("bottomY out of range: " + bottomY);
        }
        if (topY < bottomY) {
            throw new IllegalArgumentException("inverted span: topY=" + topY + " bottomY=" + bottomY);
        }
        return ((long) (topY + Y_BIAS) & Y_MASK) << TOP_SHIFT
                | ((long) (bottomY + Y_BIAS) & Y_MASK) << BOTTOM_SHIFT
                | ((long) rgb & COLOR_MASK) << COLOR_SHIFT
                | ((long) clampLight(blockLight)) << BLOCK_LIGHT_SHIFT
                | ((long) clampLight(skyLight)) << SKY_LIGHT_SHIFT
                | ((long) flags & FLAGS_MASK) << FLAGS_SHIFT;
    }

    /** Returns the world Y of the top face of the span. */
    public static int topY(long dataPoint) {
        return (int) ((dataPoint >>> TOP_SHIFT) & Y_MASK) - Y_BIAS;
    }

    /** Returns the world Y of the lowest block of the span. */
    public static int bottomY(long dataPoint) {
        return (int) ((dataPoint >>> BOTTOM_SHIFT) & Y_MASK) - Y_BIAS;
    }

    /** Returns the number of blocks the span covers vertically, at least one. */
    public static int height(long dataPoint) {
        return topY(dataPoint) - bottomY(dataPoint) + 1;
    }

    /** Returns the packed 0xRRGGBB surface colour. */
    public static int rgb(long dataPoint) {
        return (int) ((dataPoint >>> COLOR_SHIFT) & COLOR_MASK);
    }

    /** Returns the emitted light level, 0..15. */
    public static int blockLight(long dataPoint) {
        return (int) ((dataPoint >>> BLOCK_LIGHT_SHIFT) & LIGHT_MASK);
    }

    /** Returns the sky light level, 0..15. */
    public static int skyLight(long dataPoint) {
        return (int) ((dataPoint >>> SKY_LIGHT_SHIFT) & LIGHT_MASK);
    }

    /** Returns the raw flag byte. */
    public static int flags(long dataPoint) {
        return (int) ((dataPoint >>> FLAGS_SHIFT) & FLAGS_MASK);
    }

    /** Returns {@code true} if the given flag bit is set. */
    public static boolean hasFlag(long dataPoint, int flag) {
        return (flags(dataPoint) & flag) != 0;
    }

    /** Returns {@code true} if the data point carries real terrain. */
    public static boolean exists(long dataPoint) {
        return dataPoint != EMPTY && hasFlag(dataPoint, FLAG_EXISTS);
    }

    /** Returns a copy of the data point with a new top and bottom Y. */
    public static long withSpan(long dataPoint, int topY, int bottomY) {
        return pack(topY, bottomY, rgb(dataPoint), blockLight(dataPoint), skyLight(dataPoint),
                flags(dataPoint));
    }

    /** Returns a copy of the data point with a new colour. */
    public static long withRgb(long dataPoint, int rgb) {
        return pack(topY(dataPoint), bottomY(dataPoint), rgb, blockLight(dataPoint),
                skyLight(dataPoint), flags(dataPoint));
    }

    /**
     * Blends {@code count} data points into one, as used when down-sampling a detail level into the
     * next coarser one. Every field is averaged, heights included.
     *
     * <p>Averaging the heights rather than taking the highest is not a detail. Down-sampling runs
     * once per level, so going from block-accurate to one column per chunk applies this four times
     * over 2x2 groups. Under a maximum, a single tall block among the 256 in a chunk wins every
     * round and drags the whole chunk up with it: one tree, one lamp post or one tower turns into a
     * 16x16 pillar standing hundreds of blocks above the landscape. Taking the mean keeps the
     * coarse surface where the terrain actually is, and lets isolated tall features fade out with
     * distance instead of dominating the skyline.
     *
     * @param dataPoints array holding the inputs
     * @param offset     index of the first input
     * @param count      number of inputs to blend
     * @return the blended data point, or {@link #EMPTY} if no input existed
     */
    public static long blend(long[] dataPoints, int offset, int count) {
        int r = 0;
        int g = 0;
        int b = 0;
        int blockLight = 0;
        int skyLight = 0;
        int flags = 0;
        long topSum = 0;
        long bottomSum = 0;
        int n = 0;

        for (int i = offset; i < offset + count; i++) {
            long dataPoint = dataPoints[i];
            if (!exists(dataPoint)) {
                continue;
            }
            int rgb = rgb(dataPoint);
            r += (rgb >>> 16) & 0xFF;
            g += (rgb >>> 8) & 0xFF;
            b += rgb & 0xFF;
            blockLight += blockLight(dataPoint);
            skyLight += skyLight(dataPoint);
            flags |= flags(dataPoint);
            topSum += topY(dataPoint);
            bottomSum += bottomY(dataPoint);
            n++;
        }

        if (n == 0) {
            return EMPTY;
        }
        int rgb = ((r / n) << 16) | ((g / n) << 8) | (b / n);
        // Rounded rather than truncated, so repeated down-sampling does not drift downwards.
        int topY = (int) Math.round((double) topSum / n);
        int bottomY = (int) Math.round((double) bottomSum / n);
        return pack(topY, Math.min(bottomY, topY), rgb, blockLight / n, skyLight / n,
                flags | FLAG_MERGED);
    }

    /** Renders a data point as human-readable text, for logs and debug overlays. */
    public static String toString(long dataPoint) {
        if (!exists(dataPoint)) {
            return "LodDataPoint{empty}";
        }
        return String.format("LodDataPoint{y=%d..%d, rgb=#%06X, block=%d, sky=%d, flags=0x%02X}",
                bottomY(dataPoint), topY(dataPoint), rgb(dataPoint), blockLight(dataPoint),
                skyLight(dataPoint), flags(dataPoint));
    }

    private static int clampLight(int light) {
        return (int) Math.max(0, Math.min(LIGHT_MASK, light));
    }
}
