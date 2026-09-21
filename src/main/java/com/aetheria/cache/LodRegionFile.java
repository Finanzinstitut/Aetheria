package com.aetheria.cache;

import com.aetheria.core.LodChunk;
import com.aetheria.core.LodDetailLevel;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A sector-allocated region file holding the LOD data of up to 32x32 chunks.
 *
 * <p>The layout deliberately mirrors vanilla's region files, because the design solves the same
 * problem: thousands of small chunk records would otherwise mean thousands of tiny files, and both
 * the file system and the operating system's page cache handle a few large files far better. One
 * open region file serves 1024 chunks, so a session with an extreme render distance keeps a few
 * dozen descriptors open instead of tens of thousands.
 *
 * <pre>
 *   sector 0..3   header: magic, format version, then 1024 entries of
 *                 { int sectorOffset, int byteLength, int unixSeconds, int crc32 }
 *   sector 4..n   payload records, each { byte detailShift, byte spansPerColumn,
 *                 int uncompressedBytes, deflated long[] data }
 * </pre>
 *
 * <p>Records are compressed with {@link Deflater}: LOD data is extremely repetitive (long runs of
 * identical sky light and near-identical colours), and in practice compresses to roughly a tenth of
 * its in-memory size, which is what keeps a large cache directory manageable.
 *
 * <p>All public methods are synchronized on the instance. Callers are expected to be the I/O pool
 * rather than the render thread.
 */
public final class LodRegionFile implements Closeable {

    /** "AELR" &mdash; Aetheria LOD region. */
    static final int MAGIC = 0x41454C52;

    /** Bumped whenever the payload encoding changes; older files are then discarded, not migrated. */
    static final int FORMAT_VERSION = 1;

    /** Chunks along one axis of a region. */
    public static final int REGION_SIZE = 32;

    /** Number of chunk slots in a region. */
    public static final int SLOTS = REGION_SIZE * REGION_SIZE;

    static final int SECTOR_BYTES = 4096;
    static final int ENTRY_BYTES = 16;
    static final int HEADER_BYTES = 8 + SLOTS * ENTRY_BYTES;
    static final int HEADER_SECTORS = (HEADER_BYTES + SECTOR_BYTES - 1) / SECTOR_BYTES;

    private final Path path;
    private final RandomAccessFile file;

    /** Per-slot sector offset, byte length, timestamp and checksum, mirrored from the header. */
    private final int[] offsets = new int[SLOTS];
    private final int[] lengths = new int[SLOTS];
    private final int[] timestamps = new int[SLOTS];
    private final int[] checksums = new int[SLOTS];

    /** Next never-used sector; the allocator only ever grows the file. */
    private int nextFreeSector;

    private boolean closed;

    /**
     * Opens, and if necessary creates, the region file at the given path.
     *
     * @throws IOException if the file cannot be opened, or holds an unreadable header
     */
    public LodRegionFile(Path path) throws IOException {
        this.path = path;
        Files.createDirectories(path.getParent());
        this.file = new RandomAccessFile(path.toFile(), "rw");

        if (file.length() < HEADER_BYTES) {
            initialiseHeader();
        } else {
            readHeader();
        }
    }

    public Path path() {
        return path;
    }

    /** Returns the slot index of a chunk within its region. */
    public static int slotOf(int chunkX, int chunkZ) {
        return (Math.floorMod(chunkZ, REGION_SIZE) * REGION_SIZE) + Math.floorMod(chunkX, REGION_SIZE);
    }

    /** Returns the region coordinate containing the given chunk coordinate. */
    public static int regionOf(int chunkCoordinate) {
        return chunkCoordinate >> 5;
    }

    /** Returns {@code true} if the region holds a record for the given chunk. */
    public synchronized boolean contains(int chunkX, int chunkZ) {
        return lengths[slotOf(chunkX, chunkZ)] > 0;
    }

    /**
     * Returns the time the chunk's record was written, as a unix timestamp in seconds, or zero if
     * the chunk is not cached. Used to expire stale entries without decompressing them.
     */
    public synchronized long timestampSeconds(int chunkX, int chunkZ) {
        return Integer.toUnsignedLong(timestamps[slotOf(chunkX, chunkZ)]);
    }

