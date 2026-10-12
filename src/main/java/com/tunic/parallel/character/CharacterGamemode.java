package com.tunic.parallel.character;

import com.google.gson.annotations.SerializedName;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.world.level.GameType;

import java.util.Locale;

/**
 * The rules a character plays by. A character may only enter worlds of its own kind, so a survival
 * character can never be taken into a creative world, and a hardcore character stays in hardcore worlds.
 */
public enum CharacterGamemode {
    @SerializedName("survival") SURVIVAL,
    @SerializedName("creative") CREATIVE,
    @SerializedName("hardcore") HARDCORE;

    public static final StreamCodec<ByteBuf, CharacterGamemode> STREAM_CODEC = ByteBufCodecs.idMapper(
            ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO), Enum::ordinal);

    /**
     * Which kind a world is, from its settings:
     * <ul>
     *   <li>{@link #HARDCORE} if the world is hardcore,</li>
     *   <li>{@link #CREATIVE} if its default game mode is Creative or cheats are allowed, since either
     *       lets the player leave survival rules,</li>
     *   <li>{@link #SURVIVAL} otherwise.</li>
     * </ul>
     */
    public static CharacterGamemode ofWorld(boolean hardcore, GameType defaultGameType, boolean cheatsAllowed) {
        if (hardcore) {
            return HARDCORE;
        }
        return defaultGameType == GameType.CREATIVE || cheatsAllowed ? CREATIVE : SURVIVAL;
    }

    /** Whether a character of this gamemode may join a world of the given kind. */
    public boolean canJoin(CharacterGamemode world) {
        return this == world;
    }

    /** The lower-case name used in the saved data, sprites and translation keys. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "parallel.gamemode." + id();
    }

    public String descriptionKey() {
        return "parallel.gamemode." + id() + ".description";
    }
}
