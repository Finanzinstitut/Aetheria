package com.aetheria.client;

import com.aetheria.Aetheria;
import com.aetheria.cache.LodChunkCache;
import com.aetheria.cache.LodChunkStore;
import com.aetheria.client.render.LodRenderer;
import com.aetheria.client.world.WorldLodScanner;
import com.aetheria.concurrent.AetheriaExecutors;
import com.aetheria.config.AetheriaConfig;
import com.aetheria.core.LodChunk;
import com.aetheria.core.LodDetailLevel;
import com.aetheria.core.LodDetailPolicy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.IOException;

/**
 * Owns Aetheria's per-world state and drives the pipeline.
 *
 * <p>One engine exists per loaded world. It is created when the player joins, torn down when they
 * leave, and recreated on a dimension change, which keeps the cache, the thread pools and the
 * renderer's GPU buffers strictly scoped to the world they describe.
 *
 * <p>The pipeline is:
 *
 * <ol>
 *   <li>The client reports a chunk load; {@link WorldLodScanner} turns it into a {@link LodChunk}
 *       on a worker thread.
 *   <li>The chunk enters {@link LodChunkCache}, becoming visible at once and reaching the disk
 *       shortly afterwards.
 *   <li>{@link LodRenderer} meshes what it needs at the detail level {@link LodDetailPolicy}
 *       selects, uploads a bounded number of meshes per frame, and draws them.
 * </ol>
 *
 * <p>Only {@link #tick(Minecraft)} and {@link #close()} touch the client thread; everything
 * they schedule runs on the pools in {@link AetheriaExecutors}.
 */
public final class LodEngine implements AutoCloseable {

    /** Cached chunks written to disk per tick, bounding the I/O burst after a fast flight. */
    private static final int WRITES_PER_TICK = 16;

    /** Ticks between memory budget sweeps; sweeping every tick would be wasted work. */
    private static final int EVICTION_INTERVAL_TICKS = 20;

    private final AetheriaConfig config;
    private final AetheriaExecutors executors;
    private final LodChunkStore store;
    private final LodChunkCache cache;
    private final WorldLodScanner scanner;
    private final LodRenderer renderer;
    private final String dimensionId;

    private LodDetailPolicy policy;
    private int tickCounter;

    /**
     * Creates the engine for a world.
     *
     * @param dimensionId identifier of the dimension, used to scope the on-disk cache
     */
    public LodEngine(String dimensionId) {
        this.config = Aetheria.config();
        this.dimensionId = dimensionId;
        this.executors = new AetheriaExecutors(config.meshThreads(), config.ioThreads());
        this.store = new LodChunkStore(Aetheria.cacheRoot(), dimensionId);
        this.cache = new LodChunkCache(store, executors, config, LodEngine::reportError);
        this.scanner = new WorldLodScanner(config);
        this.renderer = new LodRenderer(config, cache, executors);
        this.policy = buildPolicy();

        Aetheria.logger().info(
                "LOD engine started for {} using {} mesh threads and {} I/O threads",
                dimensionId, executors.meshThreadCount(), executors.ioThreadCount());
    }

    public LodChunkCache cache() {
        return cache;
    }

    public LodRenderer renderer() {
        return renderer;
    }

    public AetheriaExecutors executors() {
        return executors;
    }

    public LodDetailPolicy policy() {
        return policy;
    }

    public String dimensionId() {
        return dimensionId;
    }

    /**
     * Queues a freshly received chunk for conversion into LOD data.
     *
     * <p>Scanning happens on the mesh pool rather than inline, because walking 16x16x384 block
     * states is far too expensive to do on the thread that is also drawing frames.
     */
    public void onChunkLoaded(ClientLevel level, LevelChunk chunk) {
        int chunkX = chunk.getPos().x();
        int chunkZ = chunk.getPos().z();
        long priority = squaredDistanceToCamera(chunkX, chunkZ);

        executors.submitMesh(() -> {
            try {
                LodChunk lod = scanner.scan(level, chunk, LodDetailLevel.BLOCK);
                if (lod != null && !lod.isEmpty()) {
                    // Store the chunk at the finest level the user allows, down-sampling from the
                    // block-accurate scan so the world is only walked once.
                    LodChunk stored = lod;
                    while (stored.detailLevel().shift() < config.finestDetailLevel().shift()) {
                        stored = stored.downSample();
                    }
                    cache.put(stored);
                    renderer.invalidate(chunkX, chunkZ);
                }
            } catch (RuntimeException e) {
                reportError(e);
            }
        }, priority);
    }

    /** Notes that a chunk left the client's view distance; its LOD data deliberately stays. */
    public void onChunkUnloaded(int chunkX, int chunkZ) {
        // Nothing to do: keeping the LOD data resident is exactly what makes terrain the server has
        // forgotten about stay on screen. The memory budget sweep drops it when it really is cold.
        renderer.invalidate(chunkX, chunkZ);
    }

    /**
     * Runs the once-per-tick housekeeping: rebuilding the detail policy after a settings change,
     * flushing queued writes and enforcing the memory budget.
     */
    public void tick(Minecraft client) {
        tickCounter++;

        if (policyIsStale()) {
            policy = buildPolicy();
            renderer.invalidateAll();
        }

        cache.flushDirty(WRITES_PER_TICK);

        if (tickCounter % EVICTION_INTERVAL_TICKS == 0) {
            int evicted = cache.evictToBudget();
            if (evicted > 0) {
                Aetheria.logger().debug("Evicted {} LOD chunks to stay within the memory budget",
                        evicted);
            }
        }

        renderer.tick(client);
    }

    /**
     * Shuts the engine down: stops the pools, persists everything still queued and releases the
     * renderer's GPU buffers.
     */
    @Override
    public void close() {
        renderer.close();
        executors.drainQueues();
        cache.flushBlocking();
        executors.shutdown();

        try {
            if (config.compactOnExit() && config.cacheEnabled()) {
                long reclaimed = store.compact();
                if (reclaimed > 0) {
                    Aetheria.logger().info("Reclaimed {} KB by compacting the {} cache",
                            reclaimed / 1024L, dimensionId);
                }
            }
            store.close();
        } catch (IOException e) {
            reportError(e);
        }

        cache.clear();
        Aetheria.logger().info("LOD engine stopped for {}", dimensionId);
    }

    private LodDetailPolicy buildPolicy() {
        return new LodDetailPolicy(config.fullDetailRadius(), config.finestDetailLevel(),
                config.effectiveDynamicDetailLimit());
    }

    /** Returns {@code true} if the configuration changed the values the policy was built from. */
    private boolean policyIsStale() {
        return policy.fullDetailRadius() != config.fullDetailRadius()
                || policy.finestLevel() != config.finestDetailLevel()
                || policy.dynamicBiasLimit() != config.effectiveDynamicDetailLimit();
    }

    private static long squaredDistanceToCamera(int chunkX, int chunkZ) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0L;
        }
        long dx = chunkX - (long) (client.player.getX() / 16.0);
        long dz = chunkZ - (long) (client.player.getZ() / 16.0);
        return dx * dx + dz * dz;
    }

    /**
     * Reports a background failure.
     *
     * <p>Every worker catches its own exceptions and routes them here rather than letting a thread
     * die silently: a failed chunk should cost the player that chunk, not the rest of the session.
     */
    private static void reportError(Throwable error) {
        Aetheria.logger().error("Background LOD task failed", error);
    }
}
