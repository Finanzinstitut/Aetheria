package com.aetheria.mixin.client;

import com.aetheria.Aetheria;
import com.aetheria.client.AetheriaClient;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pushes the camera's far clipping plane out past the LOD ring.
 *
 * <p>Vanilla derives the far plane from the game's own render distance. Without this hook every
 * chunk Aetheria draws beyond that distance would be built, submitted and then clipped away by the
 * depth range before it ever reached a pixel: the distant terrain would simply not appear.
 *
 * <p>The value is written after the camera has finished filling its render state, so vanilla's own
 * calculation still runs and Aetheria only raises the result when it needs more room.
 *
 * <p>This is the mod's only mixin, and deliberately so: everything else runs through Fabric API
 * events, which are stable across game versions in a way that internals are not. If Aetheria ever
 * fails to load after a Minecraft update, this class is the first place to look.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    /**
     * Extends the far plane to cover the configured LOD render distance.
     *
     * <p>A margin is added beyond the ring so that terrain fades out through fog rather than being
     * cut off by a visible plane sweeping across the landscape as the player moves.
     */
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void aetheria$extendFarPlane(CameraRenderState renderState, float partialTick,
                                         CallbackInfo info) {
        if (AetheriaClient.engine() == null) {
            return;
        }
        // The ring is measured in chunks; convert to blocks and leave a 25% margin for fog.
        float required = Aetheria.config().lodRenderDistance() * 16.0f * 1.25f;
        if (required > renderState.depthFar) {
            renderState.depthFar = required;
        }
    }
}
