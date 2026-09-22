package com.aetheria.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the precomputed shade table.
 *
 * <p>The table replaces per-quad arithmetic in the hot path, so the point of these tests is that it
 * is an optimisation and not a behaviour change: every entry must equal what the direct computation
 * would have produced.
 */
class LodShadeTableTest {

    private static final byte[] FACES = {
            LodMesh.FACE_UP, LodMesh.FACE_NORTH, LodMesh.FACE_SOUTH,
            LodMesh.FACE_WEST, LodMesh.FACE_EAST,
    };

    @Test
    void everyEntryMatchesTheDirectComputation() {
        LodShadeTable table = new LodShadeTable();

        for (int skyLevel = 0; skyLevel <= LodShadeTable.MAX_SKY_LEVEL; skyLevel++) {
            table.update(skyLevel);
            float skyBrightness = (float) skyLevel / LodShadeTable.MAX_SKY_LEVEL;

            for (byte face : FACES) {
                for (int light = 0; light < LodShadeTable.LIGHT_COUNT; light++) {
                    byte packed = (byte) light;
                    assertEquals(
                            LodShadeTable.factorFor(face, packed, skyBrightness),
                            table.factor(face, packed),
                            1e-6f,
                            "mismatch at sky=" + skyLevel + " face=" + face + " light=" + light);
                }
            }
        }
    }

    @Test
    void theTableOnlyRebuildsWhenTheSkyLevelChanges() {
        LodShadeTable table = new LodShadeTable();

        assertTrue(table.update(15), "the first update must build the table");
        assertFalse(table.update(15), "an unchanged sky level must not rebuild");
        assertTrue(table.update(14), "a changed sky level must rebuild");
        assertEquals(14, table.skyLevel());
    }

    @Test
    void outOfRangeSkyLevelsAreClamped() {
        LodShadeTable table = new LodShadeTable();

        table.update(-5);
        assertEquals(0, table.skyLevel());

        table.update(99);
        assertEquals(LodShadeTable.MAX_SKY_LEVEL, table.skyLevel());
    }

    @Test
    void topFacesAreBrighterThanSideFaces() {
        LodShadeTable table = new LodShadeTable();
        table.update(LodShadeTable.MAX_SKY_LEVEL);
        byte fullSky = (byte) 0xF0;

        float up = table.factor(LodMesh.FACE_UP, fullSky);
        float northSouth = table.factor(LodMesh.FACE_NORTH, fullSky);
        float eastWest = table.factor(LodMesh.FACE_EAST, fullSky);

        assertTrue(up > northSouth, "top faces must be the brightest");
        assertTrue(northSouth > eastWest, "north and south must be brighter than east and west");
        assertEquals(table.factor(LodMesh.FACE_SOUTH, fullSky), northSouth, 1e-6f);
        assertEquals(table.factor(LodMesh.FACE_WEST, fullSky), eastWest, 1e-6f);
    }

    @Test
    void blockLightKeepsSurfacesVisibleAtNight() {
        LodShadeTable table = new LodShadeTable();
        table.update(0);

        byte unlit = (byte) 0x00;
        byte torchLit = (byte) 0x0F;

        assertTrue(table.factor(LodMesh.FACE_UP, torchLit) > table.factor(LodMesh.FACE_UP, unlit),
                "block light must survive nightfall, since it does not depend on the sun");
    }

    @Test
    void nothingIsEverFullyBlack() {
        LodShadeTable table = new LodShadeTable();
        table.update(0);

        for (byte face : FACES) {
            for (int light = 0; light < LodShadeTable.LIGHT_COUNT; light++) {
                assertTrue(table.factor(face, (byte) light) > 0.0f,
                        "a factor of zero would render terrain as a black silhouette");
            }
        }
    }

    @Test
    void anUnknownFaceFallsBackInsteadOfThrowing() {
        LodShadeTable table = new LodShadeTable();
        table.update(8);

        // Reading the table must never be the thing that takes a frame down.
        assertTrue(table.factor((byte) 99, (byte) 0) > 0.0f);
        assertTrue(table.factor((byte) -3, (byte) 0) > 0.0f);
    }

    @Test
    void highLightBytesAreNotSignExtendedIntoTheIndex() {
        LodShadeTable table = new LodShadeTable();
        table.update(15);

        // A packed light byte above 0x7F is negative as a Java byte; masking must keep it in range.
        assertEquals(LodShadeTable.factorFor(LodMesh.FACE_UP, (byte) 0xFF, 1.0f),
                table.factor(LodMesh.FACE_UP, (byte) 0xFF), 1e-6f);
    }
}
