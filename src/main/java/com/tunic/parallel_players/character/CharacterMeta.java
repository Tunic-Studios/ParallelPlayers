package com.tunic.parallel_players.character;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Lightweight description of a character, persisted as {@code meta.json} in the character's folder.
 *
 * <p>{@code skin} and {@code slim} describe the {@link CharacterSkin}; {@code mode} is the id of the
 * {@link CharacterMode}. All three are absent in files written before they existed: those read as
 * "use the account's skin" and "no mode yet" (the character takes the mode of the first world it joins).
 */
public record CharacterMeta(UUID id, String name, long createdAt, long lastPlayedAt,
                            @Nullable String skin, boolean slim, @Nullable String mode) {

    public CharacterMeta withName(String newName) {
        return new CharacterMeta(id, newName, createdAt, lastPlayedAt, skin, slim, mode);
    }

    public CharacterMeta withLastPlayedAt(long time) {
        return new CharacterMeta(id, name, createdAt, time, skin, slim, mode);
    }

    public CharacterMeta withSkin(CharacterSkin newSkin) {
        return new CharacterMeta(id, name, createdAt, lastPlayedAt, newSkin.id(), newSkin.slim(), mode);
    }

    public CharacterMeta withMode(CharacterMode newMode) {
        return new CharacterMeta(id, name, createdAt, lastPlayedAt, skin, slim, newMode.id());
    }

    public CharacterSkin skinChoice() {
        return new CharacterSkin(skin, slim);
    }

    /** The character's mode, or {@code null} if it has none yet. */
    public @Nullable CharacterMode modeChoice() {
        return CharacterMode.byId(mode);
    }
}
