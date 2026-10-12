package com.tunic.parallel.config;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

/**
 * Per-player permissions. They go through fabric-permissions-api, so a permissions mod such as LuckPerms
 * can grant or deny them per player or group; without one, everyone gets the default.
 */
public final class ParallelPermissions {

    /** May play as a character. Default: everyone. */
    public static final String USE_CHARACTERS = "parallel.characters.use";
    /**
     * May take a character into a world of another gamemode. Default: nobody, not even operators, since in
     * singleplayer the owner of a world with cheats is an operator, and the rule is what keeps survival
     * characters out of those worlds.
     */
    public static final String BYPASS_GAMEMODES = "parallel.gamemodes.bypass";

    private ParallelPermissions() {
    }

    /**
     * Checked while the player's data is loaded, before the player exists. Without a permissions mod the
     * answer is immediate; with one it may wait for the player's permissions to be looked up.
     */
    public static boolean canUseCharacters(NameAndId player) {
        return Permissions.check(player, USE_CHARACTERS, true).join();
    }

    public static boolean bypassesGamemodes(ServerPlayer player) {
        return Permissions.check(player, BYPASS_GAMEMODES, false);
    }

    /** {@link #bypassesGamemodes(ServerPlayer)} while the player is still connecting; see {@link #canUseCharacters}. */
    public static boolean bypassesGamemodes(NameAndId player) {
        return Permissions.check(player, BYPASS_GAMEMODES, false).join();
    }
}
