package com.aetheria.client.render;

import com.aetheria.Aetheria;
import com.aetheria.cache.LodChunkCache;
import com.aetheria.concurrent.AetheriaExecutors;
import com.aetheria.config.AetheriaConfig;
import com.aetheria.core.GreedyMesher;
import com.aetheria.core.LodChunk;
import com.aetheria.core.LodDetailLevel;
import com.aetheria.core.LodDetailPolicy;
import com.aetheria.core.LodMesh;
import com.aetheria.core.LodMeshBuilder;

import com.mojang.blaze3d.vertex.PoseStack;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws the ring of LOD terrain that surrounds the vanilla render distance.
 *
 * <p>Per frame the renderer does as little as it possibly can. It never meshes, never reads the
 * disk and never allocates per chunk; it walks the regions around the camera, hands any that need
 * rebuilding to the mesh pool, discards everything outside the view frustum and submits the rest.
 * Every expensive step has already happened on a worker thread by the time the frame starts, which
 * is what keeps frame times flat while terrain streams in.
 *
 * <p>Regions closer to the camera than the game's own render distance are skipped entirely: vanilla
 * already draws that terrain at full detail, and drawing LOD geometry underneath it would only cost
 * fill rate and produce z-fighting along the boundary.
 */
public final class LodRenderer {

    private final AetheriaConfig config;
    private final LodChunkCache cache;
    private final AetheriaExecutors executors;

    private final Map<Long, LodRenderRegion> regions = new ConcurrentHashMap<>();

    /** One mesher and one builder per worker thread, reused for every region that thread builds. */
    private final ThreadLocal<GreedyMesher> meshers;
    private final ThreadLocal<LodMeshBuilder> builders = ThreadLocal.withInitial(LodMeshBuilder::new);

    /** Camera state sampled once per tick and read by the detail policy. */
    private volatile double cameraSpeed;
    private volatile double cameraAltitude;
    private double lastCameraX;
    private double lastCameraY;
    private double lastCameraZ;
    private boolean hasLastCamera;

    private int drawnRegions;
    private int drawnQuads;
    private int culledRegions;

    public LodRenderer(AetheriaConfig config, LodChunkCache cache, AetheriaExecutors executors) {
        this.config = config;
        this.cache = cache;
        this.executors = executors;
        this.meshers = ThreadLocal.withInitial(() -> new GreedyMesher(config.colorTolerance()));
    }

    /** Number of regions submitted in the last frame, for the debug overlay. */
    public int drawnRegions() {
        return drawnRegions;
    }

    /** Number of quads submitted in the last frame, for the debug overlay. */
    public int drawnQuads() {
        return drawnQuads;
    }

    /** Number of regions rejected by the frustum in the last frame, for the debug overlay. */
    public int culledRegions() {
        return culledRegions;
    }

    /** Camera speed in blocks per second, as used by the dynamic detail policy. */
    public double cameraSpeed() {
        return cameraSpeed;
    }

    /** Camera height above the terrain below it, as used by the dynamic detail policy. */
    public double cameraAltitude() {
        return cameraAltitude;
    }

    /** Marks the region containing a chunk for a rebuild. */
    public void invalidate(int chunkX, int chunkZ) {
        LodRenderRegion region = regions.get(LodRenderRegion.key(
                LodRenderRegion.regionOf(chunkX), LodRenderRegion.regionOf(chunkZ)));
        if (region != null) {
            region.markDirty();
        }
    }

    /** Marks every region for a rebuild, after a settings change. */
    public void invalidateAll() {
        regions.values().forEach(LodRenderRegion::markDirty);
    }

