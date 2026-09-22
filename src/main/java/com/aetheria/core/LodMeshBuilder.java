package com.aetheria.core;

import java.util.Arrays;

/**
 * Growable scratch space that accumulates quads and freezes them into a {@link LodMesh}.
 *
 * <p>A builder is expensive to allocate and cheap to reset, so the meshing pool keeps one per
 * worker thread and reuses it for every chunk that thread processes. Over a session this removes
 * essentially all of the allocation churn that LOD meshing would otherwise cause.
 *
 * <p>Instances are not thread safe and are never shared between threads.
 */
public final class LodMeshBuilder {

    private static final int DEFAULT_QUAD_CAPACITY = 256;

    private float[] positions;
    private int[] colors;
    private byte[] light;
    private byte[] facing;
    private int quadCount;
    private boolean translucent;
    private float minY = Float.MAX_VALUE;
    private float maxY = -Float.MAX_VALUE;

    public LodMeshBuilder() {
        this(DEFAULT_QUAD_CAPACITY);
    }

    public LodMeshBuilder(int initialQuadCapacity) {
        int capacity = Math.max(1, initialQuadCapacity);
        this.positions = new float[capacity * LodMesh.FLOATS_PER_QUAD];
        this.colors = new int[capacity];
        this.light = new byte[capacity];
        this.facing = new byte[capacity];
    }

    /** Drops every accumulated quad without releasing the backing arrays. */
    public void reset() {
        quadCount = 0;
        translucent = false;
        minY = Float.MAX_VALUE;
        maxY = -Float.MAX_VALUE;
    }

    public int quadCount() {
        return quadCount;
    }

    /** Marks the whole mesh as translucent; set when meshing the water pass. */
    public void setTranslucent(boolean translucent) {
        this.translucent = translucent;
    }

    /**
     * Appends an axis-aligned upward facing quad spanning {@code [x0, x1] x [z0, z1]} at height
     * {@code y}. Vertices are wound counter-clockwise as seen from above, so back-face culling
     * removes the quad when viewed from below.
     */
    public void addTopQuad(float x0, float z0, float x1, float z1, float y, int rgb,
                           int blockLight, int skyLight) {
        int base = ensureCapacity();
        put(base, x0, y, z0);
        put(base + 3, x0, y, z1);
        put(base + 6, x1, y, z1);
        put(base + 9, x1, y, z0);
        finishQuad(rgb, blockLight, skyLight, LodMesh.FACE_UP);
    }

    /**
     * Appends a vertical quad on one horizontal face of a merged rectangle.
     *
     * <p>The quad occupies the plane selected by {@code face}, runs from {@code yBottom} up to
     * {@code yTop}, and spans the rectangle edge from {@code (sx0, sz0)} to {@code (sx1, sz1)}.
     * Winding is derived from the outward normal so every side quad is also correctly culled.
     */
    public void addSideQuad(byte face, float sx0, float sz0, float sx1, float sz1,
                            float yBottom, float yTop, int rgb, int blockLight, int skyLight) {
        // The outward normal n and the tangent t = up x n give a counter-clockwise winding when
        // the quad is walked bottom-start, bottom-end, top-end, top-start.
        float startX;
        float startZ;
        float endX;
        float endZ;
        switch (face) {
            case LodMesh.FACE_EAST, LodMesh.FACE_NORTH -> {
                // t points from (sx1, sz1) towards (sx0, sz0).
                startX = sx1;
                startZ = sz1;
                endX = sx0;
                endZ = sz0;
            }
            case LodMesh.FACE_WEST, LodMesh.FACE_SOUTH -> {
                startX = sx0;
                startZ = sz0;
                endX = sx1;
                endZ = sz1;
            }
            default -> throw new IllegalArgumentException("not a side face: " + face);
        }

        int base = ensureCapacity();
        put(base, startX, yBottom, startZ);
        put(base + 3, endX, yBottom, endZ);
        put(base + 6, endX, yTop, endZ);
        put(base + 9, startX, yTop, startZ);
        finishQuad(rgb, blockLight, skyLight, face);
    }

    /**
     * Shifts a range of already-appended quads horizontally.
     *
     * <p>Used to place a chunk's quads inside a larger batch: the mesher works in chunk-local
     * coordinates, and moving the finished quads afterwards is a tight pass over a float array
     * rather than an extra addition in the meshing hot loop.
     *
     * @param firstQuad index of the first quad to move, inclusive
     * @param lastQuad  index one past the last quad to move
     * @param offsetX   blocks to add to every X coordinate
     * @param offsetZ   blocks to add to every Z coordinate
     */
    public void translate(int firstQuad, int lastQuad, float offsetX, float offsetZ) {
        if (offsetX == 0.0f && offsetZ == 0.0f) {
            return;
        }
        int from = Math.max(0, firstQuad) * LodMesh.FLOATS_PER_QUAD;
        int to = Math.min(quadCount, lastQuad) * LodMesh.FLOATS_PER_QUAD;
        for (int i = from; i < to; i += 3) {
            positions[i] += offsetX;
            positions[i + 2] += offsetZ;
        }
    }

    /** Freezes the accumulated quads into an immutable mesh, trimming the arrays to size. */
    public LodMesh build() {
        if (quadCount == 0) {
            return LodMesh.EMPTY;
        }
        return new LodMesh(
                Arrays.copyOf(positions, quadCount * LodMesh.FLOATS_PER_QUAD),
                Arrays.copyOf(colors, quadCount),
                Arrays.copyOf(light, quadCount),
                Arrays.copyOf(facing, quadCount),
                quadCount,
                translucent,
                minY,
                maxY);
    }

    private int ensureCapacity() {
        if (quadCount == colors.length) {
            int grown = colors.length * 2;
            positions = Arrays.copyOf(positions, grown * LodMesh.FLOATS_PER_QUAD);
            colors = Arrays.copyOf(colors, grown);
            light = Arrays.copyOf(light, grown);
            facing = Arrays.copyOf(facing, grown);
        }
        return quadCount * LodMesh.FLOATS_PER_QUAD;
    }

    private void put(int offset, float x, float y, float z) {
        positions[offset] = x;
        positions[offset + 1] = y;
        positions[offset + 2] = z;
        if (y < minY) {
            minY = y;
        }
        if (y > maxY) {
            maxY = y;
        }
    }

    private void finishQuad(int rgb, int blockLight, int skyLight, byte face) {
        colors[quadCount] = rgb;
        light[quadCount] = (byte) ((blockLight & 0xF) | ((skyLight & 0xF) << 4));
        facing[quadCount] = face;
        quadCount++;
    }
}
