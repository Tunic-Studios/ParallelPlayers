package com.tunic.parallel.character;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Lightweight description of a character, persisted as {@code meta.json} in the character's folder.
 *
 * <p>{@code skin} and {@code slim} describe the {@link CharacterSkin}. {@code gamemode} is chosen when the
 * character is created and never changes.
 *
 * <p>{@code savedAt} is when the character's player data was last saved, wherever that was. When a
 * character is played on a server, the client and the server each keep a copy, and the one saved last wins.
 */
public record CharacterMeta(UUID id, String name, long createdAt, long lastPlayedAt,
                            @Nullable String skin, boolean slim, CharacterGamemode gamemode, long savedAt) {

    public CharacterMeta withName(String newName) {
        return new CharacterMeta(id, newName, createdAt, lastPlayedAt, skin, slim, gamemode, savedAt);
    }

    public CharacterMeta withLastPlayedAt(long time) {
        return new CharacterMeta(id, name, createdAt, time, skin, slim, gamemode, savedAt);
    }

    public CharacterMeta withSkin(CharacterSkin newSkin) {
        return new CharacterMeta(id, name, createdAt, lastPlayedAt, newSkin.id(), newSkin.slim(), gamemode, savedAt);
    }

    public CharacterMeta withSavedAt(long time) {
        return new CharacterMeta(id, name, createdAt, lastPlayedAt, skin, slim, gamemode, time);
    }

    public CharacterSkin skinChoice() {
        return new CharacterSkin(skin, slim);
    }
}
