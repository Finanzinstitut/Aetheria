package com.aetheria.core;

/**
 * An immutable, render-ready batch of LOD quads.
 *
 * <p>The mesh is stored as parallel primitive arrays rather than a list of quad objects: uploading
 * it to the GPU is then a handful of bulk reads with no pointer chasing, and a mesh that is
 * discarded before it is ever uploaded costs the garbage collector three arrays instead of tens of
 * thousands of short-lived objects.
 *
 * <p>Vertices are stored in chunk-local block coordinates. The renderer translates them into view
 * space with the chunk origin, which keeps the float values small and precise even thousands of
 * blocks from the origin.
 */
public final class LodMesh {

    /** Upward facing quad (+Y). */
    public static final byte FACE_UP = 0;
    /** Quad facing -Z. */
    public static final byte FACE_NORTH = 1;
    /** Quad facing +Z. */
    public static final byte FACE_SOUTH = 2;
    /** Quad facing -X. */
    public static final byte FACE_WEST = 3;
    /** Quad facing +X. */
    public static final byte FACE_EAST = 4;

    /** Floats stored per quad: four vertices of three coordinates each. */
    public static final int FLOATS_PER_QUAD = 12;

    private final float[] positions;
    private final int[] colors;
    private final byte[] light;
    private final byte[] facing;
    private final int quadCount;
    private final boolean translucent;
    private final float minY;
    private final float maxY;

    LodMesh(float[] positions, int[] colors, byte[] light, byte[] facing, int quadCount,
            boolean translucent, float minY, float maxY) {
        this.positions = positions;
        this.colors = colors;
        this.light = light;
        this.facing = facing;
        this.quadCount = quadCount;
        this.translucent = translucent;
        this.minY = minY;
        this.maxY = maxY;
    }

    /** An empty mesh, shared so that empty chunks allocate nothing. */
    public static final LodMesh EMPTY =
            new LodMesh(new float[0], new int[0], new byte[0], new byte[0], 0, false, 0.0f, 0.0f);

    /** Vertex positions, {@link #FLOATS_PER_QUAD} floats per quad, chunk-local block coordinates. */
    public float[] positions() {
        return positions;
    }

    /** One packed 0xRRGGBB colour per quad. */
    public int[] colors() {
        return colors;
    }

    /** One packed light byte per quad: block light in the low nibble, sky light in the high one. */
    public byte[] light() {
        return light;
    }

    /** One {@code FACE_*} constant per quad. */
    public byte[] facing() {
        return facing;
    }

    public int quadCount() {
        return quadCount;
    }

    /** Lowest world Y touched by the mesh, used to build its culling bounds. */
    public float minY() {
        return minY;
    }

    /** Highest world Y touched by the mesh, used to build its culling bounds. */
    public float maxY() {
        return maxY;
    }

    /** Returns {@code true} if the mesh belongs in the translucent render pass. */
    public boolean isTranslucent() {
        return translucent;
    }

    public boolean isEmpty() {
        return quadCount == 0;
    }

    /** Returns the approximate GPU-bound size of this mesh in bytes. */
    public long estimatedBytes() {
        return (long) positions.length * Float.BYTES
                + (long) colors.length * Integer.BYTES
                + light.length
                + facing.length;
    }

    /** Unpacks the block light level of a quad, 0..15. */
    public static int blockLightOf(byte packed) {
        return packed & 0xF;
    }

    /** Unpacks the sky light level of a quad, 0..15. */
    public static int skyLightOf(byte packed) {
        return (packed >>> 4) & 0xF;
    }

    @Override
    public String toString() {
        return "LodMesh{" + quadCount + " quads, translucent=" + translucent + "}";
    }
}
