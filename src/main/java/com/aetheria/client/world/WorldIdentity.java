package com.aetheria.client.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.MinecraftServer;

/**
 * Derives a stable identifier for the world the player is currently in.
 *
 * <p>The chunk cache has to be scoped by world, not merely by dimension. Chunk coordinates repeat
 * across every world in existence: {@code 0, 0} in one server's lobby is also {@code 0, 0} in a
 * single-player survival world. A cache keyed only by dimension therefore serves one world's
 * terrain to another, and the mod renders a blend of landscapes that never existed.
 *
 * <p>A server is identified by its address rather than its display name, because the name is
 * whatever the player typed into their server list and two entries can share it.
 */
public final class WorldIdentity {

    /** Used when neither a server nor a local world can be identified. */
    public static final String UNKNOWN = "unknown";

    private WorldIdentity() {
    }

    /**
     * Returns an identifier for the current world, suitable for use as a directory name.
     *
     * @param client the running client
     * @return {@code mp_<address>} on a server, {@code sp_<world folder>} in single player, or
     *         {@link #UNKNOWN} if neither can be determined
     */
    public static String current(Minecraft client) {
        MinecraftServer local = client.getSingleplayerServer();
        if (local != null) {
            // The save folder name, which is unique within the saves directory and survives a
            // rename of the world's display name.
            java.nio.file.Path directory = local.getServerDirectory();
            java.nio.file.Path name = directory == null ? null : directory.getFileName();
            return name == null ? UNKNOWN : "sp_" + sanitise(name.toString());
        }

        ServerData server = client.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return "mp_" + sanitise(server.ip);
        }
        return UNKNOWN;
    }

    /**
     * Turns an arbitrary identifier into a safe directory name.
     *
     * <p>Server addresses carry colons for ports and single-player folders can hold anything the
     * player typed, so everything outside a conservative set is replaced.
     */
    static String sanitise(String raw) {
        // Check before substituting, or a name of only spaces would become a run of underscores
        // rather than falling back.
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        StringBuilder builder = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            builder.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.'
                    ? c : '_');
        }
        String result = builder.toString();
        // Guard against names that a file system would reject or resolve elsewhere.
        if (result.equals(".") || result.equals("..")) {
            return UNKNOWN;
        }
        // Keep the name well inside every file system's component limit.
        return result.length() > 96 ? result.substring(0, 96) : result;
    }
}
