package com.aetheria.config;

import com.aetheria.core.LodDetailLevel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Every user-tunable setting in Aetheria, with its defaults and validation.
 *
 * <p>The file is a plain {@code .properties} document so that it can be read, diffed and edited by
 * hand, and so that the mod carries no serialization dependency of its own. Unknown keys are
 * preserved on save and out-of-range values are clamped rather than rejected: a configuration file
 * from a newer or older version must never prevent the game from starting.
 *
 * <p>Instances are mutable but are only written from the client thread. The render and worker
 * threads read a snapshot taken at the start of each frame.
 */
public final class AetheriaConfig {

    /** Default name of the configuration file inside the game's config directory. */
    public static final String FILE_NAME = "aetheria.properties";

    // ---- Rendering -------------------------------------------------------

    /** How far LOD terrain is rendered, in chunks, measured from the camera. */
    private int lodRenderDistance = 256;

    /** Distance in chunks rendered at the finest configured detail level. */
    private int fullDetailRadius = 32;

    /** Shift of the finest detail level used; see {@link LodDetailLevel}. */
    private int finestDetailShift = LodDetailLevel.HALF.shift();

    /** Whether altitude and speed are allowed to reduce detail dynamically. */
    private boolean dynamicDetailEnabled = true;

    /** Maximum number of levels the dynamic terms may coarsen detail by. */
    private double dynamicDetailLimit = 2.0;

    /** Maximum per-channel colour difference that still allows two columns to merge. */
    private int colorTolerance = 12;

    /** Whether distant water is rendered in the translucent pass. */
    private boolean renderDistantWater = true;

    // ---- Performance -----------------------------------------------------

    /** Meshing threads, or zero to size from the available cores. */
    private int meshThreads = 0;

    /** Cache I/O threads, or zero for the default. */
    private int ioThreads = 0;

    /** Soft ceiling on the heap used by resident LOD chunks, in megabytes. */
    private int memoryBudgetMegabytes = 256;

    /** Vertical spans kept per column; more spans render overhangs and caves better. */
    private int spansPerColumn = 4;

    /** Maximum number of region rebuilds queued per frame, to bound worker queue growth. */
    private int maxRegionBuildsPerFrame = 8;

    // ---- Caching ---------------------------------------------------------

    /** Whether visited chunks are written to and read back from disk. */
    private boolean cacheEnabled = true;

    /** Age in days after which a cached chunk is re-scanned from the live world. Zero disables. */
    private int cacheMaxAgeDays = 30;

    /** Whether region files are compacted when leaving a world. */
    private boolean compactOnExit = true;

    // ---- Accessors -------------------------------------------------------

    public int lodRenderDistance() {
        return lodRenderDistance;
    }

    public void setLodRenderDistance(int value) {
        this.lodRenderDistance = clamp(value, 16, 4096);
    }

    public int fullDetailRadius() {
        return fullDetailRadius;
    }

    public void setFullDetailRadius(int value) {
        this.fullDetailRadius = clamp(value, 1, 512);
    }

    public LodDetailLevel finestDetailLevel() {
        return LodDetailLevel.ofShift(finestDetailShift);
    }

    public void setFinestDetailLevel(LodDetailLevel level) {
        this.finestDetailShift = level.shift();
    }

    public boolean dynamicDetailEnabled() {
        return dynamicDetailEnabled;
    }

    public void setDynamicDetailEnabled(boolean value) {
        this.dynamicDetailEnabled = value;
    }

    /** Returns the effective dynamic bias limit, which is zero when dynamic detail is off. */
    public double effectiveDynamicDetailLimit() {
        return dynamicDetailEnabled ? dynamicDetailLimit : 0.0;
    }

    public double dynamicDetailLimit() {
        return dynamicDetailLimit;
    }

    public void setDynamicDetailLimit(double value) {
        this.dynamicDetailLimit = clamp(value, 0.0, 4.0);
    }

    public int colorTolerance() {
        return colorTolerance;
    }

    public void setColorTolerance(int value) {
        this.colorTolerance = clamp(value, 0, 64);
    }

    public boolean renderDistantWater() {
        return renderDistantWater;
    }

    public void setRenderDistantWater(boolean value) {
        this.renderDistantWater = value;
    }

    public int meshThreads() {
        return meshThreads;
    }

    public void setMeshThreads(int value) {
        this.meshThreads = clamp(value, 0, 32);
    }

    public int ioThreads() {
        return ioThreads;
    }

    public void setIoThreads(int value) {
        this.ioThreads = clamp(value, 0, 16);
    }

    public int memoryBudgetMegabytes() {
        return memoryBudgetMegabytes;
    }

    public void setMemoryBudgetMegabytes(int value) {
        this.memoryBudgetMegabytes = clamp(value, 32, 8192);
    }

    /** Returns the memory budget in bytes. */
    public long memoryBudgetBytes() {
        return (long) memoryBudgetMegabytes * 1024L * 1024L;
    }

    public int spansPerColumn() {
        return spansPerColumn;
    }

    public void setSpansPerColumn(int value) {
        this.spansPerColumn = clamp(value, 1, 8);
    }

    public int maxRegionBuildsPerFrame() {
        return maxRegionBuildsPerFrame;
    }

