package com.aetheria.core;

/**
 * Precomputed brightness factors for every combination of face direction and light level.
 *
 * <p>Shading a LOD quad means combining a fixed per-face factor with its block and sky light. Done
 * inline that is a branch, two integer-to-float divisions and a pair of comparisons <em>per quad,
 * per frame</em> &mdash; and the far ring submits tens of thousands of quads a frame, so it is one
 * of the few pieces of arithmetic in the mod that is genuinely hot.
 *
 * <p>The inputs, however, are tiny: five face directions and 256 packed light values. The whole
 * space is {@value #SIZE} floats, roughly 5 KB, so it is computed once into a flat table and read
 * back with a single array access. The table only depends on the sky brightness, which the game
 * reports as an integer between 0 and 15, so it is rebuilt a handful of times per in-game day
 * rather than per frame.
 *
 * <p>{@link #factorFor(byte, byte, float)} is the reference implementation the table is built from
 * and is kept so the two can be checked against each other.
 *
 * <p>Instances are not thread safe; the renderer owns one and uses it from the render thread.
 */
public final class LodShadeTable {

    /** Face directions the table covers: the five {@code LodMesh.FACE_*} values. */
    public static final int FACE_COUNT = 5;

    /** Distinct packed light bytes. */
    public static final int LIGHT_COUNT = 256;

    /** Total number of entries. */
    public static final int SIZE = FACE_COUNT * LIGHT_COUNT;

    /** Highest sky level the game reports. */
    public static final int MAX_SKY_LEVEL = 15;

    /** Per-face brightness, matching the shading vanilla applies to block faces. */
    private static final float SHADE_UP = 1.0f;
    private static final float SHADE_NORTH_SOUTH = 0.8f;
    private static final float SHADE_EAST_WEST = 0.6f;

    /** Lowest brightness a fully unlit surface is drawn at, so caves are dim rather than black. */
    private static final float MIN_BRIGHTNESS = 0.15f;

    private final float[] factors = new float[SIZE];

    /** Sky level the table currently holds; -1 until the first build. */
    private int skyLevel = -1;

    /**
     * Rebuilds the table if the sky level changed.
     *
     * @param newSkyLevel sky brightness as the game reports it, 0 (night) to 15 (noon)
     * @return {@code true} if the table was rebuilt
     */
    public boolean update(int newSkyLevel) {
        int clamped = Math.max(0, Math.min(MAX_SKY_LEVEL, newSkyLevel));
        if (clamped == skyLevel) {
            return false;
        }
        skyLevel = clamped;

        float skyBrightness = (float) clamped / MAX_SKY_LEVEL;
        for (int face = 0; face < FACE_COUNT; face++) {
            for (int light = 0; light < LIGHT_COUNT; light++) {
                factors[face * LIGHT_COUNT + light] =
                        factorFor((byte) face, (byte) light, skyBrightness);
            }
        }
        return true;
    }

    /** Returns the sky level the table was last built for, or -1 if never built. */
    public int skyLevel() {
        return skyLevel;
    }

    /**
     * Returns the brightness factor for a quad.
     *
     * <p>Out-of-range faces fall back to the unshaded factor rather than throwing: this runs once
     * per quad per frame and must never be the thing that takes a frame down.
     */
    public float factor(byte face, byte packedLight) {
        int index = face * LIGHT_COUNT + (packedLight & 0xFF);
        return index >= 0 && index < SIZE ? factors[index] : SHADE_UP;
    }

    /**
     * Computes a brightness factor directly, without the table.
     *
     * <p>Sky light is scaled by the time of day while block light is not, and the brighter of the
     * two wins, which mirrors how the vanilla lightmap combines them.
     */
    public static float factorFor(byte face, byte packedLight, float skyBrightness) {
        float shade = switch (face) {
            case LodMesh.FACE_NORTH, LodMesh.FACE_SOUTH -> SHADE_NORTH_SOUTH;
            case LodMesh.FACE_EAST, LodMesh.FACE_WEST -> SHADE_EAST_WEST;
            default -> SHADE_UP;
        };

        float sky = LodMesh.skyLightOf(packedLight) / 15.0f * clamp01(skyBrightness);
        float block = LodMesh.blockLightOf(packedLight) / 15.0f;
        return shade * Math.max(MIN_BRIGHTNESS, Math.max(sky, block));
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
