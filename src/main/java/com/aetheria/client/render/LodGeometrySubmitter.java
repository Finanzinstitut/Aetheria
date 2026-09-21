package com.aetheria.client.render;

import com.aetheria.core.LodMesh;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Writes a {@link LodMesh} into Minecraft's vertex pipeline.
 *
 * <p>This is the only class in Aetheria that talks to the graphics API. Everything else &mdash; the
 * LOD data model, the greedy mesher, the cache and the scheduling &mdash; is plain Java that knows
 * nothing about OpenGL or about Minecraft's renderer. Concentrating the graphics calls here means
 * that porting the mod to a new Minecraft version is nearly always a change to this one class, and
 * it is the first place to look if the mod stops drawing after a game update.
 *
 * <p>Geometry is written as untextured coloured quads. Distant terrain has no business sampling the
 * block atlas: at these distances a texture is at most a couple of pixels, and skipping it removes
 * every atlas lookup and mipmap sample from the far terrain pass. Light is folded into the vertex
 * colour for the same reason &mdash; one multiply on the CPU replaces a lightmap sample per
 * fragment.
 */
public final class LodGeometrySubmitter {

    /** Per-face brightness factors, matching the shading vanilla applies to block faces. */
    private static final float SHADE_UP = 1.0f;
    private static final float SHADE_NORTH_SOUTH = 0.8f;
    private static final float SHADE_EAST_WEST = 0.6f;

    /** Lowest brightness a fully unlit surface is drawn at, so caves are dim rather than black. */
    private static final float MIN_BRIGHTNESS = 0.15f;

    private LodGeometrySubmitter() {
    }

    /**
     * Writes every quad of a mesh into the given consumer.
     *
     * @param consumer destination vertex consumer
     * @param mesh     the mesh to write
     * @param offsetX  world X of the mesh origin, relative to the camera
     * @param offsetY  world Y offset, relative to the camera
     * @param offsetZ  world Z of the mesh origin, relative to the camera
     * @param skyBrightness current sky brightness, 0 at night and 1 at noon
     * @return the number of quads written
     */
    public static int submit(VertexConsumer consumer, LodMesh mesh, float offsetX, float offsetY,
                             float offsetZ, float skyBrightness) {
        float[] positions = mesh.positions();
        int[] colors = mesh.colors();
        byte[] light = mesh.light();
        byte[] facing = mesh.facing();
        int quadCount = mesh.quadCount();

        for (int quad = 0; quad < quadCount; quad++) {
            int shaded = shade(colors[quad], facing[quad], light[quad], skyBrightness);
            int red = (shaded >>> 16) & 0xFF;
            int green = (shaded >>> 8) & 0xFF;
            int blue = shaded & 0xFF;

            int base = quad * LodMesh.FLOATS_PER_QUAD;
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = base + vertex * 3;
                consumer.addVertex(
                                positions[offset] + offsetX,
                                positions[offset + 1] + offsetY,
                                positions[offset + 2] + offsetZ)
                        .setColor(red, green, blue, 0xFF);
            }
        }
        return quadCount;
    }

    /**
     * Applies face shading and baked lighting to a quad's colour.
     *
     * <p>Vanilla darkens block faces by a fixed factor per direction, which is what gives
     * untextured geometry a sense of volume without any lighting calculation. Matching those
     * factors is what makes LOD terrain read as continuous with the vanilla chunks in front of it
     * rather than as a flat cut-out.
     */
    static int shade(int rgb, byte face, byte packedLight, float skyBrightness) {
        float factor = switch (face) {
            case LodMesh.FACE_NORTH, LodMesh.FACE_SOUTH -> SHADE_NORTH_SOUTH;
            case LodMesh.FACE_EAST, LodMesh.FACE_WEST -> SHADE_EAST_WEST;
            default -> SHADE_UP;
        };

        // Sky light is scaled by the time of day; block light is not. Taking the brighter of the
        // two mirrors how the vanilla lightmap combines them.
        float sky = LodMesh.skyLightOf(packedLight) / 15.0f * clamp01(skyBrightness);
        float block = LodMesh.blockLightOf(packedLight) / 15.0f;
        factor *= Math.max(MIN_BRIGHTNESS, Math.max(sky, block));

        int red = clampByte((int) (((rgb >>> 16) & 0xFF) * factor));
        int green = clampByte((int) (((rgb >>> 8) & 0xFF) * factor));
        int blue = clampByte((int) ((rgb & 0xFF) * factor));
        return (red << 16) | (green << 8) | blue;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