    public void setMaxRegionBuildsPerFrame(int value) {
        this.maxRegionBuildsPerFrame = clamp(value, 1, 64);
    }

    public boolean cacheEnabled() {
        return cacheEnabled;
    }

    public void setCacheEnabled(boolean value) {
        this.cacheEnabled = value;
    }

    public int cacheMaxAgeDays() {
        return cacheMaxAgeDays;
    }

    public void setCacheMaxAgeDays(int value) {
        this.cacheMaxAgeDays = clamp(value, 0, 3650);
    }

    public boolean compactOnExit() {
        return compactOnExit;
    }

    public void setCompactOnExit(boolean value) {
        this.compactOnExit = value;
    }

    // ---- Persistence -----------------------------------------------------

    /**
     * Loads the configuration from a file, falling back to the defaults for anything missing.
     *
     * @param path file to read; a missing file yields a default configuration
     * @return the loaded configuration, never {@code null}
     * @throws IOException if the file exists but cannot be read
     */
    public static AetheriaConfig load(Path path) throws IOException {
        AetheriaConfig config = new AetheriaConfig();
        if (!Files.isRegularFile(path)) {
            return config;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        }
        config.apply(properties);
        return config;
    }

    /**
     * Writes the configuration.
     *
     * <p>The file is written to a temporary sibling and moved into place, so an interrupted save
     * cannot leave the player with a truncated configuration.
     */
    public void save(Path path) throws IOException {
        Properties properties = toProperties();
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temporary)) {
            properties.store(out, "Aetheria configuration - see README.md for the full reference");
        }
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Applies every recognised key from the given properties, ignoring the rest. */
    void apply(Properties properties) {
        setLodRenderDistance(readInt(properties, "render.lodRenderDistance", lodRenderDistance));
        setFullDetailRadius(readInt(properties, "render.fullDetailRadius", fullDetailRadius));
        setFinestDetailLevel(LodDetailLevel.ofShift(
                readInt(properties, "render.finestDetailShift", finestDetailShift)));
        setDynamicDetailEnabled(
                readBoolean(properties, "render.dynamicDetailEnabled", dynamicDetailEnabled));
        setDynamicDetailLimit(
                readDouble(properties, "render.dynamicDetailLimit", dynamicDetailLimit));
        setColorTolerance(readInt(properties, "render.colorTolerance", colorTolerance));
        setRenderDistantWater(
                readBoolean(properties, "render.renderDistantWater", renderDistantWater));
        setMeshThreads(readInt(properties, "performance.meshThreads", meshThreads));
        setIoThreads(readInt(properties, "performance.ioThreads", ioThreads));
        setMemoryBudgetMegabytes(
                readInt(properties, "performance.memoryBudgetMegabytes", memoryBudgetMegabytes));
        setSpansPerColumn(readInt(properties, "performance.spansPerColumn", spansPerColumn));
        setMaxRegionBuildsPerFrame(readInt(properties,
                "performance.maxRegionBuildsPerFrame", maxRegionBuildsPerFrame));

        setCacheEnabled(readBoolean(properties, "cache.enabled", cacheEnabled));
        setCacheMaxAgeDays(readInt(properties, "cache.maxAgeDays", cacheMaxAgeDays));
        setCompactOnExit(readBoolean(properties, "cache.compactOnExit", compactOnExit));
    }

    /** Returns the configuration as properties, in the same key namespace it is read from. */
    Properties toProperties() {
        Properties properties = new Properties();
        properties.setProperty("render.lodRenderDistance", Integer.toString(lodRenderDistance));
        properties.setProperty("render.fullDetailRadius", Integer.toString(fullDetailRadius));
        properties.setProperty("render.finestDetailShift", Integer.toString(finestDetailShift));
        properties.setProperty("render.dynamicDetailEnabled", Boolean.toString(dynamicDetailEnabled));
        properties.setProperty("render.dynamicDetailLimit", Double.toString(dynamicDetailLimit));
        properties.setProperty("render.colorTolerance", Integer.toString(colorTolerance));
        properties.setProperty("render.renderDistantWater", Boolean.toString(renderDistantWater));

        properties.setProperty("performance.meshThreads", Integer.toString(meshThreads));
        properties.setProperty("performance.ioThreads", Integer.toString(ioThreads));
        properties.setProperty("performance.memoryBudgetMegabytes",
                Integer.toString(memoryBudgetMegabytes));
        properties.setProperty("performance.spansPerColumn", Integer.toString(spansPerColumn));
        properties.setProperty("performance.maxRegionBuildsPerFrame",
                Integer.toString(maxRegionBuildsPerFrame));

        properties.setProperty("cache.enabled", Boolean.toString(cacheEnabled));
        properties.setProperty("cache.maxAgeDays", Integer.toString(cacheMaxAgeDays));
        properties.setProperty("cache.compactOnExit", Boolean.toString(compactOnExit));
        return properties;
    }

    private static int readInt(Properties properties, String key, int fallback) {
        String raw = properties.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double readDouble(Properties properties, String key, double fallback) {
        String raw = properties.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean readBoolean(Properties properties, String key, boolean fallback) {
        String raw = properties.getProperty(key);
        return raw == null ? fallback : Boolean.parseBoolean(raw.trim());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
