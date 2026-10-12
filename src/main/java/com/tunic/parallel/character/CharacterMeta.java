package com.tunic.parallel.character;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Lightweight description of a character, persisted as {@code meta.json} in the character's folder.
 *
 * <p>{@code skin} and {@code slim} describe the {@link CharacterSkin}. {@code gamemode} is chosen when the
 * character is created and never changes.
 *
 * <p>{@code saveId} is a random id given to every save of the character's player data, wherever it happened.
 * When a character is played on a server the client and the server each keep a copy, and comparing save ids
 * (see {@link CharacterSwap#choose}) tells which of them changed since they were last in step.
 */
public record CharacterMeta(UUID id, String name, long createdAt, long lastPlayedAt,
                            @Nullable String skin, boolean slim, CharacterGamemode gamemode, long saveId) {

    public CharacterMeta withName(String newName) {
        return new CharacterMeta(id, newName, createdAt, lastPlayedAt, skin, slim, gamemode, saveId);
    }

    public CharacterMeta withLastPlayedAt(long time) {
        return new CharacterMeta(id, name, createdAt, time, skin, slim, gamemode, saveId);
    }

    public CharacterMeta withSkin(CharacterSkin newSkin) {
        return new CharacterMeta(id, name, createdAt, lastPlayedAt, newSkin.id(), newSkin.slim(), gamemode, saveId);
    }

    public CharacterMeta withSaveId(long newSaveId) {
        return new CharacterMeta(id, name, createdAt, lastPlayedAt, skin, slim, gamemode, newSaveId);
    }

    public CharacterSkin skinChoice() {
        return new CharacterSkin(skin, slim);
    }
}
