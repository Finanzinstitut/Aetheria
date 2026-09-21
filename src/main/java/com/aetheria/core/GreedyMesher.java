package com.aetheria.core;

/**
 * Converts a {@link LodChunk} into a {@link LodMesh} using greedy quad merging.
 *
 * <p>The naive approach emits one top quad and up to four side quads per LOD column. Greedy meshing
 * instead grows maximal rectangles of visually equivalent columns and emits a single quad for each,
 * which on ordinary terrain cuts the quad count by an order of magnitude or more: an ocean surface
 * or a flat desert chunk collapses from 256 quads to a handful. Since the far LOD ring holds
 * thousands of chunks, this reduction is what makes an extreme render distance affordable at all.
 *
 * <p>Two columns merge when their surfaces sit at the same height, share the same transparency
 * class and light levels, and their colours differ by no more than a configurable tolerance. The
 * tolerance is what lets naturally noisy terrain (grass, sand, stone) merge at all; the merged quad
 * takes the colour of the rectangle's first column.
 *
 * <p>Side faces are emitted as skirts. For each merged rectangle the mesher walks the neighbouring
 * columns along each edge, takes the <em>lowest</em> neighbouring surface, and drops a wall from the
 * rectangle's own surface down to it. Using the lowest neighbour rather than a per-column wall keeps
 * the geometry cheap while guaranteeing no see-through gap ever opens along a cliff.
 *
 * <p>The mesher holds no state between calls beyond a reusable visited mask, so a single instance
 * per worker thread is the intended usage.
 */
public final class GreedyMesher {

    /** Default per-channel colour distance below which two columns still merge. */
    public static final int DEFAULT_COLOR_TOLERANCE = 12;

    private final int colorTolerance;
    private boolean[] visited = new boolean[0];

    public GreedyMesher() {
        this(DEFAULT_COLOR_TOLERANCE);
    }

    /**
     * @param colorTolerance maximum per-channel colour difference that still allows a merge; zero
     *                       demands exact colour equality, larger values trade a little colour
     *                       fidelity for fewer quads
     */
    public GreedyMesher(int colorTolerance) {
        this.colorTolerance = Math.max(0, colorTolerance);
    }

