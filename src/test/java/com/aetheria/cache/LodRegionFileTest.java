package com.aetheria.cache;

import com.aetheria.core.LodChunk;
import com.aetheria.core.LodDataPoint;
import com.aetheria.core.LodDetailLevel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the on-disk region format: round trips, replacement, corruption handling and size. */
class LodRegionFileTest {

    @TempDir
    Path directory;

    private static LodChunk sampleChunk(int chunkX, int chunkZ, long seed) {
        Random random = new Random(seed);
        LodChunk chunk = new LodChunk(chunkX, chunkZ, LodDetailLevel.BLOCK, 2);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int height = 60 + random.nextInt(20);
                chunk.set(x, z, 0, LodDataPoint.pack(height, height - 3,
                        random.nextInt(0x1000000), random.nextInt(16), 15,
                        LodDataPoint.FLAG_EXISTS));
            }
        }
        return chunk;
    }

    @Test
    void chunksRoundTripThroughTheRegionFile() throws IOException {
        Path path = directory.resolve("r.0.0.aelr");
        LodChunk written = sampleChunk(5, 9, 1);

        try (LodRegionFile region = new LodRegionFile(path)) {
            region.write(written);
        }

        try (LodRegionFile region = new LodRegionFile(path)) {
            LodChunk read = region.read(5, 9);

            assertNotNull(read);
            assertEquals(written.detailLevel(), read.detailLevel());
            assertEquals(written.spansPerColumn(), read.spansPerColumn());
            assertArrayEquals(written.rawData(), read.rawData());
        }
    }

    @Test
    void anAbsentChunkReadsAsNull() throws IOException {
        try (LodRegionFile region = new LodRegionFile(directory.resolve("r.0.0.aelr"))) {
            assertNull(region.read(1, 1));
            assertTrue(!region.contains(1, 1));
        }
    }

    @Test
    void severalChunksCoexistInOneRegion() throws IOException {
        Path path = directory.resolve("r.0.0.aelr");

        try (LodRegionFile region = new LodRegionFile(path)) {
            for (int i = 0; i < 24; i++) {
                region.write(sampleChunk(i, i * 2 % 32, i));
            }
            assertEquals(24, region.size());
        }

        try (LodRegionFile region = new LodRegionFile(path)) {
            for (int i = 0; i < 24; i++) {
                assertArrayEquals(sampleChunk(i, i * 2 % 32, i).rawData(),
                        region.read(i, i * 2 % 32).rawData(),
                        "chunk " + i + " did not survive the round trip");
            }
        }
    }

    @Test
    void rewritingAChunkReplacesItRatherThanDuplicatingIt() throws IOException {
        Path path = directory.resolve("r.0.0.aelr");

        try (LodRegionFile region = new LodRegionFile(path)) {
            region.write(sampleChunk(3, 3, 1));
            region.write(sampleChunk(3, 3, 2));

            assertEquals(1, region.size());
            assertArrayEquals(sampleChunk(3, 3, 2).rawData(), region.read(3, 3).rawData());
        }
    }

    @Test
    void chunksFromDifferentRegionsMapToTheSameSlotIndependently() {
        assertEquals(LodRegionFile.slotOf(0, 0), LodRegionFile.slotOf(32, 32));
        assertEquals(0, LodRegionFile.regionOf(31));
        assertEquals(1, LodRegionFile.regionOf(32));
        assertEquals(-1, LodRegionFile.regionOf(-1));
    }

    @Test
    void negativeCoordinatesRoundTrip() throws IOException {
        Path path = directory.resolve("r.-1.-1.aelr");
        LodChunk written = sampleChunk(-5, -20, 7);

        try (LodRegionFile region = new LodRegionFile(path)) {
            region.write(written);
            assertArrayEquals(written.rawData(), region.read(-5, -20).rawData());
        }
    }

    @Test
    void aCorruptRecordIsTreatedAsAbsentRatherThanThrowing() throws IOException {
        Path path = directory.resolve("r.0.0.aelr");
        try (LodRegionFile region = new LodRegionFile(path)) {
            region.write(sampleChunk(0, 0, 3));
        }

        // Scribble over the first payload sector, simulating a torn write after a crash.
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            file.seek((long) LodRegionFile.HEADER_SECTORS * LodRegionFile.SECTOR_BYTES);
            file.write(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        }

        try (LodRegionFile region = new LodRegionFile(path)) {
            assertNull(region.read(0, 0), "a corrupt record must read as absent");
            assertNull(region.read(0, 0), "and must stay absent once discarded");
        }
    }

    @Test
    void aFileWithAForeignHeaderIsRebuiltInsteadOfFailing() throws IOException {
        Path path = directory.resolve("r.0.0.aelr");
        Files.write(path, new byte[LodRegionFile.HEADER_BYTES + 16]);

        try (LodRegionFile region = new LodRegionFile(path)) {
            region.write(sampleChunk(1, 1, 5));
            assertNotNull(region.read(1, 1));
        }
    }

    @Test
    void compressionKeepsRecordsWellBelowTheirInMemorySize() throws IOException {
        Path path = directory.resolve("r.0.0.aelr");
        // A realistically uniform chunk, which is what most terrain looks like.
        LodChunk chunk = new LodChunk(0, 0, LodDetailLevel.BLOCK, 2);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                chunk.set(x, z, 0,
                        LodDataPoint.pack(64, 60, 0x5E9A3C, 0, 15, LodDataPoint.FLAG_EXISTS));
            }
        }

        try (LodRegionFile region = new LodRegionFile(path)) {
            region.write(chunk);
        }

        long inMemory = (long) chunk.rawData().length * Long.BYTES;
        long onDisk = LodRegionFile.encode(chunk).length;
        assertTrue(onDisk * 4 < inMemory,
                "expected strong compression but got " + onDisk + " from " + inMemory + " bytes");
    }

    @Test
    void compactionReclaimsSpaceAndPreservesData() throws IOException {
        Path path = directory.resolve("r.0.0.aelr");

        try (LodRegionFile region = new LodRegionFile(path)) {
            for (int i = 0; i < 16; i++) {
                region.write(sampleChunk(i, 0, i));
            }
            for (int i = 0; i < 8; i++) {
                region.delete(i, 0);
            }
            region.compact();
        }

        try (LodRegionFile region = new LodRegionFile(path)) {
            assertEquals(8, region.size());
            for (int i = 8; i < 16; i++) {
                assertArrayEquals(sampleChunk(i, 0, i).rawData(), region.read(i, 0).rawData());
            }
            for (int i = 0; i < 8; i++) {
                assertNull(region.read(i, 0));
            }
        }
    }
}
