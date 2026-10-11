package com.tunic.parallel_players.character;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Lightweight description of a character, persisted as {@code meta.json} in the character's folder.
 *
 * <p>{@code skin} and {@code slim} describe the {@link CharacterSkin}; they are absent in files written
 * before skins existed, which read as "use the account's skin".
 */
public record CharacterMeta(UUID id, String name, long createdAt, long lastPlayedAt, @Nullable String skin, boolean slim) {

    public CharacterMeta withName(String newName) {
        return new CharacterMeta(id, newName, createdAt, lastPlayedAt, skin, slim);
    }

    public CharacterMeta withLastPlayedAt(long time) {
        return new CharacterMeta(id, name, createdAt, time, skin, slim);
    }

    public CharacterMeta withSkin(CharacterSkin newSkin) {
        return new CharacterMeta(id, name, createdAt, lastPlayedAt, newSkin.id(), newSkin.slim());
    }

    public CharacterSkin skinChoice() {
        return new CharacterSkin(skin, slim);
    }
}