    /**
     * Per-tick housekeeping: samples the camera for the dynamic detail policy and retires regions
     * that have fallen outside the render distance.
     */
    public void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }
        sampleCamera(client.player.getX(), client.player.getY(), client.player.getZ(),
                client.level.getMinY());
        retireDistantRegions(client);
    }

    /**
     * Submits the LOD ring for this frame.
     *
     * @param context the Fabric level render context for the current frame
     * @param policy  detail policy selecting a level per region
     */
    public void render(LevelRenderContext context, LodDetailPolicy policy) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }

        CameraRenderState cameraState = context.levelState().cameraRenderState;
        Vec3 cameraPos = cameraState.pos;
        Frustum frustum = cameraState.cullFrustum;

        int cameraChunkX = (int) Math.floor(cameraPos.x / 16.0);
        int cameraChunkZ = (int) Math.floor(cameraPos.z / 16.0);
        int vanillaChunks = client.options.getEffectiveRenderDistance();
        int lodChunks = config.lodRenderDistance();
        // getSkyDarken() reports how much the sky is darkened, 0 at noon and higher at night;
        // invert it into the brightness factor the shading pass expects.
        float skyBrightness = (15 - client.level.getSkyDarken()) / 15.0f;

        drawnRegions = 0;
        drawnQuads = 0;
        culledRegions = 0;
        int buildsLeft = config.maxRegionBuildsPerFrame();

        int firstX = LodRenderRegion.regionOf(cameraChunkX - lodChunks);
        int lastX = LodRenderRegion.regionOf(cameraChunkX + lodChunks);
        int firstZ = LodRenderRegion.regionOf(cameraChunkZ - lodChunks);
        int lastZ = LodRenderRegion.regionOf(cameraChunkZ + lodChunks);

        for (int rz = firstZ; rz <= lastZ; rz++) {
            for (int rx = firstX; rx <= lastX; rx++) {
                double chunkDistance = regionDistanceInChunks(rx, rz, cameraChunkX, cameraChunkZ);
                if (chunkDistance > lodChunks) {
                    continue;
                }
                // Leave a one-region margin so the LOD ring starts just outside the vanilla terrain
                // rather than fighting with it along the boundary.
                if (chunkDistance + LodRenderRegion.REGION_CHUNKS < vanillaChunks) {
                    continue;
                }

                final int regionX = rx;
                final int regionZ = rz;
                LodRenderRegion region = regions.computeIfAbsent(
                        LodRenderRegion.key(rx, rz),
                        key -> new LodRenderRegion(regionX, regionZ));

                LodDetailLevel level = policy.levelFor(chunkDistance, cameraAltitude, cameraSpeed);
                if (buildsLeft > 0 && (region.isDirty() || region.level() != level)
                        && scheduleBuild(region, level, (long) (chunkDistance * chunkDistance))) {
                    buildsLeft--;
                }

                if (!region.isRenderable()) {
                    continue;
                }

                AABB bounds = region.bounds();
                if (bounds != null && !frustum.isVisible(bounds)) {
                    culledRegions++;
                    continue;
                }

                submit(context, region, cameraPos, skyBrightness);
            }
        }
    }

    /** Submits one region's geometry to the frame's collector. */
    private void submit(LevelRenderContext context, LodRenderRegion region, Vec3 cameraPos,
                        float skyBrightness) {
        LodMesh mesh = region.mesh();
        if (mesh.isEmpty()) {
            return;
        }

        // Vertices are region-local, so the camera offset is applied in doubles here and the
        // floats that reach the GPU stay small. This is what keeps terrain thousands of blocks out
        // free of the floating-point jitter it would otherwise show.
        float offsetX = (float) (region.originX() - cameraPos.x);
        float offsetY = (float) -cameraPos.y;
        float offsetZ = (float) (region.originZ() - cameraPos.z);

        PoseStack poseStack = context.poseStack();
        context.submitNodeCollector().submitCustomGeometry(
                poseStack,
                RenderTypes.debugQuads(),
                (pose, consumer) -> LodGeometrySubmitter.submit(
                        consumer, mesh, offsetX, offsetY, offsetZ, skyBrightness));

        drawnRegions++;
        drawnQuads += mesh.quadCount();
    }

    /**
     * Submits a region rebuild to the mesh pool, unless one is already in flight.
     *
     * @return {@code true} if a rebuild was actually queued
     */
    private boolean scheduleBuild(LodRenderRegion region, LodDetailLevel level, long priority) {
        if (region.isBuildInFlight()) {
            return false;
        }
        region.setBuildInFlight(true);

        boolean accepted = executors.submitMesh(() -> {
            try {
                LodMesh built = buildRegionMesh(region, level);
                region.publish(built, level, built.minY(), built.maxY());
            } catch (RuntimeException e) {
                region.setBuildInFlight(false);
                Aetheria.logger().error("Failed to mesh LOD region {}, {}",
                        region.regionX(), region.regionZ(), e);
            }
        }, priority);

        if (!accepted) {
            region.setBuildInFlight(false);
        }
        return accepted;
    }

    /**
     * Meshes every cached chunk of a region into one mesh, on a worker thread.
     *
     * <p>Chunks that are not resident are requested from the cache and left out of this build; when
     * they arrive they mark the region dirty and it is rebuilt. Filling the holes by blocking on
     * the disk here would defeat the entire point of the background pipeline.
     */
    private LodMesh buildRegionMesh(LodRenderRegion region, LodDetailLevel level) {
        GreedyMesher mesher = meshers.get();
        LodMeshBuilder builder = builders.get();
        builder.reset();

        for (int dz = 0; dz < LodRenderRegion.REGION_CHUNKS; dz++) {
            for (int dx = 0; dx < LodRenderRegion.REGION_CHUNKS; dx++) {
                int chunkX = region.firstChunkX() + dx;
                int chunkZ = region.firstChunkZ() + dz;

                LodChunk chunk = cache.getOrLoad(chunkX, chunkZ, 0L);
                if (chunk == null) {
                    continue;
                }
                // Coarsen a finely stored chunk down to the level this region is drawn at.
                while (chunk.detailLevel().shift() < level.shift()) {
                    chunk = chunk.downSample();
                }

                int quadsBefore = builder.quadCount();
                mesher.mesh(chunk, builder);
                // The mesher works in chunk-local coordinates; move the new quads into place.
                builder.translate(quadsBefore, builder.quadCount(), dx * 16.0f, dz * 16.0f);
            }
        }
        return builder.build();
    }

    /** Samples camera speed and altitude once per tick for the dynamic detail policy. */
    private void sampleCamera(double x, double y, double z, int worldBottom) {
        if (hasLastCamera) {
            double dx = x - lastCameraX;
            double dy = y - lastCameraY;
            double dz = z - lastCameraZ;
            // One client tick is 1/20 s, so a per-tick delta scales to blocks per second by 20.
            double instantaneous = Math.sqrt(dx * dx + dy * dy + dz * dz) * 20.0;
            // Smooth the estimate so a single slow tick cannot briefly coarsen the whole world.
            cameraSpeed = cameraSpeed * 0.8 + instantaneous * 0.2;
        }
        lastCameraX = x;
        lastCameraY = y;
        lastCameraZ = z;
        hasLastCamera = true;

        // Sea level is the reference height; below it the altitude term contributes nothing.
        cameraAltitude = Math.max(0.0, y - worldBottom - 64.0);
    }

    /** Releases regions that have fallen outside the render distance. */
    private void retireDistantRegions(Minecraft client) {
        if (client.player == null) {
            return;
        }
        int cameraChunkX = (int) Math.floor(client.player.getX() / 16.0);
        int cameraChunkZ = (int) Math.floor(client.player.getZ() / 16.0);
        int limit = config.lodRenderDistance() + LodRenderRegion.REGION_CHUNKS;

        List<LodRenderRegion> retired = new ArrayList<>();
        Iterator<Map.Entry<Long, LodRenderRegion>> iterator = regions.entrySet().iterator();
        while (iterator.hasNext()) {
            LodRenderRegion region = iterator.next().getValue();
            if (regionDistanceInChunks(region.regionX(), region.regionZ(), cameraChunkX,
                    cameraChunkZ) > limit) {
                iterator.remove();
                retired.add(region);
            }
        }
        retired.forEach(LodRenderRegion::discard);
    }

    /** Returns the distance from the camera to a region's centre, in chunks. */
    static double regionDistanceInChunks(int regionX, int regionZ, int cameraChunkX,
                                         int cameraChunkZ) {
        double centreX = regionX * LodRenderRegion.REGION_CHUNKS
                + LodRenderRegion.REGION_CHUNKS / 2.0;
        double centreZ = regionZ * LodRenderRegion.REGION_CHUNKS
                + LodRenderRegion.REGION_CHUNKS / 2.0;
        double dx = centreX - cameraChunkX;
        double dz = centreZ - cameraChunkZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Drops every region's geometry. */
    public void close() {
        regions.values().forEach(LodRenderRegion::discard);
        regions.clear();
    }
}
