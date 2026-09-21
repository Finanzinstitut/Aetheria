package com.aetheria.cache;

import com.aetheria.core.LodChunk;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The on-disk half of the chunk cache: a directory of {@link LodRegionFile}s per dimension.
 *
 * <p>This is the component that makes terrain persist between sessions and across multiplayer
 * servers. Once a chunk has been seen it is written here, and on the next visit it is read back
 * without the server needing to send it again &mdash; which is also why distant terrain stays
 * visible far beyond a server's own view distance.
 *
 * <p>Region files are kept open in a small LRU map. Opening one costs a seek and a header read, so
 * holding the handful the player is currently moving through avoids re-opening the same file dozens
 * of times a second, while the bound keeps the descriptor count flat no matter how far the player
 * travels.
 *
 * <p>Every method may block on the disk and is expected to be called from the I/O pool.
 */
public final class LodChunkStore implements Closeable {

    /** How many region files stay open at once. */
    public static final int MAX_OPEN_REGIONS = 32;

    private final Path root;
    private final String dimensionId;
    private final Path dimensionRoot;

    /** Access-ordered so that the eldest entry is the least recently used region. */
    private final LinkedHashMap<Long, LodRegionFile> openRegions =
            new LinkedHashMap<>(16, 0.75f, true);

    private boolean closed;

    /**
     * @param root        cache root directory, normally {@code .minecraft/aetheria-cache}
     * @param dimensionId identifier of the dimension, used as the sub-directory name; the caller is
     *                    responsible for sanitising it into a valid file name
     */
    public LodChunkStore(Path root, String dimensionId) {
        this.root = root;
        this.dimensionId = dimensionId;
        this.dimensionRoot = root.resolve(sanitise(dimensionId));
    }

    public Path root() {
        return root;
    }

    public String dimensionId() {
        return dimensionId;
    }

    /** Returns the chunk stored for these coordinates, or {@code null} if it is not cached. */
    public synchronized LodChunk load(int chunkX, int chunkZ) throws IOException {
        ensureOpen();
        LodRegionFile region = regionFor(chunkX, chunkZ, false);
        return region == null ? null : region.read(chunkX, chunkZ);
    }

    /** Stores a chunk, replacing any previous record for the same coordinates. */
    public synchronized void save(LodChunk chunk) throws IOException {
        ensureOpen();
        LodRegionFile region = regionFor(chunk.chunkX(), chunk.chunkZ(), true);
        region.write(chunk);
    }

    /** Stores several chunks, reusing each open region file across the batch. */
    public synchronized void saveAll(List<LodChunk> chunks) throws IOException {
        for (LodChunk chunk : chunks) {
            save(chunk);
        }
    }

    /**
     * Returns the age of a cached chunk in seconds, or {@code -1} if it is not cached. The cache
     * manager uses this to decide whether a chunk should be re-scanned from the live world.
     */
    public synchronized long ageSeconds(int chunkX, int chunkZ) throws IOException {
        ensureOpen();
        LodRegionFile region = regionFor(chunkX, chunkZ, false);
        if (region == null || !region.contains(chunkX, chunkZ)) {
            return -1L;
        }
        long written = region.timestampSeconds(chunkX, chunkZ);
        return Math.max(0L, System.currentTimeMillis() / 1000L - written);
    }

    /** Returns the total size of this dimension's cache on disk, in bytes. */
    public synchronized long diskUsageBytes() throws IOException {
        if (!Files.isDirectory(dimensionRoot)) {
            return 0L;
        }
        try (var stream = Files.list(dimensionRoot)) {
            long total = 0L;
            for (Path path : stream.toList()) {
                total += Files.size(path);
            }
            return total;
        }
    }

    /**
     * Rewrites every region file without its holes.
     *
     * @return the number of bytes reclaimed
     */
    public synchronized long compact() throws IOException {
        ensureOpen();
        closeOpenRegions();
        if (!Files.isDirectory(dimensionRoot)) {
            return 0L;
        }

        long reclaimed = 0L;
        List<Path> regions;
        try (var stream = Files.list(dimensionRoot)) {
            regions = stream.filter(path -> path.getFileName().toString().endsWith(".aelr")).toList();
        }
        for (Path path : regions) {
            try (LodRegionFile region = new LodRegionFile(path)) {
                reclaimed += region.compact();
            }
        }
        return reclaimed;
    }

    /** Deletes this dimension's entire cache from disk. */
    public synchronized void deleteAll() throws IOException {
        closeOpenRegions();
        if (!Files.isDirectory(dimensionRoot)) {
            return;
        }
        try (var stream = Files.list(dimensionRoot)) {
            for (Path path : stream.toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        closeOpenRegions();
    }

    private LodRegionFile regionFor(int chunkX, int chunkZ, boolean create) throws IOException {
        int regionX = LodRegionFile.regionOf(chunkX);
        int regionZ = LodRegionFile.regionOf(chunkZ);
        long key = ((long) regionX << 32) | (regionZ & 0xFFFF_FFFFL);

        LodRegionFile region = openRegions.get(key);
        if (region != null) {
            return region;
        }

        Path path = dimensionRoot.resolve("r." + regionX + "." + regionZ + ".aelr");
        if (!create && !Files.exists(path)) {
            return null;
        }

        region = new LodRegionFile(path);
        openRegions.put(key, region);
        evictOpenRegions();
        return region;
    }

    private void evictOpenRegions() throws IOException {
        while (openRegions.size() > MAX_OPEN_REGIONS) {
            Iterator<Map.Entry<Long, LodRegionFile>> iterator = openRegions.entrySet().iterator();
            Map.Entry<Long, LodRegionFile> eldest = iterator.next();
            iterator.remove();
            eldest.getValue().close();
        }
    }

    private void closeOpenRegions() throws IOException {
        List<IOException> failures = new ArrayList<>();
        for (LodRegionFile region : openRegions.values()) {
            try {
                region.close();
            } catch (IOException e) {
                failures.add(e);
            }
        }
        openRegions.clear();
        if (!failures.isEmpty()) {
            IOException combined = new IOException("failed to close " + failures.size() + " regions");
            failures.forEach(combined::addSuppressed);
            throw combined;
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("chunk store already closed: " + dimensionRoot);
        }
    }

    /** Turns a dimension identifier such as {@code minecraft:the_nether} into a safe folder name. */
    static String sanitise(String dimensionId) {
        StringBuilder builder = new StringBuilder(dimensionId.length());
        for (int i = 0; i < dimensionId.length(); i++) {
            char c = dimensionId.charAt(i);
            builder.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
        }
        return builder.isEmpty() ? "unknown" : builder.toString();
    }
}