    /**
     * Meshes a chunk into the given builder.
     *
     * <p>The builder is <em>not</em> reset first, so several chunks can be batched into one mesh by
     * the caller. Coordinates are emitted in chunk-local block space, scaled by the chunk's detail
     * level, so a level-2 chunk still spans the full 16 blocks.
     *
     * @param chunk   the chunk to mesh
     * @param builder destination for the generated quads
     * @return the number of quads appended
     */
    public int mesh(LodChunk chunk, LodMeshBuilder builder) {
        int size = chunk.columnsPerAxis();
        int cell = chunk.detailLevel().blocksPerCell();
        int cells = size * size;

        if (visited.length < cells) {
            visited = new boolean[cells];
        } else {
            java.util.Arrays.fill(visited, 0, cells, false);
        }

        int before = builder.quadCount();

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                if (visited[z * size + x]) {
                    continue;
                }
                long origin = chunk.getSurface(x, z);
                if (!LodDataPoint.exists(origin)) {
                    visited[z * size + x] = true;
                    continue;
                }

                int width = growWidth(chunk, size, origin, x, z);
                int depth = growDepth(chunk, size, origin, x, z, width);
                markVisited(size, x, z, width, depth);

                emitRectangle(chunk, builder, size, cell, origin, x, z, width, depth);
            }
        }
        return builder.quadCount() - before;
    }

    /** Extends the rectangle along +X for as long as the columns stay mergeable. */
    private int growWidth(LodChunk chunk, int size, long origin, int x, int z) {
        int width = 1;
        while (x + width < size
                && !visited[z * size + x + width]
                && mergeable(origin, chunk.getSurface(x + width, z))) {
            width++;
        }
        return width;
    }

    /** Extends the rectangle along +Z, accepting a row only if every column in it is mergeable. */
    private int growDepth(LodChunk chunk, int size, long origin, int x, int z, int width) {
        int depth = 1;
        outer:
        while (z + depth < size) {
            for (int dx = 0; dx < width; dx++) {
                if (visited[(z + depth) * size + x + dx]
                        || !mergeable(origin, chunk.getSurface(x + dx, z + depth))) {
                    break outer;
                }
            }
            depth++;
        }
        return depth;
    }

    private void markVisited(int size, int x, int z, int width, int depth) {
        for (int dz = 0; dz < depth; dz++) {
            int row = (z + dz) * size;
            for (int dx = 0; dx < width; dx++) {
                visited[row + x + dx] = true;
            }
        }
    }

    private void emitRectangle(LodChunk chunk, LodMeshBuilder builder, int size, int cell,
                               long origin, int x, int z, int width, int depth) {
        int rgb = LodDataPoint.rgb(origin);
        int blockLight = LodDataPoint.blockLight(origin);
        int skyLight = LodDataPoint.skyLight(origin);
        int topY = LodDataPoint.topY(origin);
        int bottomY = LodDataPoint.bottomY(origin);

        float x0 = (float) (x * cell);
        float z0 = (float) (z * cell);
        float x1 = (float) ((x + width) * cell);
        float z1 = (float) ((z + depth) * cell);
        // The top face sits on top of the highest block of the span.
        float faceY = topY + 1.0f;

        builder.addTopQuad(x0, z0, x1, z1, faceY, rgb, blockLight, skyLight);

        // West (-X) and east (+X) skirts run along Z; north (-Z) and south (+Z) run along X.
        emitSkirt(chunk, builder, LodMesh.FACE_WEST, size, topY, bottomY, rgb, blockLight, skyLight,
                x0, z0, x0, z1, lowestNeighbour(chunk, size, x - 1, z, 0, 1, depth));
        emitSkirt(chunk, builder, LodMesh.FACE_EAST, size, topY, bottomY, rgb, blockLight, skyLight,
                x1, z0, x1, z1, lowestNeighbour(chunk, size, x + width, z, 0, 1, depth));
        emitSkirt(chunk, builder, LodMesh.FACE_NORTH, size, topY, bottomY, rgb, blockLight, skyLight,
                x0, z0, x1, z0, lowestNeighbour(chunk, size, x, z - 1, 1, 0, width));
        emitSkirt(chunk, builder, LodMesh.FACE_SOUTH, size, topY, bottomY, rgb, blockLight, skyLight,
                x0, z1, x1, z1, lowestNeighbour(chunk, size, x, z + depth, 1, 0, width));
    }

    private void emitSkirt(LodChunk chunk, LodMeshBuilder builder, byte face, int size, int topY,
                           int bottomY, int rgb, int blockLight, int skyLight, float sx0, float sz0,
                           float sx1, float sz1, int neighbourTopY) {
        // Drop the wall to the lowest neighbouring surface, but never below the span's own base:
        // terrain beneath that belongs to a lower span and is meshed separately.
        float top = topY + 1.0f;
        float bottom = Math.max(bottomY, neighbourTopY + 1);
        if (bottom >= top) {
            return;
        }
        builder.addSideQuad(face, sx0, sz0, sx1, sz1, bottom, top, rgb, blockLight, skyLight);
    }

    /**
     * Returns the lowest surface height among {@code count} columns starting at {@code (x, z)} and
     * stepping by {@code (stepX, stepZ)}. Columns outside the chunk, and columns with no terrain,
     * count as the bottom of the world so that the skirt reaches all the way down at a chunk border
     * where the neighbour is not loaded yet.
     */
    private int lowestNeighbour(LodChunk chunk, int size, int x, int z, int stepX, int stepZ,
                                int count) {
        int lowest = Integer.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            int cx = x + stepX * i;
            int cz = z + stepZ * i;
            if (cx < 0 || cx >= size || cz < 0 || cz >= size) {
                return LodDataPoint.MIN_Y;
            }
            long neighbour = chunk.getSurface(cx, cz);
            if (!LodDataPoint.exists(neighbour)) {
                return LodDataPoint.MIN_Y;
            }
            lowest = Math.min(lowest, LodDataPoint.topY(neighbour));
        }
        return lowest == Integer.MAX_VALUE ? LodDataPoint.MIN_Y : lowest;
    }

    /** Returns {@code true} if two surfaces are similar enough to share a quad. */
    private boolean mergeable(long a, long b) {
        if (!LodDataPoint.exists(b)) {
            return false;
        }
        if (LodDataPoint.topY(a) != LodDataPoint.topY(b)) {
            return false;
        }
        if (LodDataPoint.blockLight(a) != LodDataPoint.blockLight(b)
                || LodDataPoint.skyLight(a) != LodDataPoint.skyLight(b)) {
            return false;
        }
        if (LodDataPoint.hasFlag(a, LodDataPoint.FLAG_TRANSPARENT)
                != LodDataPoint.hasFlag(b, LodDataPoint.FLAG_TRANSPARENT)) {
            return false;
        }
        return colorDistance(LodDataPoint.rgb(a), LodDataPoint.rgb(b)) <= colorTolerance;
    }

    /** Returns the largest per-channel difference between two packed colours. */
    static int colorDistance(int a, int b) {
        int dr = Math.abs(((a >>> 16) & 0xFF) - ((b >>> 16) & 0xFF));
        int dg = Math.abs(((a >>> 8) & 0xFF) - ((b >>> 8) & 0xFF));
        int db = Math.abs((a & 0xFF) - (b & 0xFF));
        return Math.max(dr, Math.max(dg, db));
    }
}
