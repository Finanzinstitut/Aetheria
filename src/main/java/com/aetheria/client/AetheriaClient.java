package com.aetheria.client;

import com.aetheria.Aetheria;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Client entry point: wires Aetheria into the game's lifecycle and render loop.
 *
 * <p>Every hook used here comes from the Fabric API rather than from a mixin. Fabric's events are
 * far more stable across Minecraft versions than the internals a mixin would have to target, so
 * keeping the injection surface this small is what makes the mod cheap to port forward.
 *
 * <p>The engine is created lazily on the first tick inside a world and rebuilt whenever the player
 * changes dimension, so its cache and its geometry never describe a world the player has left.
 */
public final class AetheriaClient implements ClientModInitializer {

    private static LodEngine engine;

    /** Dimension the current engine was built for, used to detect a dimension change. */
    private static String activeDimension;

    /** Returns the engine for the current world, or {@code null} if no world is loaded. */
    public static LodEngine engine() {
        return engine;
    }

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(AetheriaClient::onClientTick);

        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            if (engine != null) {
                engine.onChunkLoaded(level, chunk);
            }
        });

        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            if (engine != null) {
                engine.onChunkUnloaded(chunk.getPos().x(), chunk.getPos().z());
            }
        });

        // Distant terrain is submitted after the vanilla terrain passes, so that fully detailed
        // chunks always take priority over the LOD geometry standing in for the same blocks.
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context -> {
            if (engine != null) {
                engine.renderer().render(context, engine.policy());
            }
        });

        AetheriaDebugHud.register();

        Aetheria.logger().info("{} client ready", Aetheria.MOD_NAME);
    }

    private static void onClientTick(Minecraft client) {
        ClientLevel level = client.level;

        if (level == null) {
            shutdownEngine();
            return;
        }

        String dimension = level.dimension().identifier().toString();
        if (engine == null || !dimension.equals(activeDimension)) {
            shutdownEngine();
            activeDimension = dimension;
            engine = new LodEngine(dimension);
        }

        engine.tick(client);
    }

    /** Tears the engine down, flushing the cache. Safe to call when no engine exists. */
    public static void shutdownEngine() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
        activeDimension = null;
    }

    /**
     * Applies a configuration change at runtime by rebuilding the engine.
     *
     * <p>Thread counts and buffer sizes are fixed when the engine starts, so the honest way to
     * apply a change to them is a clean restart rather than a partial reconfiguration.
     */
    public static void reload() {
        Aetheria.reloadConfig();
        String dimension = activeDimension;
        shutdownEngine();
        if (dimension != null) {
            activeDimension = dimension;
            engine = new LodEngine(dimension);
        }
    }
}
