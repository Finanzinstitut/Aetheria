package com.aetheria.client.render;

import com.aetheria.core.LodMesh;
import com.aetheria.core.LodShadeTable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.SubmitNodeCollector;

/**
 * Writes a {@link LodMesh} into Minecraft's vertex pipeline.
 *
 * <p>This is the only class in Aetheria that writes vertices. Everything else &mdash; the LOD data
 * model, the greedy mesher, the cache and the scheduling &mdash; is plain Java that knows nothing
 * about OpenGL or about Minecraft's renderer. Concentrating the graphics calls here means that
 * porting the mod to a new Minecraft version is nearly always a change to this one class, and it is
 * the first place to look if the mod stops drawing after a game update.
 *
 * <p>Geometry is written as untextured coloured quads. Distant terrain has no business sampling the
 * block atlas: at these distances a texture is at most a couple of pixels, and skipping it removes
 * every atlas lookup and mipmap sample from the far terrain pass. Light is folded into the vertex
 * colour for the same reason &mdash; one multiply on the CPU replaces a lightmap sample per
 * fragment &mdash; and the factor for that multiply comes from a {@link LodShadeTable} rather than
 * being recomputed per quad.
 */
public final class LodGeometrySubmitter {

    private LodGeometrySubmitter() {
    }

    /**
     * A reusable submission callback for one render region.
     *
     * <p>The collector takes a callback per submitted node. Creating that callback as a lambda
     * would allocate one object per visible region per frame, which at an extreme render distance
     * is hundreds of short-lived objects every frame and a steady drip of garbage collector
     * pressure for a mod whose whole purpose is smooth frame times. Each region instead keeps one
     * of these and refreshes its state just before submitting.
     *
     * <p>A region submits at most once per frame and its callback runs later in that same frame,
     * so reusing the instance is safe: nothing overwrites the state between the submit and the
     * draw.
     */
    public static final class RegionRenderer implements SubmitNodeCollector.CustomGeometryRenderer {

        private LodMesh mesh = LodMesh.EMPTY;
        private LodShadeTable shadeTable;
        private float offsetX;
        private float offsetY;
        private float offsetZ;

        /**
         * Points the callback at what it should draw this frame.
         *
         * @param mesh       the region's geometry
         * @param shadeTable brightness factors for the current sky level
         * @param offsetX    world X of the region origin, relative to the camera
         * @param offsetY    world Y offset, relative to the camera
         * @param offsetZ    world Z of the region origin, relative to the camera
         */
        public void prepare(LodMesh mesh, LodShadeTable shadeTable, float offsetX, float offsetY,
                            float offsetZ) {
            this.mesh = mesh;
            this.shadeTable = shadeTable;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
        }

        @Override
        public void render(PoseStack.Pose pose, VertexConsumer consumer) {
            submit(consumer, mesh, shadeTable, offsetX, offsetY, offsetZ);
        }
    }

    /**
     * Writes every quad of a mesh into the given consumer.
     *
     * @param consumer   destination vertex consumer
     * @param mesh       the mesh to write
     * @param shadeTable brightness factors for the current sky level
     * @param offsetX    world X of the mesh origin, relative to the camera
     * @param offsetY    world Y offset, relative to the camera
     * @param offsetZ    world Z of the mesh origin, relative to the camera
     * @return the number of quads written
     */
    public static int submit(VertexConsumer consumer, LodMesh mesh, LodShadeTable shadeTable,
                             float offsetX, float offsetY, float offsetZ) {
        float[] positions = mesh.positions();
        int[] colors = mesh.colors();
        byte[] light = mesh.light();
        byte[] facing = mesh.facing();
        int quadCount = mesh.quadCount();

        for (int quad = 0; quad < quadCount; quad++) {
            int rgb = colors[quad];
            float factor = shadeTable.factor(facing[quad], light[quad]);

            int red = clampByte((int) (((rgb >>> 16) & 0xFF) * factor));
            int green = clampByte((int) (((rgb >>> 8) & 0xFF) * factor));
            int blue = clampByte((int) ((rgb & 0xFF) * factor));

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

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
