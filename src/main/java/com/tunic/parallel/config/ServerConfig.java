package com.tunic.parallel.config;

/**
 * Rules for the server side, in {@code config/parallel/server.json}. In singleplayer the world runs on a
 * server inside the client, so these also apply to every singleplayer world on this computer.
 *
 * <p>Read when a server starts, so changes to the file apply from the next world or restart.
 */
public final class ServerConfig {

    private static final String FILE = "server";

    private static volatile ServerConfig current = new ServerConfig();

    /** Whether players can play as characters here. When off, every world uses its own player data. */
    public boolean charactersEnabled = true;

    /** Whether a character may only enter worlds of its own gamemode (survival, creative or hardcore). */
    public boolean enforceGamemodes = true;

    /**
     * Whether operators see the usernames of players on characters, next to the character's name above their
     * head and in the tab list. A permissions mod can decide this per player instead, with
     * {@link ParallelPermissions#SEE_USERNAMES}; this is the default it falls back to.
     */
    public boolean revealUsernamesToOperators = true;

    /** Longest allowed {@link #syncIntervalSeconds}. */
    public static final int MAX_SYNC_INTERVAL = 600;

    /**
     * How often, in seconds, the characters of players who joined from another computer are saved and sent
     * back to their game, so a crash or lost connection costs at most this much play. 0 sends them only when
     * the game saves and when the player leaves.
     */
    public int syncIntervalSeconds = 60;

    public static ServerConfig get() {
        return current;
    }

    public static void reload() {
        ServerConfig loaded = ConfigFile.load(FILE, ServerConfig.class, ServerConfig::new);
        loaded.syncIntervalSeconds = Math.clamp(loaded.syncIntervalSeconds, 0, MAX_SYNC_INTERVAL);
        current = loaded;
    }

    /** Writes these settings to the file and makes them the current ones. */
    public void save() {
        ConfigFile.save(FILE, this);
        current = this;
    }
}
