package com.aetheria.config;

import com.aetheria.core.LodDetailLevel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that the configuration survives a round trip and never accepts a harmful value. */
class AetheriaConfigTest {

    @TempDir
    Path directory;

    @Test
    void aMissingFileYieldsWorkingDefaults() throws IOException {
        AetheriaConfig config = AetheriaConfig.load(directory.resolve("absent.properties"));

        assertTrue(config.lodRenderDistance() > 0);
        assertTrue(config.memoryBudgetMegabytes() > 0);
        assertTrue(config.cacheEnabled());
    }

    @Test
    void settingsRoundTripThroughTheFile() throws IOException {
        Path path = directory.resolve("aetheria.properties");
        AetheriaConfig original = new AetheriaConfig();
        original.setLodRenderDistance(512);
        original.setFullDetailRadius(64);
        original.setFinestDetailLevel(LodDetailLevel.QUARTER);
        original.setDynamicDetailEnabled(false);
        original.setMemoryBudgetMegabytes(1024);
        original.setCacheMaxAgeDays(7);
        original.setRenderDistantWater(false);

        original.save(path);
        AetheriaConfig reloaded = AetheriaConfig.load(path);

        assertEquals(512, reloaded.lodRenderDistance());
        assertEquals(64, reloaded.fullDetailRadius());
        assertEquals(LodDetailLevel.QUARTER, reloaded.finestDetailLevel());
        assertFalse(reloaded.dynamicDetailEnabled());
        assertEquals(1024, reloaded.memoryBudgetMegabytes());
        assertEquals(7, reloaded.cacheMaxAgeDays());
        assertFalse(reloaded.renderDistantWater());
    }

    @Test
    void outOfRangeValuesAreClampedRatherThanRejected() {
        AetheriaConfig config = new AetheriaConfig();

        config.setLodRenderDistance(-100);
        config.setMemoryBudgetMegabytes(0);
        config.setSpansPerColumn(999);
        config.setDynamicDetailLimit(-3.0);

        assertTrue(config.lodRenderDistance() >= 16);
        assertTrue(config.memoryBudgetMegabytes() >= 32);
        assertTrue(config.spansPerColumn() <= 8);
        assertEquals(0.0, config.dynamicDetailLimit(), 1e-9);
    }

    @Test
    void anUnparsableValueFallsBackToTheDefault() {
        AetheriaConfig config = new AetheriaConfig();
        int before = config.lodRenderDistance();

        Properties broken = new Properties();
        broken.setProperty("render.lodRenderDistance", "not a number");
        broken.setProperty("render.dynamicDetailLimit", "");
        config.apply(broken);

        assertEquals(before, config.lodRenderDistance());
    }

    @Test
    void unknownKeysAreIgnoredRatherThanFailing() throws IOException {
        Path path = directory.resolve("future.properties");
        Files.writeString(path, """
                render.lodRenderDistance=128
                render.someSettingFromAFutureVersion=yes
                """);

        AetheriaConfig config = AetheriaConfig.load(path);

        assertEquals(128, config.lodRenderDistance());
    }

    @Test
    void disablingDynamicDetailZeroesTheEffectiveLimit() {
        AetheriaConfig config = new AetheriaConfig();
        config.setDynamicDetailLimit(3.0);

        config.setDynamicDetailEnabled(false);
        assertEquals(0.0, config.effectiveDynamicDetailLimit(), 1e-9);

        config.setDynamicDetailEnabled(true);
        assertEquals(3.0, config.effectiveDynamicDetailLimit(), 1e-9);
    }

    @Test
    void theMemoryBudgetIsReportedInBytes() {
        AetheriaConfig config = new AetheriaConfig();
        config.setMemoryBudgetMegabytes(256);

        assertEquals(256L * 1024L * 1024L, config.memoryBudgetBytes());
    }

    @Test
    void savingCreatesTheParentDirectory() throws IOException {
        Path path = directory.resolve("nested/deeper/aetheria.properties");

        new AetheriaConfig().save(path);

        assertTrue(Files.isRegularFile(path));
    }
}
