package com.aetheria.client.world;

import com.aetheria.config.AetheriaConfig;
import com.aetheria.core.LodChunk;
import com.aetheria.core.LodDataPoint;
import com.aetheria.core.LodDetailLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.MapColor;

/**
 * Turns a live {@link LevelChunk} into the compact {@link LodChunk} representation.
 *
 * <p>This is the only place in the mod that touches block states, and it does so exactly once per
 * chunk. Everything downstream works on packed {@code long}s, which is why the renderer and the
 * cache never need the block registry, never pin a chunk in memory, and can run entirely off the
 * client thread.
 *
 * <p>Colour comes from each block's map colour rather than from its texture. Sampling the block
 * atlas would mean touching render state from a worker thread and would tie the scan to the active
 * resource pack; map colours are plain data, already chosen to read well at a distance, and cost a
 * single field access. Grass, foliage and water are then tinted with the biome colour so that
 * distant terrain still shows the biome boundaries the player sees up close.
 *
 * <p>A scanner instance holds a reusable cursor and is used by one worker thread at a time.
 */
public final class WorldLodScanner {

    private final AetheriaConfig config;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public WorldLodScanner(AetheriaConfig config) {
        this.config = config;
    }

    /**
     * Scans a chunk.
     *
     * @param level       level the chunk belongs to, used for biome tinting and light levels
     * @param chunk       the chunk to scan
     * @param detailLevel detail level to produce
     * @return the scanned chunk, or {@code null} if it could not be read
     */
    public LodChunk scan(ClientLevel level, LevelChunk chunk, LodDetailLevel detailLevel) {
        int spans = config.spansPerColumn();
        LodChunk result = new LodChunk(chunk.getPos().x(), chunk.getPos().z(), detailLevel, spans);

        int originX = chunk.getPos().getMinBlockX();
        int originZ = chunk.getPos().getMinBlockZ();
        int cell = detailLevel.blocksPerCell();
        int columns = detailLevel.columnsPerChunk();
        int minY = level.getMinY();
        int maxY = level.getMaxY();

        try {
            for (int cz = 0; cz < columns; cz++) {
                for (int cx = 0; cx < columns; cx++) {
                    // Sample the centre of the cell. At level 0 this is the block itself; at
                    // coarser levels it is far cheaper than averaging every block it covers, with
                    // no visible difference at the distances those levels are used.
                    int blockX = originX + cx * cell + cell / 2;
                    int blockZ = originZ + cz * cell + cell / 2;
                    scanColumn(level, chunk, result, cx, cz, blockX, blockZ, minY, maxY, spans);
                }
            }
        } catch (RuntimeException e) {
            // A chunk can be unloaded underneath the worker while it is being scanned. Losing the
            // scan is fine; the chunk is queued again the next time the client receives it.
            return null;
        }
        return result;
    }

    /** Walks one column from the top down, recording up to {@code spans} visible vertical spans. */
    private void scanColumn(ClientLevel level, LevelChunk chunk, LodChunk result, int cx, int cz,
                            int blockX, int blockZ, int minY, int maxY, int spans) {
        int spanIndex = 0;
        int y = maxY;

        while (y >= minY && spanIndex < spans) {
            while (y >= minY && !isVisible(chunk, blockX, y, blockZ)) {
                y--;
            }
            if (y < minY) {
                return;
            }

            int topY = y;
            int rgb = colorOf(level, chunk, blockX, y, blockZ);
            boolean transparent = isTransparent(chunk, blockX, y, blockZ);

            // Extend downwards while the column keeps the same appearance, so a uniform stone
            // column costs one data point instead of a hundred.
            while (y - 1 >= minY
                    && isVisible(chunk, blockX, y - 1, blockZ)
                    && isTransparent(chunk, blockX, y - 1, blockZ) == transparent
                    && colorOf(level, chunk, blockX, y - 1, blockZ) == rgb) {
                y--;
            }

            int lightY = Math.min(topY + 1, maxY);
            int blockLight = lightLevel(level, LightLayer.BLOCK, blockX, lightY, blockZ);
            int skyLight = lightLevel(level, LightLayer.SKY, blockX, lightY, blockZ);

            int flags = LodDataPoint.FLAG_EXISTS
                    | (transparent ? LodDataPoint.FLAG_TRANSPARENT : 0);
            result.set(cx, cz, spanIndex,
                    LodDataPoint.pack(clampY(topY), clampY(y), rgb, blockLight, skyLight, flags));

            spanIndex++;
            y--;
        }
    }

    /** Returns {@code true} if a block contributes anything to distant terrain. */
    private boolean isVisible(LevelChunk chunk, int x, int y, int z) {
        BlockState state = stateAt(chunk, x, y, z);
        if (state.isAir()) {
            return false;
        }
        if (!config.renderDistantWater() && !state.getFluidState().isEmpty()) {
            return false;
        }
        // Blocks with no map colour (plants, torches, signs) are invisible at LOD distances, and
        // skipping them keeps the surface free of one-block noise.
        return state.getMapColor(chunk, at(x, y, z)) != MapColor.NONE;
    }

    private boolean isTransparent(LevelChunk chunk, int x, int y, int z) {
        BlockState state = stateAt(chunk, x, y, z);
        return !state.getFluidState().isEmpty() || !state.canOcclude();
    }

    /** Returns the packed 0xRRGGBB colour of a block, biome-tinted where the block calls for it. */
    private int colorOf(ClientLevel level, LevelChunk chunk, int x, int y, int z) {
        BlockState state = stateAt(chunk, x, y, z);
        BlockPos pos = at(x, y, z);

        if (!state.getFluidState().isEmpty()) {
            return BiomeColors.getAverageWaterColor(level, pos) & 0xFF_FFFF;
        }

        int base = state.getMapColor(chunk, pos).col & 0xFF_FFFF;

        // Grass and leaves are grey in the atlas and coloured by the biome at render time; distant
        // terrain has to do the same or biomes lose their identity beyond the vanilla distance.
        if (state.is(BlockTags.LEAVES)) {
            return multiply(base, BiomeColors.getAverageFoliageColor(level, pos));
        }
        if (state.is(Blocks.GRASS_BLOCK)) {
            return multiply(base, BiomeColors.getAverageGrassColor(level, pos));
        }
        return base;
    }

    /** Reads one light layer, falling back to full darkness if the engine has no data yet. */
    private int lightLevel(ClientLevel level, LightLayer layer, int x, int y, int z) {
        return level.getLightEngine().getLayerListener(layer).getLightValue(at(x, y, z));
    }

    private BlockState stateAt(LevelChunk chunk, int x, int y, int z) {
        return chunk.getBlockState(at(x, y, z));
    }

    private BlockPos.MutableBlockPos at(int x, int y, int z) {
        return cursor.set(x, y, z);
    }

    /** Multiplies two packed colours channel by channel, as the vanilla tint pass does. */
    static int multiply(int base, int tint) {
        int r = (((base >>> 16) & 0xFF) * ((tint >>> 16) & 0xFF)) / 255;
        int g = (((base >>> 8) & 0xFF) * ((tint >>> 8) & 0xFF)) / 255;
        int b = ((base & 0xFF) * (tint & 0xFF)) / 255;
        return (r << 16) | (g << 8) | b;
    }

    /** Clamps a world Y into the range a data point can represent. */
    static int clampY(int y) {
        return Math.max(LodDataPoint.MIN_Y, Math.min(LodDataPoint.MAX_Y, y));
    }
}