    /**
     * Reads a chunk from the region.
     *
     * @return the stored chunk, or {@code null} if the slot is empty or its record is corrupt. A
     *         corrupt record is treated as absent rather than as an error: a truncated cache after
     *         a crash should cost the player a re-scan of that chunk, never a broken session.
     */
    public synchronized LodChunk read(int chunkX, int chunkZ) throws IOException {
        ensureOpen();
        int slot = slotOf(chunkX, chunkZ);
        int length = lengths[slot];
        if (length <= 0) {
            return null;
        }

        byte[] record = new byte[length];
        file.seek((long) offsets[slot] * SECTOR_BYTES);
        file.readFully(record);

        CRC32 crc = new CRC32();
        crc.update(record);
        if ((int) crc.getValue() != checksums[slot]) {
            clearSlot(slot);
            return null;
        }

        try {
            return decode(chunkX, chunkZ, record);
        } catch (IOException | IllegalArgumentException e) {
            clearSlot(slot);
            return null;
        }
    }

    /**
     * Writes a chunk into the region, replacing any previous record for it.
     *
     * <p>The record is written in place when it still fits the sectors the old record occupied,
     * which is the common case for a chunk that is refreshed repeatedly, and appended at the end of
     * the file otherwise. Sectors freed by a move are left as holes; {@link #compact()} reclaims
     * them.
     */
    public synchronized void write(LodChunk chunk) throws IOException {
        ensureOpen();
        int slot = slotOf(chunk.chunkX(), chunk.chunkZ());
        byte[] record = encode(chunk);
        int neededSectors = sectorsFor(record.length);

        int offset = offsets[slot];
        int oldSectors = sectorsFor(lengths[slot]);
        if (offset == 0 || neededSectors > oldSectors) {
            offset = nextFreeSector;
            nextFreeSector += neededSectors;
        }

        file.seek((long) offset * SECTOR_BYTES);
        file.write(record);

        CRC32 crc = new CRC32();
        crc.update(record);

        offsets[slot] = offset;
        lengths[slot] = record.length;
        timestamps[slot] = (int) (System.currentTimeMillis() / 1000L);
        checksums[slot] = (int) crc.getValue();
        writeEntry(slot);
    }

    /** Removes a chunk's record. The sectors it held are reclaimed by the next {@link #compact()}. */
    public synchronized void delete(int chunkX, int chunkZ) throws IOException {
        ensureOpen();
        clearSlot(slotOf(chunkX, chunkZ));
    }

