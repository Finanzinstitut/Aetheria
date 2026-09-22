package com.aetheria.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies greedy meshing: that it merges what it should, keeps apart what it must, and produces
 * correctly wound geometry.
 */
class GreedyMesherTest {

    private static final int GRASS = 0x5E9A3C;

    /** Fills a chunk with a flat surface of uniform colour at the given height. */
    private static LodChunk flatChunk(LodDetailLevel level, int height, int rgb) {
        LodChunk chunk = new LodChunk(0, 0, level, 1);
        for (int z = 0; z < level.columnsPerChunk(); z++) {
            for (int x = 0; x < level.columnsPerChunk(); x++) {
                chunk.set(x, z, 0,
                        LodDataPoint.pack(height, height, rgb, 0, 15, LodDataPoint.FLAG_EXISTS));
            }
        }
        return chunk;
    }

    @Test
    void aFlatUniformChunkCollapsesToASingleTopQuad() {
        LodChunk chunk = flatChunk(LodDetailLevel.BLOCK, 64, GRASS);
        LodMeshBuilder builder = new LodMeshBuilder();

        new GreedyMesher(0).mesh(chunk, builder);
        LodMesh mesh = builder.build();

        // One top quad covering the whole chunk, plus four skirts down to the unloaded neighbours.
        long topQuads = countFaces(mesh, LodMesh.FACE_UP);
        assertEquals(1, topQuads, "a flat uniform chunk must merge into one top quad");
        assertEquals(5, mesh.quadCount());
    }

    @Test
    void mergingIsAnOrderOfMagnitudeBetterThanOneQuadPerColumn() {
        LodChunk chunk = flatChunk(LodDetailLevel.BLOCK, 64, GRASS);
        LodMeshBuilder builder = new LodMeshBuilder();

        new GreedyMesher(0).mesh(chunk, builder);

        int naive = LodDetailLevel.BLOCK.columnCount();
        assertTrue(builder.quadCount() * 10 < naive,
                "greedy meshing produced " + builder.quadCount() + " quads against " + naive);
    }

    @Test
    void columnsAtDifferentHeightsAreNotMerged() {
        LodChunk chunk = flatChunk(LodDetailLevel.QUARTER, 64, GRASS);
        chunk.set(0, 0, 0, LodDataPoint.pack(80, 80, GRASS, 0, 15, LodDataPoint.FLAG_EXISTS));
        LodMeshBuilder builder = new LodMeshBuilder();

        new GreedyMesher(0).mesh(chunk, builder);
        LodMesh mesh = builder.build();

        assertTrue(countFaces(mesh, LodMesh.FACE_UP) >= 2,
                "a raised column must not be merged into the surrounding surface");
    }

    @Test
    void coloursWithinToleranceMergeAndColoursBeyondItDoNot() {
        LodChunk lenient = flatChunk(LodDetailLevel.QUARTER, 64, GRASS);
        lenient.set(1, 1, 0, LodDataPoint.pack(64, 64, GRASS + 0x000005, 0, 15,
                LodDataPoint.FLAG_EXISTS));

        LodMeshBuilder builder = new LodMeshBuilder();
        new GreedyMesher(12).mesh(lenient, builder);
        assertEquals(1, countFaces(builder.build(), LodMesh.FACE_UP),
                "a colour difference inside the tolerance must still merge");

        builder.reset();
        new GreedyMesher(0).mesh(lenient, builder);
        assertTrue(countFaces(builder.build(), LodMesh.FACE_UP) > 1,
                "with a zero tolerance, differing colours must stay apart");
    }

    @Test
    void emptyColumnsProduceNoGeometry() {
        LodChunk chunk = new LodChunk(0, 0, LodDetailLevel.QUARTER, 1);
        LodMeshBuilder builder = new LodMeshBuilder();

        int quads = new GreedyMesher().mesh(chunk, builder);

        assertEquals(0, quads);
        assertTrue(builder.build().isEmpty());
    }

