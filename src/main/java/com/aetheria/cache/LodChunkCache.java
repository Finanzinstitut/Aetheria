package com.aetheria.cache;

import com.aetheria.concurrent.AetheriaExecutors;
import com.aetheria.config.AetheriaConfig;
import com.aetheria.core.LodChunk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The resident half of the chunk cache, sitting between the renderer and {@link LodChunkStore}.
 *
 * <p>Lookups are non-blocking by design. {@link #get(int, int)} either returns a chunk that is
 * already in memory or returns {@code null} and schedules a background load; the render thread
 * never waits on the disk, which is the single most important rule for keeping frame times flat.
 *
 * <p>Writes are equally deferred. A chunk handed to {@link #put(LodChunk)} becomes visible
 * immediately and is queued for persistence, so scanning a newly received chunk never blocks on
 * I/O either.
 *
 * <p>Residency is bounded by the configured memory budget rather than by a chunk count, because a
 * fine-detail chunk costs many times what a coarse one does. When the budget is exceeded the least
 * recently used chunks are dropped; they stay on disk and reload in the background if the player
 * turns back.
 */
public final class LodChunkCache {

    private final LodChunkStore store;
    private final AetheriaExecutors executors;
    private final AetheriaConfig config;
    private final Consumer<Throwable> errorHandler;

    /** Resident chunks, keyed by packed chunk coordinates. */
    private final Map<Long, LodChunk> resident = new ConcurrentHashMap<>();

    /** Coordinates with a load already in flight, so the same chunk is never queued twice. */
    private final Set<Long> pendingLoads = ConcurrentHashMap.newKeySet();

    /** Chunks waiting to be written to disk. */
    private final ConcurrentLinkedQueue<LodChunk> dirty = new ConcurrentLinkedQueue<>();

    private final AtomicLong residentBytes = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong diskLoads = new AtomicLong();
    private final AtomicLong diskWrites = new AtomicLong();

    public LodChunkCache(LodChunkStore store, AetheriaExecutors executors, AetheriaConfig config,
                         Consumer<Throwable> errorHandler) {
        this.store = store;
        this.executors = executors;
        this.config = config;
        this.errorHandler = errorHandler;
    }

    /** Packs chunk coordinates into a single map key. */
    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFF_FFFFL);
    }

    /** Unpacks the X coordinate from a key produced by {@link #key(int, int)}. */
    public static int keyX(long key) {
        return (int) (key >> 32);
    }

    /** Unpacks the Z coordinate from a key produced by {@link #key(int, int)}. */
    public static int keyZ(long key) {
        return (int) key;
    }

    /**
     * Returns a resident chunk without ever touching the disk.
     *
     * @return the chunk, or {@code null} if it is not in memory
     */
    public LodChunk get(int chunkX, int chunkZ) {
        LodChunk chunk = resident.get(key(chunkX, chunkZ));
        if (chunk == null) {
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        chunk.touch(System.nanoTime());
        return chunk;
    }

    /**
     * Returns a resident chunk, scheduling a background load if it is absent.
     *
     * @param priority lower loads first; pass the squared chunk distance from the camera
     * @return the chunk if it is already in memory, {@code null} otherwise
     */
    public LodChunk getOrLoad(int chunkX, int chunkZ, long priority) {
        LodChunk chunk = get(chunkX, chunkZ);
        if (chunk == null) {
            requestLoad(chunkX, chunkZ, priority);
        }
        return chunk;
    }

    /**
     * Schedules a background load of a chunk from disk, unless it is already resident or a load for
     * it is already in flight.
     *
     * @param priority lower loads first; pass the squared chunk distance from the camera
     */
    public void requestLoad(int chunkX, int chunkZ, long priority) {
        if (!config.cacheEnabled()) {
            return;
        }
        long key = key(chunkX, chunkZ);
        if (resident.containsKey(key) || !pendingLoads.add(key)) {
            return;
        }

        boolean accepted = executors.submitIo(() -> {
            try {
                LodChunk loaded = store.load(chunkX, chunkZ);
                if (loaded != null && !isExpired(chunkX, chunkZ)) {
                    diskLoads.incrementAndGet();
                    admit(loaded);
                }
            } catch (IOException | RuntimeException e) {
                errorHandler.accept(e);
            } finally {
                pendingLoads.remove(key);
            }
        }, priority);

        if (!accepted) {
            pendingLoads.remove(key);
        }
    }

    /**
     * Makes a freshly built chunk visible and queues it for persistence.
     *
     * <p>The chunk is published to the renderer before it reaches the disk: a player should see
     * terrain the moment it is meshed, not one disk round-trip later.
     */
    public void put(LodChunk chunk) {
        admit(chunk);
        if (config.cacheEnabled()) {
            dirty.add(chunk);
        }
    }

    /** Drops a chunk from memory without removing it from disk. */
    public void unload(int chunkX, int chunkZ) {
        LodChunk removed = resident.remove(key(chunkX, chunkZ));
        if (removed != null) {
            residentBytes.addAndGet(-removed.estimatedBytes());
        }
    }

    /** Returns {@code true} if the chunk is currently in memory. */
    public boolean isResident(int chunkX, int chunkZ) {
        return resident.containsKey(key(chunkX, chunkZ));
    }

    /** Returns the number of resident chunks. */
    public int residentCount() {
        return resident.size();
    }

    /** Returns the approximate heap used by resident chunks, in bytes. */
    public long residentBytes() {
        return residentBytes.get();
    }

    /** Returns cache counters for the debug overlay. */
    public Stats stats() {
        return new Stats(resident.size(), residentBytes.get(), hits.get(), misses.get(),
                diskLoads.get(), diskWrites.get(), dirty.size(), pendingLoads.size());
    }

    /**
     * Writes up to {@code limit} queued chunks to disk on the I/O pool.
     *
     * <p>Called once per tick rather than once per modified chunk, so a burst of chunk updates
     * costs one batched submission instead of hundreds of individual tasks.
     *
     * @return the number of chunks submitted
     */
    public int flushDirty(int limit) {
        if (!config.cacheEnabled() || dirty.isEmpty()) {
            return 0;
        }
        List<LodChunk> batch = new ArrayList<>(Math.min(limit, dirty.size()));
        for (int i = 0; i < limit; i++) {
            LodChunk chunk = dirty.poll();
            if (chunk == null) {
                break;
            }
            batch.add(chunk);
        }
        if (batch.isEmpty()) {
            return 0;
        }

        boolean accepted = executors.submitIo(() -> {
            try {
                store.saveAll(batch);
                diskWrites.addAndGet(batch.size());
            } catch (IOException | RuntimeException e) {
                errorHandler.accept(e);
            }
        }, Long.MAX_VALUE); // Persistence is never urgent; reads always go first.

        if (!accepted) {
            dirty.addAll(batch);
            return 0;
        }
        return batch.size();
    }

    /**
     * Evicts the least recently used chunks until the resident set fits the configured budget.
     *
     * @return the number of chunks evicted
     */
    public int evictToBudget() {
        long budget = config.memoryBudgetBytes();
        if (residentBytes.get() <= budget) {
            return 0;
        }

        List<Map.Entry<Long, LodChunk>> entries = new ArrayList<>(resident.entrySet());
        entries.sort(Comparator.comparingLong(entry -> entry.getValue().lastUsedNanos()));

        int evicted = 0;
        for (Map.Entry<Long, LodChunk> entry : entries) {
            if (residentBytes.get() <= budget) {
                break;
            }
            if (resident.remove(entry.getKey(), entry.getValue())) {
                residentBytes.addAndGet(-entry.getValue().estimatedBytes());
                evicted++;
            }
        }
        return evicted;
    }

    /** Drops every resident chunk, used when changing dimension or leaving a world. */
    public void clear() {
        resident.clear();
        pendingLoads.clear();
        residentBytes.set(0L);
    }

    /** Writes every queued chunk synchronously; called once when leaving a world. */
    public void flushBlocking() {
        if (!config.cacheEnabled()) {
            dirty.clear();
            return;
        }
        List<LodChunk> remaining = new ArrayList<>();
        LodChunk chunk;
        while ((chunk = dirty.poll()) != null) {
            remaining.add(chunk);
        }
        if (remaining.isEmpty()) {
            return;
        }
        try {
            store.saveAll(remaining);
            diskWrites.addAndGet(remaining.size());
        } catch (IOException | RuntimeException e) {
            errorHandler.accept(e);
        }
    }

    private void admit(LodChunk chunk) {
        chunk.touch(System.nanoTime());
        LodChunk previous = resident.put(key(chunk.chunkX(), chunk.chunkZ()), chunk);
        if (previous != null) {
            residentBytes.addAndGet(-previous.estimatedBytes());
        }
        residentBytes.addAndGet(chunk.estimatedBytes());
    }

    private boolean isExpired(int chunkX, int chunkZ) {
        int maxAgeDays = config.cacheMaxAgeDays();
        if (maxAgeDays <= 0) {
            return false;
        }
        try {
            long age = store.ageSeconds(chunkX, chunkZ);
            return age >= 0 && age > maxAgeDays * 86_400L;
        } catch (IOException e) {
            errorHandler.accept(e);
            return false;
        }
    }

    /**
     * A snapshot of the cache counters.
     *
     * @param residentChunks chunks currently in memory
     * @param residentBytes  approximate heap used by those chunks
     * @param hits           lookups served from memory
     * @param misses         lookups that found nothing resident
     * @param diskLoads      chunks read back from disk
     * @param diskWrites     chunks written to disk
     * @param queuedWrites   chunks waiting to be written
     * @param queuedLoads    loads currently in flight
     */
    public record Stats(int residentChunks, long residentBytes, long hits, long misses,
                        long diskLoads, long diskWrites, int queuedWrites, int queuedLoads) {

        /** Returns the fraction of lookups served from memory, between 0 and 1. */
        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 1.0 : (double) hits / total;
        }

        /** Returns the resident footprint in megabytes. */
        public double residentMegabytes() {
            return residentBytes / (1024.0 * 1024.0);
        }
    }
}
