package com.aetheria;

import com.aetheria.config.AetheriaConfig;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Common entry point.
 *
 * <p>Aetheria is a client-side mod: it draws terrain the server never asked it to draw, and needs
 * no server component at all. This initializer therefore does nothing but load the configuration
 * and resolve the paths the client side will use, so that installing the mod on a server is
 * harmless rather than an error.
 */
public final class Aetheria implements ModInitializer {

    /** The mod identifier, matching {@code fabric.mod.json}. */
    public static final String MOD_ID = "aetheria";

    /** Human-readable name used in logs and user interfaces. */
    public static final String MOD_NAME = "Aetheria";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private static AetheriaConfig config = new AetheriaConfig();

    /** Returns the shared logger. */
    public static Logger logger() {
        return LOGGER;
    }

    /** Returns the active configuration. */
    public static AetheriaConfig config() {
        return config;
    }

    /** Returns the path of the configuration file. */
    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(AetheriaConfig.FILE_NAME);
    }

    /** Returns the root directory holding the LOD cache for every world and server. */
    public static Path cacheRoot() {
        return FabricLoader.getInstance().getGameDir().resolve("aetheria-cache");
    }

    @Override
    public void onInitialize() {
        reloadConfig();
        LOGGER.info("{} initialized; LOD render distance {} chunks, memory budget {} MB",
                MOD_NAME, config.lodRenderDistance(), config.memoryBudgetMegabytes());
    }

    /**
     * Re-reads the configuration file, writing a default one if none exists.
     *
     * <p>A broken or unreadable file is reported and then ignored: the mod falls back to defaults
     * rather than refusing to start, because a configuration problem should never cost the player
     * their session.
     */
    public static void reloadConfig() {
        Path path = configPath();
        try {
            config = AetheriaConfig.load(path);
            config.save(path);
        } catch (IOException e) {
            LOGGER.error("Could not read {}; continuing with default settings", path, e);
            config = new AetheriaConfig();
        }
    }

    /** Writes the current configuration to disk, reporting rather than throwing on failure. */
    public static void saveConfig() {
        try {
            config.save(configPath());
        } catch (IOException e) {
            LOGGER.error("Could not write {}", configPath(), e);
        }
    }
}
