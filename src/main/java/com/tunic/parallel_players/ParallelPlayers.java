package com.tunic.parallel_players;

import com.tunic.parallel_players.character.ActiveCharacter;
import com.tunic.parallel_players.character.CharacterRules;
import com.tunic.parallel_players.character.CharacterStore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ParallelPlayers implements ModInitializer {

    public static final String MOD_ID = "parallelplayers";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Folder in the game directory holding every character and the active-character file. */
    private static final String FOLDER = "characters";
    /** Where earlier versions kept it: {@code parallelplayers/characters} and {@code parallelplayers/active_character.txt}. */
    private static final String LEGACY_FOLDER = MOD_ID;
    private static final String ACTIVE_FILE = "active_character.txt";

    private static CharacterStore characterStore;
    private static ActiveCharacter activeCharacter;

    /** The global character store, shared by every world. */
    public static CharacterStore characters() {
        if (characterStore == null) {
            characterStore = new CharacterStore(characterDir());
        }
        return characterStore;
    }

    /** The character the local player is playing as. */
    public static ActiveCharacter activeCharacter() {
        if (activeCharacter == null) {
            activeCharacter = new ActiveCharacter(characterDir().resolve(ACTIVE_FILE), characters());
        }
        return activeCharacter;
    }

    private static Path characterDir() {
        return FabricLoader.getInstance().getGameDir().resolve(FOLDER);
    }

    @Override
    public void onInitialize() {
        migrateLegacyFolder();
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                CharacterRules.onJoin(server, handler.getPlayer()).ifPresent(refusal -> handler.disconnect(refusal.message())));
    }

    /**
     * Moves data from the old {@code parallelplayers} folder to {@code characters}, so characters made
     * before the rename are still there. Does nothing if there is no old folder or the new one exists.
     */
    private static void migrateLegacyFolder() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path legacy = gameDir.resolve(LEGACY_FOLDER);
        Path target = characterDir();
        if (!Files.isDirectory(legacy)) {
            return;
        }
        if (Files.exists(target)) {
            LOGGER.warn("Both {} and {} exist; leaving the old folder alone", legacy, target);
            return;
        }
        try {
            Path legacyCharacters = legacy.resolve("characters");
            if (Files.isDirectory(legacyCharacters)) {
                Files.move(legacyCharacters, target);
            } else {
                Files.createDirectories(target);
            }
            Path legacyActive = legacy.resolve(ACTIVE_FILE);
            if (Files.isRegularFile(legacyActive)) {
                Files.move(legacyActive, target.resolve(ACTIVE_FILE));
            }
            try {
                Files.delete(legacy); // only succeeds if nothing else is left in it
            } catch (IOException ignored) {
                LOGGER.info("Left {} in place, it still contains other files", legacy);
            }
            LOGGER.info("Moved characters from {} to {}", legacy, target);
        } catch (IOException e) {
            LOGGER.error("Could not move characters from {} to {}", legacy, target, e);
        }
    }
}
