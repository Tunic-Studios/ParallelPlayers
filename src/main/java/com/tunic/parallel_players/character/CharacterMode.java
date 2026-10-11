package com.tunic.parallel_players.character;

import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * The rules a character plays by. A character may only enter worlds of its own kind, so a survival
 * character can never be taken into a creative world, and a hardcore character stays in hardcore worlds.
 */
public enum CharacterMode {
    SURVIVAL,
    CREATIVE,
    HARDCORE;

    /**
     * Which kind a world is, from its settings:
     * <ul>
     *   <li>{@link #HARDCORE} if the world is hardcore,</li>
     *   <li>{@link #CREATIVE} if its default game mode is Creative or cheats are allowed, since either
     *       lets the player leave survival rules,</li>
     *   <li>{@link #SURVIVAL} otherwise.</li>
     * </ul>
     */
    public static CharacterMode ofWorld(boolean hardcore, GameType defaultGameType, boolean cheatsAllowed) {
        if (hardcore) {
            return HARDCORE;
        }
        return defaultGameType == GameType.CREATIVE || cheatsAllowed ? CREATIVE : SURVIVAL;
    }

    /** Whether a character of this mode may join a world of the given kind. */
    public boolean canJoin(CharacterMode world) {
        return this == world;
    }

    /** Key of the lower-case name used in the saved data and translation keys. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "parallelplayers.mode." + id();
    }

    public String descriptionKey() {
        return "parallelplayers.mode." + id() + ".description";
    }

    /** Parses a saved mode; unknown or missing values read as "no mode". */
    public static @Nullable CharacterMode byId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (CharacterMode mode : values()) {
            if (mode.id().equals(id)) {
                return mode;
            }
        }
        return null;
    }
}
