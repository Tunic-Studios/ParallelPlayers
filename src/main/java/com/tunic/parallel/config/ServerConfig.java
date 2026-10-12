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

    public static ServerConfig get() {
        return current;
    }

    public static void reload() {
        current = ConfigFile.load(FILE, ServerConfig.class, ServerConfig::new);
    }

    /** Writes these settings to the file and makes them the current ones. */
    public void save() {
        ConfigFile.save(FILE, this);
        current = this;
    }
}