    @Test
    void everyQuadIsWoundCounterClockwiseAroundItsOutwardNormal() {
        LodChunk chunk = flatChunk(LodDetailLevel.QUARTER, 64, GRASS);
        // A pit in the middle forces interior skirts in all four directions.
        chunk.set(1, 1, 0, LodDataPoint.pack(50, 50, GRASS, 0, 15, LodDataPoint.FLAG_EXISTS));
        LodMeshBuilder builder = new LodMeshBuilder();

        new GreedyMesher(0).mesh(chunk, builder);
        LodMesh mesh = builder.build();

        assertTrue(mesh.quadCount() > 5, "the fixture should produce interior skirts");
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            float[] normal = normalOf(mesh, quad);
            float[] expected = expectedNormal(mesh.facing()[quad]);
            float dot = normal[0] * expected[0] + normal[1] * expected[1] + normal[2] * expected[2];
            assertTrue(dot > 0.0f,
                    "quad " + quad + " with facing " + mesh.facing()[quad]
                            + " is wound against its outward normal (dot " + dot + ")");
        }
    }

    @Test
    void skirtsReachDownToTheLowestNeighbour() {
        LodChunk chunk = flatChunk(LodDetailLevel.QUARTER, 64, GRASS);
        chunk.set(1, 1, 0, LodDataPoint.pack(90, 40, GRASS, 0, 15, LodDataPoint.FLAG_EXISTS));
        LodMeshBuilder builder = new LodMeshBuilder();

        new GreedyMesher(0).mesh(chunk, builder);
        LodMesh mesh = builder.build();

        // The raised pillar's walls must span from its own top down to the surrounding surface,
        // otherwise the player sees through the cliff.
        assertTrue(mesh.maxY() >= 91.0f, "the pillar's top face is missing");
        assertTrue(mesh.minY() <= 65.0f, "the pillar's skirt does not reach the neighbouring surface");
    }

    @Test
    void aSkirtIsNotCutShortByTheSurfaceSpansOwnBase() {
        // Regression: the skirt used to stop at the surface span's base, which is only where the
        // material stops being uniform - one block down for grass over dirt. Every cliff taller
        // than that span opened a see-through hole.
        LodChunk chunk = flatChunk(LodDetailLevel.QUARTER, 30, GRASS);
        // A plateau one block thick, sitting 40 blocks above its neighbours.
        chunk.set(1, 1, 0, LodDataPoint.pack(70, 70, GRASS, 0, 15, LodDataPoint.FLAG_EXISTS));
        LodMeshBuilder builder = new LodMeshBuilder();

        new GreedyMesher(0).mesh(chunk, builder);
        LodMesh mesh = builder.build();

        assertTrue(mesh.minY() <= 31.0f,
                "the cliff face must reach the surrounding terrain at y=31, but stopped at "
                        + mesh.minY());
    }

    @Test
    void aChunkBorderGetsABoundedSkirtRatherThanAHoleOrAWall() {
        // The mesher cannot see the neighbouring chunk, so the border skirt is a bounded drop:
        // deep enough to hide the seam, not so deep that it hangs in the sky.
        LodChunk chunk = flatChunk(LodDetailLevel.QUARTER, 100, GRASS);
        LodMeshBuilder builder = new LodMeshBuilder();

        new GreedyMesher(0).mesh(chunk, builder);
        LodMesh mesh = builder.build();

        assertTrue(mesh.minY() < 101.0f, "a border skirt must exist at all");
        assertTrue(mesh.minY() >= 101.0f - 64.0f,
                "the border skirt must stay bounded, but reached " + mesh.minY());
    }

    @Test
    void skirtsStayBoundedEvenAgainstAnExtremeHeightDifference() {
        LodChunk chunk = flatChunk(LodDetailLevel.QUARTER, 64, GRASS);
        chunk.set(1, 1, 0, LodDataPoint.pack(2000, 2000, GRASS, 0, 15, LodDataPoint.FLAG_EXISTS));
        LodMeshBuilder builder = new LodMeshBuilder();

        new GreedyMesher(0).mesh(chunk, builder);
        LodMesh mesh = builder.build();

        // Measure the tallest single quad: the mesh's own minY also covers the flat terrain's
        // border skirts far below, so it says nothing about the spike's wall.
        assertTrue(tallestQuad(mesh) <= 513.0f,
                "one freak column must not produce a kilometre-tall wall, but a quad spanned "
                        + tallestQuad(mesh));
    }

    /** Returns the greatest vertical extent of any single quad in the mesh. */
    private static float tallestQuad(LodMesh mesh) {
        float tallest = 0.0f;
        float[] p = mesh.positions();
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            int base = quad * LodMesh.FLOATS_PER_QUAD;
            float low = Float.MAX_VALUE;
            float high = -Float.MAX_VALUE;
            for (int vertex = 0; vertex < 4; vertex++) {
                float y = p[base + vertex * 3 + 1];
                low = Math.min(low, y);
                high = Math.max(high, y);
            }
            tallest = Math.max(tallest, high - low);
        }
        return tallest;
    }

    @Test
    void colorDistanceUsesTheLargestChannelDifference() {
        assertEquals(0, GreedyMesher.colorDistance(0x102030, 0x102030));
        assertEquals(0x20, GreedyMesher.colorDistance(0x102030, 0x104030));
    }

    private static int countFaces(LodMesh mesh, byte face) {
        int count = 0;
        for (int i = 0; i < mesh.quadCount(); i++) {
            if (mesh.facing()[i] == face) {
                count++;
            }
        }
        return count;
    }

    /** Computes a quad's geometric normal from the cross product of its first two edges. */
    private static float[] normalOf(LodMesh mesh, int quad) {
        float[] p = mesh.positions();
        int base = quad * LodMesh.FLOATS_PER_QUAD;

        float ax = p[base + 3] - p[base];
        float ay = p[base + 4] - p[base + 1];
        float az = p[base + 5] - p[base + 2];
        float bx = p[base + 6] - p[base + 3];
        float by = p[base + 7] - p[base + 4];
        float bz = p[base + 8] - p[base + 5];

        return new float[] {ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
    }

    private static float[] expectedNormal(byte face) {
        return switch (face) {
            case LodMesh.FACE_UP -> new float[] {0, 1, 0};
            case LodMesh.FACE_NORTH -> new float[] {0, 0, -1};
            case LodMesh.FACE_SOUTH -> new float[] {0, 0, 1};
            case LodMesh.FACE_WEST -> new float[] {-1, 0, 0};
            case LodMesh.FACE_EAST -> new float[] {1, 0, 0};
            default -> throw new IllegalArgumentException("unknown facing: " + face);
        };
    }
}