    /** Returns the number of chunks currently stored in this region. */
    public synchronized int size() {
        int count = 0;
        for (int length : lengths) {
            if (length > 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * Rewrites the file without the holes left by relocated or deleted records.
     *
     * <p>Compaction is done into a sibling temporary file and moved into place, so an interruption
     * leaves the original intact rather than a half-rewritten region.
     *
     * <p>The instance is closed once compaction finishes and must be reopened to be used again;
     * the cache manager drops its reference and lets the next request reopen the file.
     *
     * @return the number of bytes reclaimed
     */
    public synchronized long compact() throws IOException {
        ensureOpen();
        long before = file.length();

        Path temporary = path.resolveSibling(path.getFileName() + ".compact");
        Files.deleteIfExists(temporary);

        try (LodRegionFile target = new LodRegionFile(temporary)) {
            for (int slot = 0; slot < SLOTS; slot++) {
                if (lengths[slot] <= 0) {
                    continue;
                }
                byte[] record = new byte[lengths[slot]];
                file.seek((long) offsets[slot] * SECTOR_BYTES);
                file.readFully(record);
                target.writeRaw(slot, record, timestamps[slot], checksums[slot]);
            }
        }

        file.close();
        Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        closed = true;
        return before - Files.size(path);
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            file.close();
        }
    }

    // ---------------------------------------------------------------- encoding

    static byte[] encode(LodChunk chunk) {
        long[] data = chunk.rawData();
        ByteBuffer raw = ByteBuffer.allocate(data.length * Long.BYTES).order(ByteOrder.BIG_ENDIAN);
        raw.asLongBuffer().put(data);

        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(raw.array());
            deflater.finish();
            // Worst case for deflate on incompressible input is n + n/16 + 64 bytes.
            byte[] compressed = new byte[raw.capacity() + raw.capacity() / 16 + 64];
            int compressedLength = 0;
            while (!deflater.finished() && compressedLength < compressed.length) {
                compressedLength += deflater.deflate(compressed, compressedLength,
                        compressed.length - compressedLength);
            }

            ByteBuffer out = ByteBuffer.allocate(6 + compressedLength).order(ByteOrder.BIG_ENDIAN);
            out.put((byte) chunk.detailLevel().shift());
            out.put((byte) chunk.spansPerColumn());
            out.putInt(raw.capacity());
            out.put(compressed, 0, compressedLength);
            return out.array();
        } finally {
            deflater.end();
        }
    }

    static LodChunk decode(int chunkX, int chunkZ, byte[] record) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(record).order(ByteOrder.BIG_ENDIAN);
        LodDetailLevel detailLevel = LodDetailLevel.ofShift(in.get());
        int spansPerColumn = in.get();
        int uncompressedBytes = in.getInt();

        if (spansPerColumn < 1 || uncompressedBytes < 0
                || uncompressedBytes != detailLevel.columnCount() * spansPerColumn * Long.BYTES) {
            throw new IllegalArgumentException("record header does not describe a valid chunk");
        }

        byte[] plain = new byte[uncompressedBytes];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(record, in.position(), record.length - in.position());
            int produced = inflater.inflate(plain);
            if (produced != uncompressedBytes) {
                throw new IllegalArgumentException("truncated record");
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("corrupt LOD record", e);
        } finally {
            inflater.end();
        }

        long[] data = new long[uncompressedBytes / Long.BYTES];
        ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN).asLongBuffer().get(data);
        return LodChunk.wrap(chunkX, chunkZ, detailLevel, spansPerColumn, data);
    }

    // ---------------------------------------------------------------- internals

    private void writeRaw(int slot, byte[] record, int timestamp, int checksum) throws IOException {
        int offset = nextFreeSector;
        nextFreeSector += sectorsFor(record.length);
        file.seek((long) offset * SECTOR_BYTES);
        file.write(record);

        offsets[slot] = offset;
        lengths[slot] = record.length;
        timestamps[slot] = timestamp;
        checksums[slot] = checksum;
        writeEntry(slot);
    }

    private void initialiseHeader() throws IOException {
        file.setLength(0);
        file.seek(0);
        file.writeInt(MAGIC);
        file.writeInt(FORMAT_VERSION);
        file.write(new byte[HEADER_SECTORS * SECTOR_BYTES - 8]);
        nextFreeSector = HEADER_SECTORS;
    }

    private void readHeader() throws IOException {
        file.seek(0);
        if (file.readInt() != MAGIC || file.readInt() != FORMAT_VERSION) {
            // A file from an older format version is rebuilt from scratch rather than migrated;
            // the cache is a derived artefact and can always be regenerated by playing.
            initialiseHeader();
            return;
        }

        byte[] header = new byte[SLOTS * ENTRY_BYTES];
        file.readFully(header);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);

        nextFreeSector = HEADER_SECTORS;
        for (int slot = 0; slot < SLOTS; slot++) {
            offsets[slot] = buffer.getInt();
            lengths[slot] = buffer.getInt();
            timestamps[slot] = buffer.getInt();
            checksums[slot] = buffer.getInt();
            if (lengths[slot] > 0) {
                nextFreeSector = Math.max(nextFreeSector, offsets[slot] + sectorsFor(lengths[slot]));
            }
        }
    }

    private void writeEntry(int slot) throws IOException {
        file.seek(8L + (long) slot * ENTRY_BYTES);
        file.writeInt(offsets[slot]);
        file.writeInt(lengths[slot]);
        file.writeInt(timestamps[slot]);
        file.writeInt(checksums[slot]);
    }

    private void clearSlot(int slot) throws IOException {
        offsets[slot] = 0;
        lengths[slot] = 0;
        timestamps[slot] = 0;
        checksums[slot] = 0;
        writeEntry(slot);
    }

    private static int sectorsFor(int bytes) {
        return (bytes + SECTOR_BYTES - 1) / SECTOR_BYTES;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("region file already closed: " + path);
        }
    }
}
