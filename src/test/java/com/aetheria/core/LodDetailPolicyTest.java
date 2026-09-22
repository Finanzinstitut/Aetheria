package com.aetheria.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that detail selection is monotonic and that the dynamic terms stay bounded. */
class LodDetailPolicyTest {

    private final LodDetailPolicy policy =
            new LodDetailPolicy(32, LodDetailLevel.BLOCK, 2.0);

    @Test
    void nearTerrainUsesTheFinestConfiguredLevel() {
        assertEquals(LodDetailLevel.BLOCK, policy.levelFor(8, 0, 0));
        assertEquals(LodDetailLevel.BLOCK, policy.levelFor(32, 0, 0));
    }

    @Test
    void detailCoarsensWithEveryDoublingOfDistance() {
        assertEquals(0, policy.distanceBias(32));
        assertEquals(1, policy.distanceBias(48));
        assertEquals(2, policy.distanceBias(96));
        assertEquals(3, policy.distanceBias(192));
    }

    @Test
    void detailNeverGetsFinerAsDistanceGrows() {
        int previous = -1;
        for (int distance = 1; distance <= 1024; distance++) {
            int shift = policy.levelFor(distance, 0, 0).shift();
            assertTrue(shift >= previous, "detail got finer at distance " + distance);
            previous = shift;
        }
    }

    @Test
    void theCoarsestLevelIsNeverExceeded() {
        assertEquals(LodDetailLevel.coarsest(), policy.levelFor(100_000, 10_000, 10_000));
    }

    @Test
    void altitudeAndSpeedCoarsenDetailButOnlyUpToTheLimit() {
        assertEquals(0.0, policy.dynamicBias(0, 0), 1e-9);
        assertTrue(policy.dynamicBias(192, 0) > 0.9, "flying high should reduce detail");
        assertTrue(policy.dynamicBias(0, 24) > 0.9, "moving fast should reduce detail");
        assertEquals(2.0, policy.dynamicBias(10_000, 10_000), 1e-9, "the bias must stay bounded");
    }

    @Test
    void standingStillAtSeaLevelAppliesNoDynamicBias() {
        assertEquals(policy.levelFor(64, 0, 0), policy.levelFor(64, 0, 0));
        assertEquals(0.0, policy.dynamicBias(-100, -100), 1e-9,
                "being underground or stationary must not coarsen detail");
    }

    @Test
    void disablingDynamicDetailPinsTheBiasToZero() {
        LodDetailPolicy staticPolicy = new LodDetailPolicy(32, LodDetailLevel.BLOCK, 0.0);

        assertEquals(0.0, staticPolicy.dynamicBias(5000, 5000), 1e-9);
        assertEquals(staticPolicy.levelFor(64, 0, 0), staticPolicy.levelFor(64, 5000, 5000));
    }

    @Test
    void aCoarserFinestLevelShiftsEverythingCoarser() {
        LodDetailPolicy lowQuality = new LodDetailPolicy(32, LodDetailLevel.QUARTER, 0.0);

        assertEquals(LodDetailLevel.QUARTER, lowQuality.levelFor(8, 0, 0));
        assertEquals(LodDetailLevel.EIGHTH, lowQuality.levelFor(48, 0, 0));
    }
}
