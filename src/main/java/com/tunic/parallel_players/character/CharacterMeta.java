package com.tunic.parallel_players.character;

import java.util.UUID;

/**
 * Lightweight description of a character, persisted as {@code meta.json} in the character's folder.
 */
public record CharacterMeta(UUID id, String name, long createdAt, long lastPlayedAt) {

    public CharacterMeta withName(String newName) {
        return new CharacterMeta(id, newName, createdAt, lastPlayedAt);
    }

    public CharacterMeta withLastPlayedAt(long time) {
        return new CharacterMeta(id, name, createdAt, time);
    }
}
