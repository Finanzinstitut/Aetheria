package com.aetheria.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the packed data point layout, which every other component depends on. */
class LodDataPointTest {

    @Test
    void packRoundTripsEveryField() {
        long dataPoint = LodDataPoint.pack(96, 64, 0x3F7A21, 7, 15,
                LodDataPoint.FLAG_EXISTS | LodDataPoint.FLAG_TRANSPARENT);

        assertEquals(96, LodDataPoint.topY(dataPoint));
        assertEquals(64, LodDataPoint.bottomY(dataPoint));
        assertEquals(33, LodDataPoint.height(dataPoint));
        assertEquals(0x3F7A21, LodDataPoint.rgb(dataPoint));
        assertEquals(7, LodDataPoint.blockLight(dataPoint));
        assertEquals(15, LodDataPoint.skyLight(dataPoint));
        assertTrue(LodDataPoint.exists(dataPoint));
        assertTrue(LodDataPoint.hasFlag(dataPoint, LodDataPoint.FLAG_TRANSPARENT));
    }

    @Test
    void negativeHeightsSurviveTheRoundTrip() {
        long dataPoint = LodDataPoint.pack(-16, -64, 0x808080, 0, 0, LodDataPoint.FLAG_EXISTS);

        assertEquals(-16, LodDataPoint.topY(dataPoint));
        assertEquals(-64, LodDataPoint.bottomY(dataPoint));
    }

    @Test
    void extremeYValuesRoundTrip() {
        long low = LodDataPoint.pack(LodDataPoint.MIN_Y, LodDataPoint.MIN_Y, 0, 0, 0,
                LodDataPoint.FLAG_EXISTS);
        long high = LodDataPoint.pack(LodDataPoint.MAX_Y, LodDataPoint.MAX_Y, 0xFFFFFF, 15, 15,
                LodDataPoint.FLAG_EXISTS);

        assertEquals(LodDataPoint.MIN_Y, LodDataPoint.topY(low));
        assertEquals(LodDataPoint.MAX_Y, LodDataPoint.topY(high));
        assertEquals(0xFFFFFF, LodDataPoint.rgb(high));
    }

    @Test
    void emptyDataPointDoesNotExist() {
        assertFalse(LodDataPoint.exists(LodDataPoint.EMPTY));
    }

    @Test
    void outOfRangeHeightsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LodDataPoint.pack(LodDataPoint.MAX_Y + 1, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> LodDataPoint.pack(LodDataPoint.MIN_Y - 1, LodDataPoint.MIN_Y - 1, 0, 0, 0, 0));
    }

    @Test
    void invertedSpansAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LodDataPoint.pack(10, 20, 0, 0, 0, LodDataPoint.FLAG_EXISTS));
    }

    @Test
    void lightLevelsAreClampedRatherThanOverflowing() {
        long dataPoint = LodDataPoint.pack(0, 0, 0, 99, -5, LodDataPoint.FLAG_EXISTS);

        assertEquals(15, LodDataPoint.blockLight(dataPoint));
        assertEquals(0, LodDataPoint.skyLight(dataPoint));
    }

    @Test
    void blendAveragesEveryFieldIncludingHeight() {
        long[] inputs = {
                LodDataPoint.pack(70, 60, 0x000000, 0, 10, LodDataPoint.FLAG_EXISTS),
                LodDataPoint.pack(90, 80, 0x404040, 4, 14, LodDataPoint.FLAG_EXISTS),
                LodDataPoint.EMPTY,
                LodDataPoint.EMPTY,
        };

        long blended = LodDataPoint.blend(inputs, 0, 4);

        assertEquals(80, LodDataPoint.topY(blended));
        assertEquals(70, LodDataPoint.bottomY(blended));
        assertEquals(0x202020, LodDataPoint.rgb(blended));
        assertEquals(2, LodDataPoint.blockLight(blended));
        assertEquals(12, LodDataPoint.skyLight(blended));
        assertTrue(LodDataPoint.hasFlag(blended, LodDataPoint.FLAG_MERGED));
    }

    @Test
    void oneTallColumnDoesNotDragItsNeighboursUp() {
        // Regression: taking the maximum height here made a single tall block win every
        // down-sampling round, turning one tree or tower into a pillar covering a whole chunk.
        long ground = LodDataPoint.pack(64, 64, 0x404040, 0, 15, LodDataPoint.FLAG_EXISTS);
        long spike = LodDataPoint.pack(300, 64, 0x404040, 0, 15, LodDataPoint.FLAG_EXISTS);
        long[] inputs = {spike, ground, ground, ground};

        long blended = LodDataPoint.blend(inputs, 0, 4);

        assertTrue(LodDataPoint.topY(blended) < 130,
                "one tall column must not dominate the cell, but reached "
                        + LodDataPoint.topY(blended));
        assertTrue(LodDataPoint.topY(blended) > 64, "it must still raise the average a little");
    }

    @Test
    void blendNeverProducesAnInvertedSpan() {
        // The bottom is averaged independently of the top, so guard the invariant pack() demands.
        long[] inputs = {
                LodDataPoint.pack(64, 64, 0x111111, 0, 0, LodDataPoint.FLAG_EXISTS),
                LodDataPoint.pack(64, 10, 0x111111, 0, 0, LodDataPoint.FLAG_EXISTS),
                LodDataPoint.pack(65, 65, 0x111111, 0, 0, LodDataPoint.FLAG_EXISTS),
                LodDataPoint.pack(63, 63, 0x111111, 0, 0, LodDataPoint.FLAG_EXISTS),
        };

        long blended = LodDataPoint.blend(inputs, 0, 4);

        assertTrue(LodDataPoint.topY(blended) >= LodDataPoint.bottomY(blended));
    }

    @Test
    void repeatedBlendingDoesNotDriftDownwards() {
        // Down-sampling runs once per level; truncation instead of rounding would sink terrain
        // a little further on each of those rounds.
        long point = LodDataPoint.pack(65, 65, 0x222222, 0, 15, LodDataPoint.FLAG_EXISTS);
        long[] inputs = {point, point, point, point};

        long blended = point;
        for (int level = 0; level < 4; level++) {
            inputs = new long[] {blended, blended, blended, blended};
            blended = LodDataPoint.blend(inputs, 0, 4);
        }

        assertEquals(65, LodDataPoint.topY(blended), "uniform terrain must keep its height exactly");
    }

    @Test
    void blendingNothingYieldsEmpty() {
        long[] inputs = {LodDataPoint.EMPTY, LodDataPoint.EMPTY};

        assertEquals(LodDataPoint.EMPTY, LodDataPoint.blend(inputs, 0, 2));
    }
}
