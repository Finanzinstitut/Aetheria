package com.aetheria.core;

/**
 * The discrete detail levels Aetheria renders distant terrain at.
 *
 * <p>A detail level {@code n} covers {@code 2^n} blocks per LOD cell, so a 16x16 chunk is described
 * by {@code (16 >> n)^2} columns. Level 0 is block-accurate and is only used for the ring directly
 * behind the vanilla render distance; the coarsest level describes a whole chunk with a single
 * column, which is what keeps far terrain nearly free to draw.
 */
public enum LodDetailLevel {

    /** One LOD cell per block: 16x16 columns per chunk. */
    BLOCK(0),
    /** One LOD cell per 2x2 blocks: 8x8 columns per chunk. */
    HALF(1),
    /** One LOD cell per 4x4 blocks: 4x4 columns per chunk. */
    QUARTER(2),
    /** One LOD cell per 8x8 blocks: 2x2 columns per chunk. */
    EIGHTH(3),
    /** One LOD cell per chunk: a single column. */
    CHUNK(4);

    /** Number of blocks along one axis of a chunk. */
    public static final int CHUNK_SIZE = 16;

    private static final LodDetailLevel[] BY_SHIFT = values();

    private final int shift;

    LodDetailLevel(int shift) {
        this.shift = shift;
    }

    /** Returns the power-of-two shift: one cell spans {@code 1 << shift} blocks per axis. */
    public int shift() {
        return shift;
    }

    /** Returns the number of blocks a single cell spans along one axis. */
    public int blocksPerCell() {
        return 1 << shift;
    }

    /** Returns the number of columns along one axis of a chunk at this detail level. */
    public int columnsPerChunk() {
        return CHUNK_SIZE >> shift;
    }

    /** Returns the total number of columns in a chunk at this detail level. */
    public int columnCount() {
        return columnsPerChunk() * columnsPerChunk();
    }

    /** Returns the next coarser level, or {@code this} if already the coarsest. */
    public LodDetailLevel coarser() {
        return shift + 1 < BY_SHIFT.length ? BY_SHIFT[shift + 1] : this;
    }

    /** Returns the next finer level, or {@code this} if already the finest. */
    public LodDetailLevel finer() {
        return shift > 0 ? BY_SHIFT[shift - 1] : this;
    }

    /** The coarsest level available. */
    public static LodDetailLevel coarsest() {
        return BY_SHIFT[BY_SHIFT.length - 1];
    }

    /**
     * Returns the level for the given shift, clamping out-of-range values to the nearest level
     * rather than throwing: detail selection runs every frame and must never fail on a bad input.
     */
    public static LodDetailLevel ofShift(int shift) {
        int clamped = Math.max(0, Math.min(BY_SHIFT.length - 1, shift));
        return BY_SHIFT[clamped];
    }
}
