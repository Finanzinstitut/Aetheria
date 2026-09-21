package com.aetheria.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies chunk storage geometry and the down-sampling that builds the coarse detail levels. */
class LodChunkTest {

    @Test
    void detailLevelsDescribeTheExpectedColumnCounts() {
        assertEquals(16, LodDetailLevel.BLOCK.columnsPerChunk());
        assertEquals(256, LodDetailLevel.BLOCK.columnCount());
        assertEquals(1, LodDetailLevel.CHUNK.columnsPerChunk());
        assertEquals(1, LodDetailLevel.CHUNK.columnCount());
        assertEquals(4, LodDetailLevel.QUARTER.blocksPerCell());
    }

    @Test
    void aFreshChunkIsEmpty() {
        assertTrue(new LodChunk(0, 0, LodDetailLevel.BLOCK).isEmpty());
    }

    @Test
    void spansRoundTripThroughStorage() {
        LodChunk chunk = new LodChunk(3, -7, LodDetailLevel.HALF, 2);
        long surface = LodDataPoint.pack(70, 70, 0x123456, 1, 15, LodDataPoint.FLAG_EXISTS);
        long below = LodDataPoint.pack(40, 30, 0x654321, 0, 0, LodDataPoint.FLAG_EXISTS);

        chunk.set(2, 5, 0, surface);
        chunk.set(2, 5, 1, below);

        assertEquals(surface, chunk.getSurface(2, 5));
        assertEquals(below, chunk.get(2, 5, 1));
        assertFalse(chunk.isEmpty());
        assertEquals(3, chunk.chunkX());
        assertEquals(-7, chunk.chunkZ());
    }

    @Test
    void outOfBoundsAccessIsRejected() {
        LodChunk chunk = new LodChunk(0, 0, LodDetailLevel.QUARTER, 1);

        assertThrows(IndexOutOfBoundsException.class, () -> chunk.getSurface(4, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> chunk.getSurface(0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> chunk.get(0, 0, 1));
    }

    @Test
    void downSamplingHalvesTheResolutionAndKeepsTheHighestSurface() {
        LodChunk chunk = new LodChunk(0, 0, LodDetailLevel.HALF, 1);
        for (int z = 0; z < 8; z++) {
            for (int x = 0; x < 8; x++) {
                int height = (x == 0 && z == 0) ? 90 : 64;
                chunk.set(x, z, 0,
                        LodDataPoint.pack(height, height, 0x404040, 0, 15, LodDataPoint.FLAG_EXISTS));
            }
        }

        LodChunk coarse = chunk.downSample();

        assertEquals(LodDetailLevel.QUARTER, coarse.detailLevel());
        assertEquals(4, coarse.columnsPerAxis());
        assertEquals(90, LodDataPoint.topY(coarse.getSurface(0, 0)),
                "down-sampling must keep the highest surface so no hole opens at the LOD seam");
        assertEquals(64, LodDataPoint.topY(coarse.getSurface(1, 1)));
    }

    @Test
    void downSamplingTheCoarsestLevelIsANoOp() {
        LodChunk chunk = new LodChunk(0, 0, LodDetailLevel.coarsest(), 1);

        assertEquals(chunk, chunk.downSample());
    }

    @Test
    void repeatedDownSamplingReachesASingleColumn() {
        LodChunk chunk = new LodChunk(0, 0, LodDetailLevel.BLOCK, 1);
        while (chunk.detailLevel() != LodDetailLevel.coarsest()) {
            chunk = chunk.downSample();
        }

        assertEquals(1, chunk.columnsPerAxis());
    }

    @Test
    void memoryFootprintShrinksSharplyWithCoarserDetail() {
        long fine = new LodChunk(0, 0, LodDetailLevel.BLOCK, 4).estimatedBytes();
        long coarse = new LodChunk(0, 0, LodDetailLevel.CHUNK, 4).estimatedBytes();

        assertTrue(coarse * 10 < fine,
                "a chunk-level LOD chunk should cost far less than a block-level one");
    }

    @Test
    void clearResetsEveryColumn() {
        LodChunk chunk = new LodChunk(0, 0, LodDetailLevel.QUARTER, 1);
        chunk.set(0, 0, 0, LodDataPoint.pack(64, 64, 0xFFFFFF, 0, 0, LodDataPoint.FLAG_EXISTS));

        chunk.clear();

        assertTrue(chunk.isEmpty());
    }
}
