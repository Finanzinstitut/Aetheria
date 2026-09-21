package com.aetheria.cache;

import com.aetheria.core.LodChunk;
import com.aetheria.core.LodDataPoint;
import com.aetheria.core.LodDetailLevel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the dimension-scoped chunk store that spans many region files. */
class LodChunkStoreTest {

    @TempDir
    Path root;

    private static LodChunk chunkAt(int x, int z) {
        LodChunk chunk = new LodChunk(x, z, LodDetailLevel.QUARTER, 1);
        for (int cz = 0; cz < 4; cz++) {
            for (int cx = 0; cx < 4; cx++) {
                chunk.set(cx, cz, 0, LodDataPoint.pack(64 + cx, 60, 0x334455 + x, 0, 15,
                        LodDataPoint.FLAG_EXISTS));
            }
        }
        return chunk;
    }

    @Test
    void chunksSpanningManyRegionsAllComeBack() throws IOException {
        try (LodChunkStore store = new LodChunkStore(root, "minecraft:overworld")) {
            List<int[]> coordinates = List.of(
                    new int[] {0, 0}, new int[] {40, 40}, new int[] {-33, 17},
                    new int[] {200, -200}, new int[] {-1, -1});

            for (int[] position : coordinates) {
                store.save(chunkAt(position[0], position[1]));
            }
            for (int[] position : coordinates) {
                LodChunk read = store.load(position[0], position[1]);
                assertArrayEquals(chunkAt(position[0], position[1]).rawData(), read.rawData(),
                        "chunk " + position[0] + "," + position[1] + " was lost");
            }
        }
    }

    @Test
    void openRegionsStayBoundedWhileTravelling() throws IOException {
        try (LodChunkStore store = new LodChunkStore(root, "overworld")) {
            // Walk across far more regions than the store is allowed to keep open at once.
            for (int i = 0; i < LodChunkStore.MAX_OPEN_REGIONS * 3; i++) {
                store.save(chunkAt(i * 32, 0));
            }
            // Everything must still be readable afterwards, from whichever region it landed in.
            for (int i = 0; i < LodChunkStore.MAX_OPEN_REGIONS * 3; i++) {
                assertArrayEquals(chunkAt(i * 32, 0).rawData(), store.load(i * 32, 0).rawData());
            }
        }
    }

    @Test
    void dimensionsAreStoredSeparately() throws IOException {
        try (LodChunkStore overworld = new LodChunkStore(root, "minecraft:overworld");
             LodChunkStore nether = new LodChunkStore(root, "minecraft:the_nether")) {
            overworld.save(chunkAt(0, 0));

            assertNull(nether.load(0, 0), "one dimension must not see another's cache");
        }
    }

    @Test
    void anUncachedChunkLoadsAsNullAndHasNoAge() throws IOException {
        try (LodChunkStore store = new LodChunkStore(root, "overworld")) {
            assertNull(store.load(12, 34));
            assertEquals(-1L, store.ageSeconds(12, 34));
        }
    }

    @Test
    void aFreshlySavedChunkHasAnAgeOfAboutZero() throws IOException {
        try (LodChunkStore store = new LodChunkStore(root, "overworld")) {
            store.save(chunkAt(1, 1));

            long age = store.ageSeconds(1, 1);
            assertTrue(age >= 0 && age < 5, "unexpected age: " + age);
        }
    }

    @Test
    void deletingTheCacheRemovesEverythingFromDisk() throws IOException {
        try (LodChunkStore store = new LodChunkStore(root, "overworld")) {
            store.save(chunkAt(0, 0));
            assertTrue(store.diskUsageBytes() > 0);

            store.deleteAll();

            assertEquals(0L, store.diskUsageBytes());
            assertNull(store.load(0, 0));
        }
    }

    @Test
    void dimensionIdentifiersBecomeSafeFolderNames() {
        assertEquals("minecraft_overworld", LodChunkStore.sanitise("minecraft:overworld"));
        assertEquals("a_b_c", LodChunkStore.sanitise("a/b\\c"));
        assertEquals("unknown", LodChunkStore.sanitise(""));
        assertEquals("the-end_1", LodChunkStore.sanitise("the-end_1"));
    }

    @Test
    void savingABatchReusesTheStoreCorrectly() throws IOException {
        try (LodChunkStore store = new LodChunkStore(root, "overworld")) {
            store.saveAll(List.of(chunkAt(1, 1), chunkAt(2, 2), chunkAt(3, 3)));

            assertArrayEquals(chunkAt(2, 2).rawData(), store.load(2, 2).rawData());
        }
    }
}
