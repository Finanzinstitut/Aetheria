package com.aetheria.core;

/**
 * Decides which detail level a chunk should be rendered at.
 *
 * <p>The base rule is distance: detail drops one level every time the distance from the camera
 * doubles, which keeps the on-screen size of a LOD cell roughly constant and therefore keeps the
 * quad count roughly constant per screen area instead of growing with the square of the render
 * distance.
 *
 * <p>Two dynamic terms are layered on top:
 *
 * <ul>
 *   <li><b>Altitude.</b> Looking down from high up puts far more terrain on screen at once, and the
 *       extra detail is invisible at that scale anyway. Detail is therefore biased coarser as the
 *       camera climbs above the terrain.
 *   <li><b>Speed.</b> While the camera moves quickly, chunks scroll past faster than the eye can
 *       resolve them and faster than the meshing pool can rebuild them at full detail. Biasing
 *       coarser while moving lets the pool stay ahead of the camera, which is what prevents the
 *       stutter normally seen when flying with elytra or riding a fast mount.
 * </ul>
 *
 * <p>Both biases relax smoothly back to zero once the camera settles, so standing still always
 * converges on the best detail the configuration allows.
 *
 * <p>The class is a pure function of its inputs and is safe to call from any thread.
 */
public final class LodDetailPolicy {

    /** Camera altitude above terrain, in blocks, at which the altitude bias reaches one level. */
    public static final double ALTITUDE_PER_LEVEL = 192.0;

    /** Camera speed, in blocks per second, at which the speed bias reaches one level. */
    public static final double SPEED_PER_LEVEL = 24.0;

    /** Distance in chunks at which detail first drops below the finest level. */
    private final int fullDetailRadius;

    /** Finest level the policy is allowed to return, from the user's quality setting. */
    private final LodDetailLevel finestLevel;

    /** How strongly altitude and speed are allowed to coarsen detail, in levels. */
    private final double dynamicBiasLimit;

    /**
     * @param fullDetailRadius distance in chunks that still renders at {@code finestLevel}
     * @param finestLevel      best detail level the policy may return
     * @param dynamicBiasLimit maximum number of levels the dynamic terms may add; zero disables
     *                         dynamic scaling entirely
     */
    public LodDetailPolicy(int fullDetailRadius, LodDetailLevel finestLevel, double dynamicBiasLimit) {
        this.fullDetailRadius = Math.max(1, fullDetailRadius);
        this.finestLevel = finestLevel;
        this.dynamicBiasLimit = Math.max(0.0, dynamicBiasLimit);
    }

    /**
     * Returns the detail level to use for a chunk.
     *
     * @param chunkDistance     distance from the camera to the chunk, in chunks
     * @param altitudeAboveTerrain camera height above the terrain below it, in blocks; negative
     *                             values (underground) are treated as zero
     * @param speedBlocksPerSecond magnitude of the camera velocity, in blocks per second
     * @return the level to mesh and render the chunk at
     */
    public LodDetailLevel levelFor(double chunkDistance, double altitudeAboveTerrain,
                                   double speedBlocksPerSecond) {
        int shift = finestLevel.shift() + distanceBias(chunkDistance)
                + (int) Math.floor(dynamicBias(altitudeAboveTerrain, speedBlocksPerSecond));
        return LodDetailLevel.ofShift(shift);
    }

    /**
     * Returns the combined altitude and speed bias in levels, clamped to the configured limit.
     * Exposed separately so the debug overlay can show why detail is currently reduced.
     */
    public double dynamicBias(double altitudeAboveTerrain, double speedBlocksPerSecond) {
        double altitude = Math.max(0.0, altitudeAboveTerrain) / ALTITUDE_PER_LEVEL;
        double speed = Math.max(0.0, speedBlocksPerSecond) / SPEED_PER_LEVEL;
        return Math.min(dynamicBiasLimit, altitude + speed);
    }

    /** Returns how many levels the distance term alone coarsens detail by. */
    public int distanceBias(double chunkDistance) {
        if (chunkDistance <= fullDetailRadius) {
            return 0;
        }
        // log2(distance / fullDetailRadius), i.e. one level per doubling of distance.
        double ratio = chunkDistance / fullDetailRadius;
        return (int) Math.floor(Math.log(ratio) / Math.log(2.0)) + 1;
    }

    public int fullDetailRadius() {
        return fullDetailRadius;
    }

    public LodDetailLevel finestLevel() {
        return finestLevel;
    }

    public double dynamicBiasLimit() {
        return dynamicBiasLimit;
    }
}
