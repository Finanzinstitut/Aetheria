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
    void blendAveragesColoursAndSpansTheFullHeightRange() {
        long[] inputs = {
                LodDataPoint.pack(70, 60, 0x000000, 0, 10, LodDataPoint.FLAG_EXISTS),
                LodDataPoint.pack(90, 80, 0x404040, 4, 14, LodDataPoint.FLAG_EXISTS),
                LodDataPoint.EMPTY,
                LodDataPoint.EMPTY,
        };

        long blended = LodDataPoint.blend(inputs, 0, 4);

        assertEquals(90, LodDataPoint.topY(blended), "coarse terrain must not sink below the fine terrain");
        assertEquals(60, LodDataPoint.bottomY(blended));
        assertEquals(0x202020, LodDataPoint.rgb(blended));
        assertEquals(2, LodDataPoint.blockLight(blended));
        assertEquals(12, LodDataPoint.skyLight(blended));
        assertTrue(LodDataPoint.hasFlag(blended, LodDataPoint.FLAG_MERGED));
    }

    @Test
    void blendingNothingYieldsEmpty() {
        long[] inputs = {LodDataPoint.EMPTY, LodDataPoint.EMPTY};

        assertEquals(LodDataPoint.EMPTY, LodDataPoint.blend(inputs, 0, 2));
    }
}
