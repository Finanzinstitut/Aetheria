package com.aetheria.cache;

import com.aetheria.concurrent.AetheriaExecutors;
import com.aetheria.config.AetheriaConfig;
import com.aetheria.core.LodChunk;
import com.aetheria.core.LodDataPoint;
import com.aetheria.core.LodDetailLevel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies residency, eviction and write-behind in the in-memory half of the cache. */
class LodChunkCacheTest {

    @TempDir
    Path root;

    private AetheriaConfig config;
    private AetheriaExecutors executors;
    private LodChunkStore store;
    private LodChunkCache cache;
    private final List<Throwable> errors = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        config = new AetheriaConfig();
        executors = new AetheriaExecutors(1, 1);
        store = new LodChunkStore(root, "overworld");
        cache = new LodChunkCache(store, executors, config, errors::add);
    }

    @AfterEach
    void tearDown() throws IOException {
        executors.shutdown();
        store.close();
        assertTrue(errors.isEmpty(), "background tasks reported: " + errors);
    }

    private static LodChunk chunkAt(int x, int z, LodDetailLevel level) {
        LodChunk chunk = new LodChunk(x, z, level, 1);
        chunk.set(0, 0, 0, LodDataPoint.pack(64, 60, 0x223344, 0, 15, LodDataPoint.FLAG_EXISTS));
        return chunk;
    }

    @Test
    void keysPackAndUnpackIncludingNegativeCoordinates() {
        long key = LodChunkCache.key(-40, 77);

        assertEquals(-40, LodChunkCache.keyX(key));
        assertEquals(77, LodChunkCache.keyZ(key));
    }

    @Test
    void aStoredChunkIsImmediatelyVisible() {
        cache.put(chunkAt(2, 3, LodDetailLevel.QUARTER));

        assertNotNull(cache.get(2, 3));
        assertTrue(cache.isResident(2, 3));
        assertEquals(1, cache.residentCount());
    }

    @Test
    void anAbsentChunkReadsAsNullWithoutBlocking() {
        assertNull(cache.get(9, 9));
        assertFalse(cache.isResident(9, 9));
    }

    @Test
    void unloadingFreesTheTrackedMemory() {
        cache.put(chunkAt(0, 0, LodDetailLevel.BLOCK));
        long occupied = cache.residentBytes();
        assertTrue(occupied > 0);

        cache.unload(0, 0);

        assertEquals(0, cache.residentCount());
        assertEquals(0L, cache.residentBytes());
    }

    @Test
    void replacingAChunkDoesNotDoubleCountItsMemory() {
        cache.put(chunkAt(1, 1, LodDetailLevel.BLOCK));
        long once = cache.residentBytes();

        cache.put(chunkAt(1, 1, LodDetailLevel.BLOCK));

        assertEquals(once, cache.residentBytes());
        assertEquals(1, cache.residentCount());
    }

    @Test
    void evictionBringsTheCacheBackWithinItsBudget() {
        config.setMemoryBudgetMegabytes(32);
        long budget = config.memoryBudgetBytes();

        int chunks = 0;
        while (cache.residentBytes() <= budget * 2 && chunks < 20_000) {
            cache.put(chunkAt(chunks % 512, chunks / 512, LodDetailLevel.BLOCK));
            chunks++;
        }
        assertTrue(cache.residentBytes() > budget, "the fixture did not exceed the budget");

        int evicted = cache.evictToBudget();

        assertTrue(evicted > 0);
        assertTrue(cache.residentBytes() <= budget,
                "still over budget: " + cache.residentBytes() + " > " + budget);
    }

    @Test
    void evictionIsANoOpWhileWithinBudget() {
        cache.put(chunkAt(0, 0, LodDetailLevel.QUARTER));

        assertEquals(0, cache.evictToBudget());
        assertEquals(1, cache.residentCount());
    }

    @Test
    void queuedChunksReachDiskOnABlockingFlush() throws IOException {
        cache.put(chunkAt(4, 5, LodDetailLevel.QUARTER));

        cache.flushBlocking();

        assertNotNull(store.load(4, 5), "the chunk never reached the disk");
    }

    @Test
    void disablingTheCacheKeepsChunksInMemoryButOffDisk() throws IOException {
        config.setCacheEnabled(false);

        cache.put(chunkAt(7, 7, LodDetailLevel.QUARTER));
        cache.flushBlocking();

        assertTrue(cache.isResident(7, 7), "terrain must still render with the disk cache off");
        assertNull(store.load(7, 7), "nothing should have been written");
    }

    @Test
    void statisticsTrackHitsAndMisses() {
        cache.put(chunkAt(0, 0, LodDetailLevel.QUARTER));
        cache.get(0, 0);
        cache.get(1, 1);

        LodChunkCache.Stats stats = cache.stats();

        assertEquals(1, stats.hits());
        assertEquals(1, stats.misses());
        assertEquals(0.5, stats.hitRate(), 1e-9);
        assertTrue(stats.residentMegabytes() >= 0.0);
    }

    @Test
    void anEmptyCacheReportsAPerfectHitRateRatherThanDividingByZero() {
        assertEquals(1.0, cache.stats().hitRate(), 1e-9);
    }

    @Test
    void clearingDropsEverythingResident() {
        cache.put(chunkAt(1, 2, LodDetailLevel.QUARTER));

        cache.clear();

        assertEquals(0, cache.residentCount());
        assertEquals(0L, cache.residentBytes());
    }
}
