package com.aetheria.client.render;

import com.aetheria.core.LodDetailLevel;
import com.aetheria.core.LodMesh;

import net.minecraft.world.phys.AABB;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A square group of chunks that are meshed and drawn as one unit.
 *
 * <p>Distant terrain is batched into regions rather than handled per chunk because the fixed cost
 * of submitting geometry does not shrink with distance: a thousand far chunks handled individually
 * cost a thousand submissions for a few pixels each. Grouping them into
 * {@value #REGION_CHUNKS}-chunk squares turns that into a handful of submissions per frame, which
 * is where most of the mod's frame-time headroom comes from.
 *
 * <p>A region moves through three states. It is <em>dirty</em> when its terrain changed, has a
 * <em>build in flight</em> while a worker is meshing it, and is <em>ready</em> once that worker has
 * published a {@link LodMesh}. The hand-off uses a single {@link AtomicReference}, which is all the
 * synchronisation the pipeline needs: the worker only ever writes, the render thread only ever
 * reads.
 */
public final class LodRenderRegion {

    /** Chunks along one axis of a render region. */
    public static final int REGION_CHUNKS = 8;

    /** Blocks along one axis of a render region. */
    public static final int REGION_BLOCKS = REGION_CHUNKS * 16;

    private final int regionX;
    private final int regionZ;

    /** The most recently built mesh, published by a worker and read by the render thread. */
    private final AtomicReference<LodMesh> mesh = new AtomicReference<>(LodMesh.EMPTY);

    /** Detail level of the mesh currently in {@link #mesh}. */
    private volatile LodDetailLevel level;

    /** World-space bounds used for frustum culling; null until the first mesh arrives. */
    private volatile AABB bounds;

    private volatile boolean dirty = true;
    private volatile boolean buildInFlight;

    public LodRenderRegion(int regionX, int regionZ) {
        this.regionX = regionX;
        this.regionZ = regionZ;
    }

    /** Returns the region coordinate containing the given chunk coordinate. */
    public static int regionOf(int chunkCoordinate) {
        return Math.floorDiv(chunkCoordinate, REGION_CHUNKS);
    }

    /** Packs region coordinates into a map key. */
    public static long key(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFF_FFFFL);
    }

    public int regionX() {
        return regionX;
    }

    public int regionZ() {
        return regionZ;
    }

    /** World X of the region's corner, in blocks. */
    public double originX() {
        return (double) regionX * REGION_BLOCKS;
    }

    /** World Z of the region's corner, in blocks. */
    public double originZ() {
        return (double) regionZ * REGION_BLOCKS;
    }

    /** Chunk X of the region's first chunk. */
    public int firstChunkX() {
        return regionX * REGION_CHUNKS;
    }

    /** Chunk Z of the region's first chunk. */
    public int firstChunkZ() {
        return regionZ * REGION_CHUNKS;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Marks the region for a rebuild, for example after one of its chunks changed. */
    public void markDirty() {
        dirty = true;
    }

    public boolean isBuildInFlight() {
        return buildInFlight;
    }

    /** Records that a build has been submitted, so the region is never queued twice. */
    public void setBuildInFlight(boolean inFlight) {
        this.buildInFlight = inFlight;
    }

    /** Called from a worker thread to publish a finished mesh. */
    public void publish(LodMesh built, LodDetailLevel builtLevel, double minY, double maxY) {
        this.mesh.set(built);
        this.level = builtLevel;
        this.bounds = new AABB(originX(), minY, originZ(),
                originX() + REGION_BLOCKS, maxY, originZ() + REGION_BLOCKS);
        this.dirty = false;
        this.buildInFlight = false;
    }

    /** Returns the current mesh, never {@code null}. */
    public LodMesh mesh() {
        return mesh.get();
    }

    /** Returns the detail level of the current mesh, or {@code null} if none was built yet. */
    public LodDetailLevel level() {
        return level;
    }

    /** Returns the world-space bounds, or {@code null} if no mesh has been built yet. */
    public AABB bounds() {
        return bounds;
    }

    /** Returns {@code true} if the region has geometry worth drawing. */
    public boolean isRenderable() {
        return !mesh.get().isEmpty();
    }

    /** Drops the region's geometry, releasing its arrays to the garbage collector. */
    public void discard() {
        mesh.set(LodMesh.EMPTY);
        bounds = null;
        level = null;
    }
}
