package com.aetheria.core;

import java.util.Arrays;

/**
 * The LOD representation of a single chunk at one detail level.
 *
 * <p>Storage is a flat {@code long[]} of {@link LodDataPoint}s: {@code columnCount} columns, each
 * holding up to {@code spansPerColumn} vertical spans, ordered from the topmost span downwards.
 * Empty trailing slots are {@link LodDataPoint#EMPTY}. Keeping the whole chunk in one primitive
 * array means a loaded LOD chunk costs exactly one object header regardless of how much terrain it
 * describes, and it can be written to disk with a single bulk copy.
 *
 * <p>Instances are not thread safe. The pipeline hands ownership of a chunk from the worker that
 * built it to the render thread via a concurrent queue, and never mutates one after publishing it.
 */
public final class LodChunk {

    /** Default number of vertical spans kept per column. */
    public static final int DEFAULT_SPANS_PER_COLUMN = 4;

    private final int chunkX;
    private final int chunkZ;
    private final LodDetailLevel detailLevel;
    private final int columnsPerAxis;
    private final int spansPerColumn;
    private final long[] data;

    private long lastUsedNanos;

    /**
     * Creates an empty LOD chunk.
     *
     * @param chunkX         chunk X coordinate in chunk space
     * @param chunkZ         chunk Z coordinate in chunk space
     * @param detailLevel    detail level this chunk is stored at
     * @param spansPerColumn number of vertical spans kept per column; more spans mean better caves
     *                       and overhangs at a linear memory cost
     */
    public LodChunk(int chunkX, int chunkZ, LodDetailLevel detailLevel, int spansPerColumn) {
        if (spansPerColumn < 1) {
            throw new IllegalArgumentException("spansPerColumn must be positive: " + spansPerColumn);
        }
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.detailLevel = detailLevel;
        this.columnsPerAxis = detailLevel.columnsPerChunk();
        this.spansPerColumn = spansPerColumn;
        this.data = new long[detailLevel.columnCount() * spansPerColumn];
    }

    /** Creates an empty LOD chunk with {@link #DEFAULT_SPANS_PER_COLUMN} spans per column. */
    public LodChunk(int chunkX, int chunkZ, LodDetailLevel detailLevel) {
        this(chunkX, chunkZ, detailLevel, DEFAULT_SPANS_PER_COLUMN);
    }

    /**
     * Wraps an existing data array, used when loading a chunk from the disk cache so that the
     * decompressed bytes become the backing store without a further copy.
     *
     * @throws IllegalArgumentException if the array length does not match the detail level
     */
    public static LodChunk wrap(int chunkX, int chunkZ, LodDetailLevel detailLevel,
                                int spansPerColumn, long[] data) {
        LodChunk chunk = new LodChunk(chunkX, chunkZ, detailLevel, spansPerColumn);
        if (data.length != chunk.data.length) {
            throw new IllegalArgumentException(
                    "expected " + chunk.data.length + " data points but got " + data.length);
        }
        System.arraycopy(data, 0, chunk.data, 0, data.length);
        return chunk;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public LodDetailLevel detailLevel() {
        return detailLevel;
    }

    /** Number of columns along one axis. */
    public int columnsPerAxis() {
        return columnsPerAxis;
    }

    public int spansPerColumn() {
        return spansPerColumn;
    }

    /** Direct access to the backing array; callers must not resize or publish it. */
    public long[] rawData() {
        return data;
    }

    /**
     * Returns the span at {@code spanIndex} of the column at {@code (x, z)}, in column coordinates.
     *
     * @return the packed data point, possibly {@link LodDataPoint#EMPTY}
     */
    public long get(int x, int z, int spanIndex) {
        return data[index(x, z, spanIndex)];
    }

    /** Returns the topmost span of the column at {@code (x, z)}, i.e. its visible surface. */
    public long getSurface(int x, int z) {
        return data[index(x, z, 0)];
    }

    /** Stores a span. Spans must be written top-down so that index 0 is always the surface. */
    public void set(int x, int z, int spanIndex, long dataPoint) {
        data[index(x, z, spanIndex)] = dataPoint;
    }

    /** Clears every span, returning the chunk to its freshly allocated state for pool reuse. */
    public void clear() {
        Arrays.fill(data, LodDataPoint.EMPTY);
    }

    /** Returns {@code true} if no column holds any terrain. */
    public boolean isEmpty() {
        for (long dataPoint : data) {
            if (LodDataPoint.exists(dataPoint)) {
                return false;
            }
        }
        return true;
    }

    /** Records that the chunk was touched, used by the in-memory LRU eviction pass. */
    public void touch(long nowNanos) {
        this.lastUsedNanos = nowNanos;
    }

    /** Returns the timestamp of the last {@link #touch(long)}. */
    public long lastUsedNanos() {
        return lastUsedNanos;
    }

    /** Returns the approximate heap footprint in bytes, used by the memory budget reporter. */
    public long estimatedBytes() {
        // 8 bytes per data point plus a generous allowance for the object header and fields.
        return (long) data.length * Long.BYTES + 64L;
    }

    /**
     * Down-samples this chunk into the next coarser detail level.
     *
     * <p>Each output column blends the {@code 2x2} input columns it covers, span by span. Building
     * the coarse levels from the fine ones rather than from the world means a chunk is only ever
     * scanned once, no matter how many levels it ends up feeding.
     *
     * @return a new chunk one level coarser, or {@code this} if already the coarsest
     */
    public LodChunk downSample() {
        if (detailLevel == LodDetailLevel.coarsest()) {
            return this;
        }
        LodDetailLevel target = detailLevel.coarser();
        LodChunk result = new LodChunk(chunkX, chunkZ, target, spansPerColumn);
        long[] scratch = new long[4];

        for (int z = 0; z < result.columnsPerAxis(); z++) {
            for (int x = 0; x < result.columnsPerAxis(); x++) {
                for (int span = 0; span < spansPerColumn; span++) {
                    scratch[0] = get(x * 2, z * 2, span);
                    scratch[1] = get(x * 2 + 1, z * 2, span);
                    scratch[2] = get(x * 2, z * 2 + 1, span);
                    scratch[3] = get(x * 2 + 1, z * 2 + 1, span);
                    result.set(x, z, span, LodDataPoint.blend(scratch, 0, 4));
                }
            }
        }
        return result;
    }

    private int index(int x, int z, int spanIndex) {
        if (x < 0 || x >= columnsPerAxis || z < 0 || z >= columnsPerAxis) {
            throw new IndexOutOfBoundsException("column (" + x + ", " + z + ") outside chunk");
        }
        if (spanIndex < 0 || spanIndex >= spansPerColumn) {
            throw new IndexOutOfBoundsException("span " + spanIndex + " outside column");
        }
        return (z * columnsPerAxis + x) * spansPerColumn + spanIndex;
    }

    @Override
    public String toString() {
        return "LodChunk{" + chunkX + ", " + chunkZ + ", " + detailLevel + "}";
    }
}
