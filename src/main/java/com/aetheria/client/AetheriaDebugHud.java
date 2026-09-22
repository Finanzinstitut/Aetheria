package com.aetheria.client;

import com.aetheria.Aetheria;
import com.aetheria.cache.LodChunkCache;
import com.aetheria.client.render.LodRenderer;
import com.aetheria.concurrent.AetheriaExecutors;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * An optional on-screen overlay reporting what the LOD pipeline is doing.
 *
 * <p>The numbers here are the ones that matter when tuning the mod on a particular machine: how
 * much memory the resident LOD data uses, how deep the worker queues are, and how many quads
 * survive greedy meshing and frustum culling. A player reporting a performance problem can read all
 * of it off one screen, which is far more useful than a log file after the fact.
 *
 * <p>The overlay is hidden by default and toggled with a key binding, so it costs nothing while
 * nobody is looking at it.
 */
public final class AetheriaDebugHud {

    /** Identifier of the overlay's HUD element. */
    public static final Identifier ELEMENT_ID =
            Identifier.fromNamespaceAndPath(Aetheria.MOD_ID, "performance_overlay");

    /** Key binding category the toggle appears under in the controls screen. */
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Aetheria.MOD_ID, "main"));

    private static KeyMapping toggleKey;
    private static boolean visible;

    private AetheriaDebugHud() {
    }

    /** Registers the key binding and the overlay element. Called once during client startup. */
    public static void register() {
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.aetheria.toggle_overlay",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F6,
                CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick()) {
                visible = !visible;
                Aetheria.logger().debug("Aetheria overlay {}", visible ? "shown" : "hidden");
            }
        });

        HudElementRegistry.addLast(ELEMENT_ID, (graphics, deltaTracker) -> {
            if (!visible) {
                return;
            }
            Minecraft client = Minecraft.getInstance();
            int y = 4;
            for (String line : buildLines()) {
                graphics.text(client.font, line, 4, y, 0xFFFF_FFFF, true);
                y += 10;
            }
        });
    }

    /** Returns {@code true} while the overlay is being shown. */
    public static boolean isVisible() {
        return visible;
    }

    /** Builds the overlay text. Package-private so it can be exercised without a game running. */
    static List<String> buildLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Aetheria - distant terrain");

        LodEngine engine = AetheriaClient.engine();
        if (engine == null) {
            lines.add("  no world loaded");
            return lines;
        }

        LodChunkCache.Stats stats = engine.cache().stats();
        LodRenderer renderer = engine.renderer();
        AetheriaExecutors executors = engine.executors();

        lines.add(String.format("  dimension: %s", engine.dimensionId()));
        lines.add(String.format("  drawing: %d regions, %d quads (%d culled)",
                renderer.drawnRegions(), renderer.drawnQuads(), renderer.culledRegions()));
        lines.add(String.format("  resident: %d chunks, %.1f MB (budget %d MB)",
                stats.residentChunks(), stats.residentMegabytes(),
                Aetheria.config().memoryBudgetMegabytes()));
        lines.add(String.format("  cache: %.1f%% hit, %d read, %d written",
                stats.hitRate() * 100.0, stats.diskLoads(), stats.diskWrites()));
        lines.add(String.format("  queues: %d mesh, %d I/O, %d pending writes",
                executors.pendingMeshTasks(), executors.pendingIoTasks(), stats.queuedWrites()));
        lines.add(String.format("  camera: %.1f blocks/s, %.0f blocks up, detail bias %.2f",
                renderer.cameraSpeed(), renderer.cameraAltitude(),
                engine.policy().dynamicBias(renderer.cameraAltitude(), renderer.cameraSpeed())));
        return lines;
    }
}
