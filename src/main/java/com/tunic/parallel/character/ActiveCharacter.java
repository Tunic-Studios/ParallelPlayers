package com.tunic.parallel.character;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * The character the local player is currently playing as, persisted so it survives restarts.
 * When none is selected the game behaves exactly like vanilla.
 */
public final class ActiveCharacter {

    private static final Logger LOGGER = LoggerFactory.getLogger("parallel");

    private final Path file;
    private final CharacterStore store;
    private volatile UUID active;

    public ActiveCharacter(Path file, CharacterStore store) {
        this.file = file;
        this.store = store;
        this.active = load();
    }

    /** The selected character, or empty if none is selected or it no longer exists. */
    public Optional<UUID> get() {
        UUID id = active;
        if (id != null && store.get(id).isPresent()) {
            return Optional.of(id);
        }
        return Optional.empty();
    }

    public void set(UUID id) {
        write(id.toString());
        active = id;
    }

    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("Failed to clear active character", e);
        }
        active = null;
    }

    private UUID load() {
        try {
            if (Files.isRegularFile(file)) {
                return UUID.fromString(Files.readString(file, StandardCharsets.UTF_8).strip());
            }
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.warn("Ignoring unreadable active character file {}", file, e);
        }
        return null;
    }

    private void write(String value) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, value, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to save active character", e);
        }
    }
}
